package app.djmrecorder

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.SystemClock

/**
 * Reads the DJM-900NXS2's USB-MIDI interface (class 1 / subclass 3, "interface 2" per the kernel notes) and reports
 * Control Change messages. Two routes, tried in this order by [RecordService]:
 *   1. android.media.midi.MidiManager: the system exposes the class-compliant MIDI interface as a MidiDevice.
 *   2. Raw USB fallback: claim the MIDI interface on the engine's existing UsbDeviceConnection (interface 0, the
 *      audio capture, is not touched) and read the bulk IN endpoint of 4-byte USB-MIDI event packets.
 * [onCc] (controller number, value) is called on a background thread; callers must hop to the main thread.
 * [log] is thread safe on the caller's side.
 */
class MidiInput(private val ctx: Context, private val ui: Handler, private val log: (String) -> Unit,
                private val onCc: (Int, Int) -> Unit) {
    @Volatile var route = ""; private set                  // "", "android" or "usb"
    @Volatile var lastMsgAt = 0L; private set
    @Volatile var routeAt = 0L; private set
    @Volatile var managerOpen = false; private set
    @Volatile var rawActive = false; private set
    val startedAt = SystemClock.elapsedRealtime()

    private var mm: MidiManager? = null
    private var opening = false
    private var device: MidiDevice? = null
    private val ports = ArrayList<MidiOutputPort>()
    private var openedId = -1
    private var stopped = false

    private val parser = Parser()

    // ---- route 1: MidiManager ----
    private val cb = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) { consider(info) }
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info.id == openedId) { log("midi: device removed"); closeManagerDevice() }
        }
    }

    fun startManager() {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) { log("midi: no FEATURE_MIDI on this phone"); return }
        val m = try { ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager } catch (_: Exception) { null }
        if (m == null) { log("midi: no MidiManager"); return }
        mm = m
        try {
            m.registerDeviceCallback(cb, ui)
            val all = m.devices
            log("midi: MidiManager lists ${all.size} device(s)")
            for (i in all) {
                log("midi:   id ${i.id} '${name(i)}' in ${i.inputPortCount} out ${i.outputPortCount} type ${i.type}")
                consider(i)
            }
        } catch (e: Exception) { log("midi: manager scan failed: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun usbOf(i: MidiDeviceInfo): UsbDevice? = try { i.properties.getParcelable<UsbDevice>(MidiDeviceInfo.PROPERTY_USB_DEVICE) } catch (_: Exception) { null }

    private fun name(i: MidiDeviceInfo): String {
        val p = i.properties
        return (p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: "") + " " + (p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: p.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "")
    }

    private fun matches(i: MidiDeviceInfo): Boolean {
        if (i.type != MidiDeviceInfo.TYPE_USB) return false
        val u = usbOf(i)
        if (u != null) return u.vendorId == RecordService.VID_PIONEER
        val n = name(i)
        return n.contains("Pioneer", true) || n.contains("DJM", true)
    }

    private fun consider(info: MidiDeviceInfo) {
        if (stopped || opening || device != null || !matches(info) || info.outputPortCount <= 0) return
        val m = mm ?: return
        opening = true
        log("midi: opening '${name(info)}' via MidiManager")
        try {
            m.openDevice(info, { d ->
                opening = false
                if (d == null) { log("midi: MidiManager could not open the device (in use?) - raw USB fallback will be tried"); return@openDevice }
                if (stopped) { try { d.close() } catch (_: Exception) {}; return@openDevice }
                device = d; openedId = info.id
                for (p in 0 until info.outputPortCount) {
                    try {
                        val port = d.openOutputPort(p) ?: continue
                        port.connect(object : MidiReceiver() {
                            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                                if (offset < 0 || count <= 0 || offset + count > msg.size) return
                                parser.feed(msg, offset, count)
                            }
                        })
                        ports.add(port)
                    } catch (e: Exception) { log("midi: output port $p failed: ${e.message}") }
                }
                if (ports.isEmpty()) { log("midi: no output port could be opened"); closeManagerDevice(); return@openDevice }
                route = "android"; routeAt = SystemClock.elapsedRealtime(); managerOpen = true
                log("midi: connected through MidiManager (${ports.size} port(s))")
            }, ui)
        } catch (e: Exception) { opening = false; log("midi: openDevice failed: ${e.message}") }
    }

    private fun closeManagerDevice() {
        for (p in ports) try { p.close() } catch (_: Exception) {}
        ports.clear()
        try { device?.close() } catch (_: Exception) {}
        device = null; openedId = -1; managerOpen = false; opening = false
        if (route == "android") route = ""
    }

    // ---- route 2: raw USB on the engine's connection ----
    private var rawThread: Thread? = null
    @Volatile private var rawRun = false
    private var rawConn: UsbDeviceConnection? = null
    private var rawIf: UsbInterface? = null

    /** Claims the MIDI interface on [c] and starts reading its bulk IN endpoint. Returns false if there is none or the claim fails. */
    fun attachRaw(c: UsbDeviceConnection, dev: UsbDevice): Boolean {
        if (rawActive || managerOpen || stopped) return false
        var f: UsbInterface? = null; var ep: UsbEndpoint? = null
        for (i in 0 until dev.interfaceCount) {
            val x = dev.getInterface(i)
            if (x.interfaceClass == UsbConstants.USB_CLASS_AUDIO && x.interfaceSubclass == 3) {
                log("midi: MIDIStreaming interface ${x.id} alt ${x.alternateSetting}, ${x.endpointCount} endpoint(s)")
                for (e in 0 until x.endpointCount) {
                    val p = x.getEndpoint(e)
                    log("midi:   ep 0x%02x type %d maxpkt %d".format(p.address, p.type, p.maxPacketSize))
                    if (ep == null && p.type == UsbConstants.USB_ENDPOINT_XFER_BULK && p.direction == UsbConstants.USB_DIR_IN) { f = x; ep = p }
                }
            }
        }
        if (f == null || ep == null) { log("midi: no MIDIStreaming bulk IN endpoint found on this device"); return false }
        val ok = try { c.claimInterface(f, true) } catch (e: Exception) { log("midi: claim failed: ${e.message}"); false }
        if (!ok) { log("midi: could not claim MIDI interface ${f.id}"); return false }
        rawConn = c; rawIf = f
        rawRun = true; rawActive = true; route = "usb"; routeAt = SystemClock.elapsedRealtime()
        val ein = ep
        val size = maxOf(64, ein.maxPacketSize)
        rawThread = Thread({
            val buf = ByteArray(size)
            var errs = 0
            while (rawRun) {
                val t0 = SystemClock.elapsedRealtime()
                val n = try { c.bulkTransfer(ein, buf, buf.size, 250) } catch (_: Exception) { -2 }
                if (n < 0 && SystemClock.elapsedRealtime() - t0 < 100) {
                    // returned early with an error (device gone?) instead of timing out: do not spin
                    if (++errs > 40) { log("midi: raw read stopped (transfer error)"); break }
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    continue
                }
                if (n > 0) {
                    errs = 0
                    var o = 0
                    while (o + 4 <= n) {
                        // 4-byte USB-MIDI event: [cable<<4 | CIN, status, data1, data2]; CIN 0xB = Control Change
                        if ((buf[o].toInt() and 0x0f) == 0x0B && (buf[o + 1].toInt() and 0xf0) == 0xB0) {
                            lastMsgAt = SystemClock.elapsedRealtime()
                            onCc(buf[o + 2].toInt() and 0x7f, buf[o + 3].toInt() and 0x7f)
                        }
                        o += 4
                    }
                }
            }
            rawActive = false
        }, "midi-raw").also { it.isDaemon = true; it.start() }
        log("midi: reading MIDI interface ${f.id} directly over USB (ep 0x%02x)".format(ein.address))
        return true
    }

    /** Stops the raw reader and releases the MIDI interface. Must run before the connection is closed. */
    fun detachRaw() {
        if (rawThread == null && !rawActive) return
        rawRun = false
        try { rawThread?.join(700) } catch (_: Exception) {}
        rawThread = null
        try { rawIf?.let { rawConn?.releaseInterface(it) } } catch (_: Exception) {}
        rawIf = null; rawConn = null; rawActive = false
        if (route == "usb") route = ""
    }

    fun stop() {
        stopped = true
        detachRaw()
        try { mm?.unregisterDeviceCallback(cb) } catch (_: Exception) {}
        closeManagerDevice()
        mm = null
    }

    /** Parses a raw MIDI byte stream (MidiManager route): running status, realtime bytes and SysEx are skipped. */
    private inner class Parser {
        private var status = 0
        private val d = IntArray(2)
        private var cnt = 0
        private var need = 0
        fun feed(b: ByteArray, off: Int, len: Int) {
            for (k in off until off + len) {
                val v = b[k].toInt() and 0xff
                if (v >= 0xF8) continue                       // realtime (timing clock): does not touch running status
                if (v >= 0x80) {
                    cnt = 0
                    if (v >= 0xF0) { status = 0 }             // system messages / SysEx: ignore their data bytes
                    else { status = v; need = if (v in 0xC0..0xDF) 1 else 2 }
                } else if (status != 0) {
                    d[cnt++] = v
                    if (cnt >= need) {
                        if ((status and 0xf0) == 0xB0 && need == 2) { lastMsgAt = SystemClock.elapsedRealtime(); onCc(d[0], d[1]) }
                        cnt = 0
                    }
                }
            }
        }
    }
}
