package app.djmrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Owns the USB connection, the native capture engine and the recording session, so recording keeps going when
 * the activity is closed, rotated or the screen is off. All state is touched on the main thread only
 * (the activity binds and calls in on the main thread too); file writes go through [io].
 *
 *   IDLE      nothing running
 *   ARMED     capture runs, meters/waveform move, last N seconds kept in RAM (pre-roll), nothing on disk
 *   RECORDING files are being written (foreground service + wake lock)
 *   WAITING   recording session is open but the mixer is gone; files stay open, resumes on replug
 */
class RecordService : Service() {
    companion object {
        const val ACTION_PERM = "app.djmrecorder.USB_PERMISSION"
        const val ACTION_STOP = "app.djmrecorder.STOP"
        const val ACTION_MARKER = "app.djmrecorder.MARKER"
        const val ACTION_DISARM = "app.djmrecorder.DISARM"
        const val VID_PIONEER = 0x2b73
        const val PID_900NXS2 = 0x000a
        const val EP_IN = 0x82
        const val EP_OUT = 0x01
        const val CH_IN = 12
        const val CH_OUT = 10
        const val REC_PAIR = 4 // channels 9/10 = pair 5 = REC OUT
        val RATES = intArrayOf(44100, 48000, 96000)

        private const val CH_REC = "rec"
        private const val CH_ALERT = "alert"
        private const val NOTIF_ID = 1
        private const val ALERT_BATTERY = 10
        private const val ALERT_STORAGE = 11
        private const val ALERT_AUTO = 12
        private const val ALERT_ERROR = 13
        private const val ALERT_RECONNECT = 14
        private const val TICK_MS = 80L
        private val DEMO_TRACKS = listOf(
            DemoTrack(0.0, 1, "Demo Artist A", "Placeholder Track One", "link"),
            DemoTrack(40.0, 2, "Demo Artist B", "Placeholder Track Two", "link"),
            DemoTrack(95.0, 1, "", "", "midi"),
            DemoTrack(150.0, 2, "Demo Artist C", "Placeholder Track Three", "link")
        )
    }

    enum class State { IDLE, ARMED, RECORDING, WAITING }

    interface Listener {
        fun onTick()
        fun onState()
        fun onWave()
        fun onNote(msg: String)
        fun onLog(line: String)
        /** Files on disk were finalized (recordings list should reload). */
        fun onFilesChanged()
        /** The live tracklist changed (new or updated entry). */
        fun onTracks() {}
    }

    inner class LocalBinder : Binder() { val service get() = this@RecordService }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private lateinit var usb: UsbManager
    private lateinit var nm: NotificationManager
    private lateinit var pm: PowerManager
    lateinit var settings: Settings; private set
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    var listener: Listener? = null

    // ---------- public live state (read by the activity on the main thread) ----------
    var state = State.IDLE; private set
    var demo = false; private set
    val wave = WaveData()
    val markers = ArrayList<Double>()
    val events = ArrayList<DropEvent>()
    var currentFile: File? = null; private set
    var levelL = -90f; private set
    var levelR = -90f; private set
    val deckLevel = FloatArray(4) { -90f }
    val maxPk = floatArrayOf(-90f, -90f)
    val deckHold = BooleanArray(4)
    var dropouts = 0; private set
    var reconnects = 0; private set
    var recBytes = 0L; private set
    var freeBytes = 0L; private set
    var leftSec = 0L; private set
    var storageLevel = 0; private set          // 0 ok, 1 < 30 min (orange), 2 < 5 min (red), 3 < 30 s (stop)
    var batteryLow = false; private set
    var batteryPct = 100; private set
    var lastError: String? = null; private set
    var preFilledSec = 0f; private set          // pre-roll currently buffered (armed)
    var preArmedSec = 0; private set            // pre-roll length the running engine was armed with
    var stemsPreSec = 0; private set            // stems pre-roll actually available (32 MB cap)
    val logText = StringBuilder()
    var uiVisible = false; private set
    val trackLog = TrackLog { onTracksChanged() }
    val tracks: List<TrackEntry> get() = trackLog.entries
    var linkStatus = ""; private set

    // ---------- private state ----------
    private var conn: UsbDeviceConnection? = null
    private var intf: UsbInterface? = null
    private var engRate = 48000            // rate of the running engine / session
    private var engStems = true
    private var engFeedback = false
    private var engPid = PID_900NXS2        // product id of the device the engine/session uses
    private var fgs = false
    private var ticking = false
    private var tickN = 0L
    private var lastTickAt = 0L
    private var userDisarmed = false
    private var demoStart = 0L
    private var demoEntries = 0
    private var waveAbs = 0L               // wave entries drained since the engine was armed
    private var waveOut: FileOutputStream? = null
    private var stemsFile: File? = null
    private var recStartedAt = 0L
    private var waitSince = 0L
    private var waitRecPos = 0.0
    private var lastResumeTry = 0L
    private var permAskedAt = 0L
    private var resumeFails = 0
    private var aboveMs = 0L
    private var belowMs = 0L
    private var holdOff = false
    private var holdClearMs = 0L
    private var autoStarted = false
    private var batteryNotified = false
    private var storageNotified = 0
    private var lastQLost = 0L
    private var lastCtrMismatch = 0L
    private var lastWakeRefresh = 0L
    private var recFramesNow = 0L
    private var dead = false
    private var midi: MidiInput? = null
    private var link: ProLink? = null
    private var monitor: Monitor? = null
    private var midiFallbackTried = false
    private val linkTokens = HashMap<Long, TrackEntry>()
    private var demoTrackIdx = 0
    private val faders = FaderTracker(ui) { din, dout, at -> onMix(din, dout, at) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERM -> log(if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) "USB permission granted" else "USB permission denied")
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> log("USB device attached")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> log("USB device unplugged")
            }
            usbChanged()
        }
    }

    // ---------- lifecycle ----------
    override fun onCreate() {
        super.onCreate()
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        settings = Settings(this)
        nm.createNotificationChannel(NotificationChannel(CH_REC, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Get Rect is recording or armed"; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Warnings", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Low battery, low storage, dropouts, disconnects"
        })
        val filter = IntentFilter(ACTION_PERM).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
        log("Service created. Files: ${getExternalFilesDir(null)}")
        updateStorage()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_MARKER -> addMarker()
            ACTION_DISARM -> disarmByUser()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        dead = true
        ui.removeCallbacksAndMessages(null)
        listener = null
        if ((state == State.RECORDING || state == State.WAITING) && !demo) finishSession(null)
        if (state == State.ARMED) stopEngine()
        stopMidi(); stopLink(); stopMonitor()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        releaseWake()
        try { nm.cancel(NOTIF_ID) } catch (_: Exception) {}
        io.shutdown()
        super.onDestroy()
    }

    // ---------- logging ----------
    fun log(s: String) {
        logText.append(s).append('\n')
        if (logText.length > 40000) logText.delete(0, logText.length - 30000)
        val dir = getExternalFilesDir(null)
        try { io.execute { try { File(dir, "log.txt").appendText(s + "\n") } catch (_: Exception) {} } } catch (_: Exception) {}
        listener?.onLog(s)
    }

    private fun note(s: String) { listener?.onNote(s) }

    // ---------- USB ----------
    fun mixer(): UsbDevice? {
        val all = usb.deviceList.values
        return all.firstOrNull { it.vendorId == VID_PIONEER && it.productId == PID_900NXS2 }
            ?: all.firstOrNull { it.vendorId == VID_PIONEER }
    }

    fun hasMixerPermission(): Boolean = mixer()?.let { usb.hasPermission(it) } ?: false

    /** Logs the device layout (diagnostics), asks for USB permission if missing, and arms if appropriate. */
    fun findMixer() {
        val dev = mixer()
        if (dev == null) { listener?.onState(); return }
        log("Found %04x:%04x %s".format(dev.vendorId, dev.productId, dev.productName ?: ""))
        for (i in 0 until dev.interfaceCount) {
            val f = dev.getInterface(i)
            log("  if ${f.id} alt ${f.alternateSetting} class ${f.interfaceClass}/${f.interfaceSubclass}")
            for (e in 0 until f.endpointCount) {
                val ep = f.getEndpoint(e)
                log("    ep 0x%02x type %d maxpkt 0x%04x interval %d".format(ep.address, ep.type, ep.maxPacketSize, ep.interval))
            }
        }
        requestPermission(dev)
        usbChanged()
    }

    private fun requestPermission(dev: UsbDevice) {
        if (usb.hasPermission(dev)) return
        permAskedAt = SystemClock.elapsedRealtime()
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        try {
            usb.requestPermission(dev, PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags))
        } catch (e: Exception) { log("requestPermission failed: ${e.message}") }
    }

    private fun wantArm() = (uiVisible || settings.autoStart) && !userDisarmed

    private fun usbChanged() {
        when (state) {
            State.WAITING -> tryResume()
            State.IDLE -> if (wantArm() && !demo) tryArm()
            // detach broadcast: do not wait for the capture thread to notice
            State.ARMED, State.RECORDING -> if (!demo && mixer() == null) onEngineDied(Native.nativeStats())
        }
        listener?.onState()
    }

    private fun pktSize(w: Int) = (w and 0x7ff) * (1 + ((w shr 11) and 3))

    private fun sendLevel(c: UsbDeviceConnection, i: Int) = c.controlTransfer(0x40, 0x03, i shl 8, 0x8003, null, 0, 1000)

    /**
     * The USB start sequence (unchanged since 0.3): claim interface 0, alt 1, sample rate, capture routing, mixer level,
     * then the native engine. [resume] re-attaches to the open recording session after a replug.
     */
    private fun startEngine(dev: UsbDevice, resume: Boolean, gapFrames: Long): Boolean {
        val rate = if (resume) engRate else settings.rate
        val stems = if (resume) engStems else settings.stems
        val feedback = if (resume) engFeedback else settings.feedback
        val c = usb.openDevice(dev) ?: run { log("openDevice failed"); return false }
        val dir = getExternalFilesDir(null)
        try { File(dir, "descriptors.txt").writeText(c.rawDescriptors.joinToString(" ") { "%02x".format(it) }) } catch (_: Exception) {}

        var f: UsbInterface? = null
        for (i in 0 until dev.interfaceCount) {
            val x = dev.getInterface(i)
            if (x.id == 0 && x.alternateSetting == 1) f = x
        }
        if (f == null) { log("Interface 0 alt 1 not found"); c.close(); return false }
        var maxIn = 0; var maxOut = 0
        for (e in 0 until f.endpointCount) {
            val ep = f.getEndpoint(e)
            if (ep.address == EP_IN) maxIn = pktSize(ep.maxPacketSize)
            if (ep.address == EP_OUT) maxOut = pktSize(ep.maxPacketSize)
        }
        if (maxIn == 0) { log("Capture endpoint 0x82 not found"); c.close(); return false }

        log("claim: ${c.claimInterface(f, true)}")
        log("set alt 1: ${c.setInterface(f)}")
        val sr = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte())
        log("set rate $rate: ${c.controlTransfer(0x22, 0x01, 0x0100, 0x0082, sr, 3, 1000)}")
        log("REC OUT -> ch 9/10: ${c.controlTransfer(0x40, 0x03, 0x050a, 0x8002, null, 0, 1000)}")
        if (stems) for (p in 1..4) log("deck $p post-fader -> pair $p: ${c.controlTransfer(0x40, 0x03, (p shl 8) or 0x06, 0x8002, null, 0, 1000)}")

        log("mixer level ${settings.level + 1}: ${sendLevel(c, settings.level)}")

        val fb = feedback && maxOut > 0
        val preSec = if (resume) 0 else settings.effectivePreSec()
        val r = Native.nativeArm(c.fileDescriptor, EP_IN, maxIn, CH_IN, if (fb) EP_OUT else 0, maxOut, CH_OUT, rate, REC_PAIR,
            preSec, if (stems) 1 else 0, if (resume) 1 else 0, gapFrames)
        log("${if (resume) "resume" else "arm"}: $r (maxpkt in $maxIn out $maxOut, feedback $fb, pre-roll ${preSec}s)")
        if (r != 0) { c.releaseInterface(f); c.close(); return false }
        conn = c; intf = f
        engRate = rate; engStems = stems; engFeedback = feedback; engPid = dev.productId
        if (!resume) {
            preArmedSec = preSec
            stemsPreSec = if (stems && preSec > 0) minOf(preSec, Native.stemsPreMaxSec(rate)) else 0
        }
        return true
    }

    private fun stopEngine() {
        stopMonitor()
        midi?.detachRaw()          // releases the MIDI interface on the connection before it is closed
        Native.nativeDisarm()
        val c = conn; val f = intf
        conn = null; intf = null
        try { if (f != null) c?.releaseInterface(f) } catch (_: Exception) {}
        try { c?.close() } catch (_: Exception) {}
    }

    // ---------- arm / disarm ----------
    private fun tryArm(): Boolean {
        if (state != State.IDLE || demo) return false
        val dev = mixer() ?: return false
        if (!usb.hasPermission(dev)) {
            if (SystemClock.elapsedRealtime() - permAskedAt > 3000) requestPermission(dev)
            return false
        }
        if (!startEngine(dev, false, 0)) return false
        waveAbs = 0; wave.clear(); markers.clear(); events.clear(); trackLog.clear(); linkTokens.clear()
        resetLive()
        setState(State.ARMED)
        ensureTick()
        afterEngineStart()
        return true
    }

    private fun resetLive() {
        levelL = -90f; levelR = -90f; deckLevel.fill(-90f); maxPk.fill(-90f); deckHold.fill(false)
        aboveMs = 0; belowMs = 0; dropouts = 0; reconnects = 0; recBytes = 0; lastQLost = 0; lastCtrMismatch = 0
        autoStarted = false
    }

    private fun disarm() {
        if (state != State.ARMED) return
        stopEngine()
        wave.clear()
        setState(State.IDLE)
    }

    private fun disarmByUser() {
        userDisarmed = true
        disarm()
    }

    /** The activity became visible / hidden. Visible: arm if a mixer is ready. Hidden: disarm unless auto-start needs it. */
    fun setUiVisible(v: Boolean) {
        uiVisible = v
        if (v) { userDisarmed = false; if (state == State.IDLE && !demo) tryArm() }
        else {
            if (demo) stopDemo()
            if (state == State.ARMED && !settings.autoStart) disarm()
        }
        updateForeground()
    }

    /** Rate / stems / feedback / pre-roll changed while armed: restart the engine so the new values apply. */
    fun reconfigure() {
        userDisarmed = false
        if (state == State.ARMED) { stopEngine(); wave.clear(); setState(State.IDLE); tryArm() }
        else if (state == State.IDLE && wantArm()) tryArm()
        updateForeground()
    }

    fun setLevel(i: Int) {
        settings.level = i
        val c = conn
        if ((state == State.ARMED || state == State.RECORDING) && !demo && c != null) {
            Thread({
                val r = try { sendLevel(c, i) } catch (_: Exception) { -99 }
                ui.post { log("mixer level ${i + 1}: $r") }
            }, "level").start()
        }
    }

    // ---------- recording ----------
    fun startRecording(auto: Boolean = false) {
        when (state) {
            State.RECORDING, State.WAITING -> return
            State.IDLE -> {
                if (mixer() == null) { note("Connect your DJM via USB to record"); return }
                userDisarmed = false
                if (!tryArm()) { findMixer(); return }
            }
            State.ARMED -> {}
        }
        if (demo) return
        val dir = getExternalFilesDir(null) ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        var base = "mix-$stamp"; var n = 2
        while (File(dir, "$base.wav").exists() || File(dir, "$base-12ch.wav").exists()) base = "mix-$stamp-${n++}"
        val stereo = File(dir, "$base.wav")
        val multi = if (engStems) File(dir, "$base-12ch.wav") else null

        val req = preArmedSec * engRate
        val rf64 = if (settings.rf64Test) 50L * 1024 * 1024 else 0L
        val r = Native.nativeRecordStart(multi?.absolutePath, stereo.absolutePath, req, rf64)
        if (r[0] != 0L) {
            log("record start failed: ${r[0]}")
            lastError = "Could not start recording (error ${r[0]})"
            note(lastError!!)
            listener?.onState()
            return
        }
        // Wave alignment: the first file sample is engine frame (start - preroll); entries are 10 ms of engine time.
        drainWave()
        val win = engRate / 100
        val preFrames = r[1]
        val firstEntry = ((r[2] - preFrames) + win - 1) / win
        val keep = (waveAbs - firstEntry).coerceIn(0, wave.count.toLong()).toInt()
        wave.trimTo(keep)
        val head = wave.bytes.copyOf(keep * WaveData.STRIDE)
        val wf = try { FileOutputStream(Rec.waveFile(stereo)) } catch (e: Exception) { log("wave file: ${e.message}"); null }
        waveOut = wf
        if (wf != null) io.execute { try { wf.write(WaveData.MAGIC); if (head.isNotEmpty()) wf.write(head) } catch (_: Exception) {} }
        markers.clear(); events.clear(); trackLog.clear(); linkTokens.clear()
        dropouts = 0; reconnects = 0; recBytes = 0; lastQLost = 0; lastCtrMismatch = 0
        maxPk.fill(-90f); deckHold.fill(false)
        lastError = null; storageNotified = 0
        currentFile = stereo; stemsFile = multi
        recStartedAt = SystemClock.elapsedRealtime()
        autoStarted = auto
        log("record start ${stereo.name}${if (multi != null) " + stems" else ""}: pre-roll ${"%.1f".format(Locale.US, preFrames.toDouble() / engRate)} s" +
            (if (multi != null) " (stems ${"%.1f".format(Locale.US, r[3].toDouble() / engRate)} s)" else "") + if (auto) " [auto]" else "")
        setState(State.RECORDING)
        ensureTick()
        // the deck that is already audible when recording starts is the first tracklist line
        if (faders.current in 1..4) trackLog.addMidi(0.0, faders.current)
        link?.resetLogged()
    }

    /** Finalizes the files. [why] is shown to the user (auto-stop, disk full, ...). */
    fun stopRecording(why: String? = null) {
        when (state) {
            State.RECORDING -> {
                if (demo) { stopDemo(); return }
                drainWave(); drainEvents()
                finishSession(why)
            }
            State.WAITING -> finishSession(why)
            else -> {}
        }
    }

    private fun finishSession(why: String?) {
        val wasWaiting = state == State.WAITING
        val r = Native.nativeRecordStop()
        log("stopped: frames ${r[0]} stereo ${r[1]} B stems ${r[2]} B err ${r[3]}")
        val wo = waveOut; waveOut = null
        if (wo != null) io.execute { try { wo.close() } catch (_: Exception) {} }
        val f = currentFile
        if (f != null) {
            val snap = markers.toList(); io.execute { Rec.saveMarkers(f, snap) }
            if (trackLog.entries.isNotEmpty()) { val ts = trackLog.entries.map { it.copy() }; io.execute { TrackFile.save(f, ts) } }
            if (!demo) io.execute { Recognition.maybeAuto(this, f) }   // queued after the saves above; only starts a background job
        }
        if (r[3] != 0L) {
            lastError = "Disk write error (errno ${r[3]}) - recording was stopped and the files were finalized"
            alert(ALERT_ERROR, "Recording stopped", lastError!!)
        }
        if (why != null) note(why)
        currentFile = null; stemsFile = null
        markers.clear(); events.clear(); wave.clear(); trackLog.clear(); linkTokens.clear()
        resetLive()
        holdOff = true; holdClearMs = 0
        if (wasWaiting || !engineRunningNow()) {
            if (!wasWaiting) { /* engine died while stopping */ stopEngine() }
            state = State.IDLE
            setState(State.IDLE)
            if (wantArm()) tryArm()
        } else if (wantArm()) {
            setState(State.ARMED)
        } else {
            stopEngine()
            setState(State.IDLE)
        }
        updateStorage()
        io.execute { ui.post { if (!dead) listener?.onFilesChanged() } }
    }

    private fun engineRunningNow(): Boolean = try { Native.nativeStats()[Native.S_RUNNING] != 0L } catch (_: Exception) { false }

    fun addMarker() {
        if (state != State.RECORDING) return
        val t = if (demo) (System.currentTimeMillis() - demoStart) / 1000.0 else Native.nativeRecFrames().toDouble() / engRate
        markers.add(t)
        val f = currentFile
        if (!demo && f != null) { val snap = markers.toList(); io.execute { Rec.saveMarkers(f, snap) } }
        listener?.onWave()
        postNotif()
    }

    // ---------- demo ----------
    fun startDemo() {
        if (state != State.IDLE || demo || mixer() != null) return
        demo = true; demoStart = System.currentTimeMillis(); demoEntries = 0; demoTrackIdx = 0
        wave.clear(); markers.clear(); events.clear(); trackLog.clear(); resetLive()
        state = State.RECORDING
        setState(State.RECORDING)
        ensureTick()
    }

    private fun stopDemo() {
        demo = false
        wave.clear(); markers.clear(); events.clear(); trackLog.clear(); resetLive()
        state = State.IDLE
        setState(State.IDLE)
        if (wantArm()) tryArm()
    }

    /** Fake 128 bpm 3-band audio for demo mode (kick = low, pad = mid, off-beat hats = high). */
    private fun demoWave(): ByteArray {
        val target = ((System.currentTimeMillis() - demoStart) / 10).toInt()
        val n = (target - demoEntries).coerceIn(0, 400)
        val out = ByteArray(n * 3)
        for (k in 0 until n) {
            val t = (demoEntries + k) / 100.0
            val beat = t * 128.0 / 60.0
            val bar = (beat / 4).toInt()
            val drop = if (bar % 8 >= 6) 0.15 else 1.0
            val kick = exp(-(beat % 1.0) * 6.0)
            val hat = exp(-((beat + 0.5) % 1.0) * 10.0)
            val pad = 0.5 + 0.5 * sin(t * 1.3)
            val lo = (0.12 + 0.75 * kick) * drop
            val mi = 0.10 + 0.30 * pad * (0.6 + 0.4 * kick) + Random.nextDouble() * 0.04
            val hi = (0.05 + 0.35 * hat) * (if (bar % 8 == 7) 1.5 else 1.0) + Random.nextDouble() * 0.04
            out[k * 3] = (sqrt(lo.coerceIn(0.0, 1.0)) * 255).toInt().toByte()
            out[k * 3 + 1] = (sqrt(mi.coerceIn(0.0, 1.0)) * 255).toInt().toByte()
            out[k * 3 + 2] = (sqrt(hi.coerceIn(0.0, 1.0)) * 255).toInt().toByte()
        }
        demoEntries += n
        return out
    }

    // ---------- tick ----------
    private val tickRun = Runnable { tick() }

    private fun ensureTick() {
        if (!ticking) { ticking = true; lastTickAt = SystemClock.elapsedRealtime(); ui.postDelayed(tickRun, TICK_MS) }
    }

    private fun db(v: Long) = if (v <= 0) -90f else (20 * log10(v / 8388608.0)).toFloat()

    fun elapsedSec(): Int = when {
        demo -> ((System.currentTimeMillis() - demoStart) / 1000).toInt()
        state == State.RECORDING -> (recFramesNow / engRate).toInt()
        state == State.WAITING -> waitRecPos.toInt()
        else -> 0
    }

    private fun tick() {
        ticking = false
        if (state == State.IDLE && !demo) return
        tickN++
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTickAt).coerceIn(1, 500)
        lastTickAt = now
        when {
            demo -> tickDemo()
            state == State.WAITING -> tickWaiting(now)
            else -> tickEngine(dt)
        }
        if (state == State.IDLE && !demo) { listener?.onTick(); return }
        if (tickN % 12L == 0L) housekeeping(now)
        listener?.onTick()
        ensureTick()
    }

    private fun tickDemo() {
        val beat = (tickN % 4 == 0L)
        val l = -14f + 6f * sin(tickN / 7f) + (if (beat) 6f else 0f) + Random.nextFloat() * 3f
        val r = l - Random.nextFloat() * 2f
        levelL = l; levelR = r
        for (d in 0..3) deckLevel[d] = l - 4f * d - Random.nextFloat() * 6f
        wave.append(demoWave()); listener?.onWave()
        trackPeaks()
        val el = (System.currentTimeMillis() - demoStart) / 1000.0
        while (demoTrackIdx < DEMO_TRACKS.size && el >= DEMO_TRACKS[demoTrackIdx].sec) {
            val t = DEMO_TRACKS[demoTrackIdx++]
            trackLog.entries.add(TrackEntry(t.sec, t.deck, t.artist, t.title, t.source)); trackLog.entries.sortBy { it.sec }
            listener?.onTracks()
        }
    }

    private fun trackPeaks() {
        if (levelL > maxPk[0]) maxPk[0] = levelL
        if (levelR > maxPk[1]) maxPk[1] = levelR
        for (d in 0..3) if (deckLevel[d] > -0.3f) deckHold[d] = true
    }

    private fun tickEngine(dt: Long) {
        val s = Native.nativeStats()
        if (s[Native.S_RUNNING] == 0L) { onEngineDied(s); return }
        levelL = db(s[Native.S_PEAK0 + REC_PAIR * 2]); levelR = db(s[Native.S_PEAK0 + REC_PAIR * 2 + 1])
        for (d in 0..3) deckLevel[d] = maxOf(db(s[Native.S_PEAK0 + d * 2]), db(s[Native.S_PEAK0 + d * 2 + 1]))
        trackPeaks()
        drainWave()
        recFramesNow = s[Native.S_REC_FRAMES]
        preFilledSec = if (state == State.ARMED) s[Native.S_PRE_S].toFloat() / engRate else 0f
        if (state == State.RECORDING) {
            recBytes = s[Native.S_STEREO_BYTES] + s[Native.S_STEMS_BYTES]
            drainEvents()
            if (s[Native.S_REC_ERR] != 0L) {
                log("write error errno ${s[Native.S_REC_ERR]}")
                stopRecording("Disk full or write error - recording stopped, files are valid")
                return
            }
            if (s[Native.S_Q_LOST] != lastQLost) {
                log("disk too slow: ${s[Native.S_Q_LOST]} frames replaced by silence so far")
                lastQLost = s[Native.S_Q_LOST]
            }
            autoStopCheck(dt)
            if (state != State.RECORDING) return
        } else {
            autoStartCheck(dt)
        }
        // diagnostics: sample counter of the fake mixer (the real mixer has none, so this stays silent)
        if (s[Native.S_CTR_MISMATCH] != lastCtrMismatch) {
            lastCtrMismatch = s[Native.S_CTR_MISMATCH]
            val a = Native.nativeCounterLog()
            var i = 0
            while (i + 2 < a.size) {
                log("counter check: jump at stream frame ${a[i]}: expected ${a[i + 1]} got ${a[i + 2]} (missing ${a[i + 2] - a[i + 1]})")
                i += 3
            }
        }
        if (s[Native.S_CTR_VALID] != 0L && tickN % 125L == 0L)
            log("counter check: counter missing ${s[Native.S_CTR_MISSING]} frames, silence inserted ${s[Native.S_GAP_FRAMES]} frames, mismatches ${s[Native.S_CTR_MISMATCH]}")
        if (tickN % 25L == 0L)
            log("t=${s[Native.S_FRAMES] / engRate}s frames ${s[Native.S_FRAMES]} urbs ${s[Native.S_URBS]} pktErr ${s[Native.S_PKT_ERR]} odd ${s[Native.S_ODD]} errno ${s[Native.S_ERRNO]}" +
                " gaps ${s[Native.S_GAP_EVENTS]}/${s[Native.S_GAP_FRAMES]}f sf ${s[Native.S_SF_JUMPS]}/${s[Native.S_SF_REJECTED]}${if (s[Native.S_SF_LEARNED] != 0L) "" else " (sf not learned)"}" +
                " pre ${s[Native.S_PRE_S]}")
    }

    private fun tickWaiting(now: Long) {
        // elapsed time stands still while the mixer is gone; poll for the device every second
        if (now - lastResumeTry >= 1000) { lastResumeTry = now; tryResume() }
    }

    private fun autoStartCheck(dt: Long) {
        val lv = max(levelL, levelR)
        if (holdOff) {                       // after a stop, wait until the signal went quiet once
            if (lv < -50f) holdClearMs += dt else holdClearMs = 0
            if (holdClearMs >= 1000) holdOff = false
            return
        }
        if (!settings.autoStart || state != State.ARMED) { aboveMs = 0; return }
        if (lv > -50f) aboveMs += dt else aboveMs = 0
        if (aboveMs >= 2000) {
            aboveMs = 0
            log("auto-start: level above -50 dBFS for 2 s")
            startRecording(auto = true)
        }
    }

    private fun autoStopCheck(dt: Long) {
        val min = settings.autoStopMin
        if (min <= 0) { belowMs = 0; return }
        if (max(levelL, levelR) < -60f) belowMs += dt else belowMs = 0
        if (belowMs >= min * 60_000L) {
            belowMs = 0
            val msg = "Auto-stopped: $min min below -60 dBFS"
            log(msg)
            alert(ALERT_AUTO, "Recording stopped", msg)
            stopRecording(msg)
        }
    }

    // ---------- wave / events ----------
    private fun drainWave() {
        val wb = Native.nativeWave()
        if (wb.isEmpty()) return
        waveAbs += wb.size / WaveData.STRIDE
        wave.append(wb)
        if (state == State.RECORDING) {
            val o = waveOut
            if (o != null) io.execute { try { o.write(wb) } catch (_: Exception) {} }
        } else wave.trimTo(preArmedSec * 100 + 300)
        listener?.onWave()
    }

    private fun drainEvents() {
        val a = Native.nativeEvents()
        var i = 0
        while (i + 1 < a.size) {
            addEvent(DropEvent(a[i].toDouble() / engRate, a[i + 1] * 1000L / engRate))
            i += 2
        }
    }

    private fun addEvent(e: DropEvent) {
        events.add(e)
        if (e.reconnect) reconnects++ else dropouts++
        val f = currentFile
        if (f != null) io.execute { Rec.appendEvent(f, e) }
        log("EVENT ${if (e.reconnect) "reconnect" else "dropout"} at ${Rec.clockT(e.sec)} (${e.ms} ms)")
        if (!e.reconnect) note("Dropout at ${Rec.clockT(e.sec)} (${e.ms} ms)")
        listener?.onWave()
        postNotif()
    }

    // ---------- disconnect / reconnect ----------
    private fun onEngineDied(s: LongArray) {
        drainWave()
        if (state == State.RECORDING) drainEvents()
        val gone = s[Native.S_DEV_GONE] != 0L || mixer() == null
        log("Stream ended (${if (gone) "mixer unplugged" else "USB error"}), errno ${s[Native.S_ERRNO]}")
        recFramesNow = s[Native.S_REC_FRAMES]
        stopEngine()
        if (state == State.RECORDING) {
            waitRecPos = Native.nativeRecFrames().toDouble() / engRate
            waitSince = SystemClock.elapsedRealtime()
            lastResumeTry = waitSince; resumeFails = 0
            log("session paused at ${Rec.clockT(waitRecPos)} - waiting for the mixer")
            alert(ALERT_RECONNECT, "Mixer disconnected", "Mixer disconnected - plug back in to continue")
            setState(State.WAITING)
            if (!gone) ui.postDelayed({ if (state == State.WAITING) tryResume() }, 1500)
        } else {
            setState(State.IDLE)
            if (wantArm()) ui.postDelayed({ if (state == State.IDLE && wantArm()) tryArm() }, 1500)
        }
    }

    private fun tryResume() {
        if (state != State.WAITING) return
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == VID_PIONEER && it.productId == engPid } ?: return
        if (!usb.hasPermission(dev)) {
            val now = SystemClock.elapsedRealtime()
            if (now - permAskedAt > 8000) {
                log("mixer is back - asking for USB permission")
                alert(ALERT_RECONNECT, "Mixer reconnected", "Tap to allow USB access and continue recording")
                requestPermission(dev)
            }
            return
        }
        val waitedMs = SystemClock.elapsedRealtime() - waitSince
        val gapFrames = if (settings.keepGaps) waitedMs * engRate / 1000 else 0L
        if (!startEngine(dev, true, gapFrames)) {
            resumeFails++
            log("resume failed ($resumeFails), will retry")
            return
        }
        waveAbs = 0
        if (settings.keepGaps) {
            val entries = (gapFrames / (engRate / 100)).toInt()
            wave.appendZeros(entries)
            val o = waveOut
            if (o != null && entries > 0) io.execute { try { o.write(ByteArray(entries * WaveData.STRIDE)) } catch (_: Exception) {} }
        }
        addEvent(DropEvent(waitRecPos, waitedMs, reconnect = true))
        log("resumed after ${waitedMs / 1000.0} s (${if (settings.keepGaps) "silence inserted" else "no silence inserted"})")
        nm.cancel(ALERT_RECONNECT)
        setState(State.RECORDING)
        ensureTick()
        afterEngineStart()
    }

    // ---------- warnings ----------
    private fun housekeeping(now: Long) {
        updateStorage()
        if (tickN % 120L == 0L) updateBattery()
        if (state == State.RECORDING || state == State.WAITING) {
            if (!demo) {
                if (storageLevel >= 2 && storageNotified < 2) {
                    storageNotified = 2
                    alert(ALERT_STORAGE, "Storage almost full", "Less than 5 minutes of recording space left")
                }
                if (storageLevel >= 3 && state == State.RECORDING) {
                    log("storage < 30 s left: stopping cleanly")
                    alert(ALERT_STORAGE, "Recording stopped", "Phone storage is full - recording was finalized")
                    stopRecording("Storage full - recording stopped and saved")
                    return
                }
                if (now - lastWakeRefresh > 3_600_000L) { lastWakeRefresh = now; acquireWake() }
            }
            postNotif()
        }
    }

    fun updateStorage() {
        val dir = getExternalFilesDir(null) ?: return
        freeBytes = try { dir.usableSpace } catch (_: Exception) { 0L }
        val rate = if (state == State.IDLE) settings.rate else engRate
        val stems = if (state == State.IDLE) settings.stems else engStems
        val bps = rate.toLong() * 6 + (if (stems) rate.toLong() * 36 else 0L)
        leftSec = if (bps > 0) freeBytes / bps else 0L
        storageLevel = when { leftSec < 30 -> 3; leftSec < 300 -> 2; leftSec < 1800 -> 1; else -> 0 }
    }

    private fun updateBattery() {
        val bi = try { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null } ?: return
        val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        if (lvl < 0 || scale <= 0) return
        batteryPct = lvl * 100 / scale
        batteryLow = batteryPct < 15 && plugged == 0
        if (batteryLow && !batteryNotified && state != State.IDLE) {
            batteryNotified = true
            alert(ALERT_BATTERY, "Battery low", "Battery at $batteryPct% and not charging - plug in a charger")
        }
        if (!batteryLow) batteryNotified = false
    }

    /** Active warnings for the banner: (text, color). */
    fun warnings(): List<Pair<String, Int>> {
        val w = ArrayList<Pair<String, Int>>()
        if (state == State.WAITING) w.add("Mixer disconnected - plug back in to continue" to C.AMBER)
        if (batteryLow) w.add("Battery low ($batteryPct%) and not charging" to C.RED)
        if (state != State.IDLE && storageLevel == 1) w.add("Storage: about ${leftSec / 60} min left" to C.AMBER)
        if (state != State.IDLE && storageLevel >= 2) w.add("Storage almost full: ${if (leftSec < 60) "${leftSec}s" else "${leftSec / 60} min"} left" to C.RED)
        lastError?.let { w.add(it to C.RED) }
        return w
    }

    private fun alert(id: Int, title: String, text: String) {
        val b = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_rec).setContentTitle(title).setContentText(text)
            .setContentIntent(openAppIntent()).setAutoCancel(true)
        try { nm.notify(id, b.build()) } catch (_: Exception) {}
    }

    // ---------- foreground / notification ----------
    private fun setState(s: State) {
        state = s
        updateForeground()
        postNotif()
        syncTrackers()
        listener?.onState()
    }

    // ---------- tracklist sources (MIDI faders, Pro DJ Link), live monitoring ----------
    private class DemoTrack(val sec: Double, val deck: Int, val artist: String, val title: String, val source: String)

    /** Called after an engine (re)start succeeded: the connection exists, so the raw-USB MIDI fallback and monitoring can attach. */
    private fun afterEngineStart() {
        midiFallbackTried = false
        ui.postDelayed({ midiFallbackCheck() }, 3500)
        syncMonitor()
    }

    /** Starts or stops the MIDI and Pro DJ Link listeners to match the state and settings. Safe to call any time. */
    fun syncTrackers() {
        if (dead) return
        val active = !demo && (state == State.ARMED || state == State.RECORDING || state == State.WAITING)
        if (active && settings.midiTrack) startMidi() else stopMidi()
        if (active && settings.cdjLink) startLink() else stopLink()
        syncMonitor()
    }

    private fun startMidi() {
        if (midi != null) return
        val m = MidiInput(this, ui, { s -> ui.post { if (!dead) log(s) } }, { num, v -> ui.post { if (!dead) faders.cc(num, v) } })
        midi = m
        m.startManager()
    }

    private fun stopMidi() {
        val m = midi ?: return
        midi = null
        m.stop()
        faders.stop()
    }

    /** MidiManager did not give us the mixer's MIDI port: claim the MIDI interface ourselves on the engine connection. */
    private fun midiFallbackCheck() {
        val m = midi ?: return
        if (midiFallbackTried || dead || m.managerOpen || m.rawActive) return
        val c = conn ?: return
        val dev = mixer() ?: return
        midiFallbackTried = true
        log("midi: MidiManager route not available - trying raw USB MIDI")
        m.attachRaw(c, dev)
    }

    /** One line for Settings / the record screen: where MIDI stands. */
    fun midiText(): String {
        val m = midi ?: return if (settings.midiTrack) "MIDI: off while idle" else "MIDI: disabled"
        val now = SystemClock.elapsedRealtime()
        return when {
            m.lastMsgAt > 0 -> "MIDI faders OK (${if (m.route == "usb") "raw USB" else "Android MIDI"})"
            m.route.isNotEmpty() && now - m.routeAt > 10_000 -> "MIDI connected, no data: press MIDI ON on the mixer"
            m.route.isNotEmpty() -> "MIDI connected (${if (m.route == "usb") "raw USB" else "Android MIDI"})"
            now - m.startedAt < 4000 -> "MIDI: looking for the mixer"
            else -> "MIDI: not found"
        }
    }

    /** Short hint for the record screen, or null when everything is fine. */
    fun midiHint(): String? {
        val m = midi ?: return null
        val now = SystemClock.elapsedRealtime()
        return if (m.route.isNotEmpty() && m.lastMsgAt == 0L && now - m.routeAt > 10_000) "Press MIDI ON on the mixer" else null
    }

    private fun recSecAt(elapsedMs: Long): Double {
        val now = Native.nativeRecFrames().toDouble() / engRate
        return maxOf(0.0, now - (SystemClock.elapsedRealtime() - elapsedMs) / 1000.0)
    }

    private fun onMix(din: Int, dout: Int, at: Long) {
        if (state != State.RECORDING || demo) return
        val sec = recSecAt(at)
        log("mix: deck $din in${if (dout > 0) ", deck $dout out" else ""} at ${Rec.clockT(sec)}")
        trackLog.addMidi(sec, din)
    }

    private fun startLink() {
        if (link != null) return
        val l = ProLink(this, settings.linkMac, { s -> ui.post { if (!dead) log(s) } }, object : ProLink.Callback {
            override fun onStatus(text: String) { ui.post { if (!dead) { linkStatus = text; listener?.onTick() } } }
            override fun onEntry(token: Long, deck: Int, startElapsedMs: Long, artist: String, title: String) {
                ui.post {
                    if (!dead && state == State.RECORDING && !demo) {
                        val e = trackLog.addLink(recSecAt(startElapsedMs), deck, artist, title)
                        linkTokens[token] = e
                        log("link: deck $deck '${e.label()}' at ${Rec.clockT(e.sec)}")
                    }
                }
            }
            override fun onMeta(token: Long, artist: String, title: String) {
                ui.post { if (!dead) linkTokens[token]?.let { trackLog.updateMeta(it, artist, title) } }
            }
        }, { d -> faders.isAudible(d) })
        link = l
        linkStatus = "Link: starting"
        l.start()
    }

    private fun stopLink() {
        val l = link ?: return
        link = null
        l.stop()
        linkStatus = ""
    }

    private fun onTracksChanged() {
        val f = currentFile
        if (f != null && !demo) { val snap = trackLog.entries.map { it.copy() }; io.execute { TrackFile.save(f, snap) } }
        listener?.onTracks()
    }

    // ---------- monitoring ----------
    fun setMonitor(on: Boolean) { settings.monitor = on; syncMonitor() }

    private fun syncMonitor() {
        val ok = settings.monitor && !demo && conn != null && (state == State.ARMED || state == State.RECORDING)
        if (ok) {
            if (monitor == null) {
                val m = Monitor(engRate) { s -> ui.post { if (!dead) log(s) } }
                if (m.start()) monitor = m
            }
        } else stopMonitor()
    }

    private fun stopMonitor() {
        val m = monitor ?: return
        monitor = null
        m.stop()
    }

    private fun wantForeground() = !demo && (state == State.RECORDING || state == State.WAITING ||
        (state == State.ARMED && settings.autoStart))

    fun updateForeground() {
        if (dead) return
        if (wantForeground()) ensureForeground() else if (fgs) dropForeground()
    }

    private fun ensureForeground() {
        val n = buildNotif()
        if (!fgs) {
            try { startForegroundService(Intent(this, RecordService::class.java)) } catch (e: Exception) { log("startForegroundService: ${e.message}") }
            try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                else startForeground(NOTIF_ID, n)
                fgs = true
            } catch (e: Exception) { log("startForeground failed: ${e.message}") }
            acquireWake()
        } else try { nm.notify(NOTIF_ID, n) } catch (_: Exception) {}
    }

    private fun dropForeground() {
        fgs = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        releaseWake()
        stopSelf()
    }

    private fun acquireWake() {
        try {
            val w = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "djmrecorder:rec").also { it.setReferenceCounted(false); wakeLock = it }
            w.acquire(12L * 3_600_000L)
        } catch (e: Exception) { log("wake lock: ${e.message}") }
    }

    private fun releaseWake() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
    }

    private fun postNotif() {
        if (fgs) try { nm.notify(NOTIF_ID, buildNotif()) } catch (_: Exception) {}
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun actionIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, RecordService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun act(title: String, pi: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_rec), title, pi).build()

    private fun buildNotif(): Notification {
        val b = Notification.Builder(this, CH_REC)
            .setSmallIcon(R.drawable.ic_stat_rec).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(openAppIntent())
        when (state) {
            State.RECORDING -> {
                val sz = Rec.size(recBytes)
                val dr = if (dropouts == 0) "0 dropouts" else "$dropouts dropout${if (dropouts == 1) "" else "s"}"
                b.setContentTitle("Recording  " + Rec.clock(elapsedSec()))
                    .setContentText("$sz · $dr" + (if (reconnects > 0) " · $reconnects reconnect${if (reconnects == 1) "" else "s"}" else ""))
                    .setColor(C.RED)
                    .addAction(act("Marker", actionIntent(ACTION_MARKER, 1)))
                    .addAction(act("Stop", actionIntent(ACTION_STOP, 2)))
            }
            State.WAITING -> {
                b.setContentTitle("Paused - waiting for mixer  " + Rec.clock(elapsedSec()))
                    .setContentText("Mixer disconnected - plug back in to continue")
                    .setColor(C.AMBER)
                    .addAction(act("Stop", actionIntent(ACTION_STOP, 2)))
            }
            else -> {
                b.setContentTitle("Armed - waiting for audio")
                    .setContentText("Auto-start on" + (if (preArmedSec > 0) " · pre-roll $preArmedSec s" else ""))
                    .addAction(act("Disarm", actionIntent(ACTION_DISARM, 3)))
            }
        }
        return b.build()
    }
}
