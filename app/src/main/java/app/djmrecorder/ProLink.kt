package app.djmrecorder

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Minimal Pro DJ Link client (docs/prodjlink.md): joins the booth network as a virtual CDJ, reads CDJ status
 * (UDP 50002) and the mixer's channels-on-air flags (UDP 50001), and asks the CDJ's dbserver (TCP 12523) for the
 * title and artist of the track that is audibly playing. Everything runs on its own threads; callbacks arrive on
 * those threads, so the owner must hop to its main thread. Nothing here throws to the caller: network problems
 * are logged and retried.
 *
 * [midiAudible] returns whether the faders say a deck (1-4) is audible, or null if no MIDI data is known.
 */
class ProLink(
    private val ctx: Context,
    private val macText: String,
    private val log: (String) -> Unit,
    private val cb: Callback,
    private val midiAudible: (Int) -> Boolean?
) {
    interface Callback {
        fun onStatus(text: String)
        /** A deck has been audibly playing the same track for HOLD_MS. [startElapsedMs] is when that began (SystemClock.elapsedRealtime). */
        fun onEntry(token: Long, deck: Int, startElapsedMs: Long, artist: String, title: String)
        /** Title/artist arrived after [onEntry] was sent with empty ones. */
        fun onMeta(token: Long, artist: String, title: String)
    }

    private class Dev(val number: Int, var name: String, var ip: InetAddress, var type: Int, var seen: Long) { var st: St? = null }
    private class St(val dr: Int, val sr: Int, val tr: Int, val id: Long, val playing: Boolean, val at: Long)
    private class Deck { var liveKey: String? = null; var liveSince = 0L; var offSince = 0L; var logged: String? = null; var key: String? = null }

    private val lock = Any()
    @Volatile private var running = false
    private val devices = HashMap<Int, Dev>()
    private val decks = HashMap<Int, Deck>()
    private val air = BooleanArray(6)
    private var airAt = 0L
    private var airSeen = false
    private var myNumber = 7
    private var myAddr: InetAddress? = null
    private var myIp = ByteArray(4)
    private var bcast: InetAddress? = null
    private var net: Network? = null
    private var s0: DatagramSocket? = null
    private var s1: DatagramSocket? = null
    private var s2: DatagramSocket? = null
    @Volatile private var netDown = false
    private var up = false
    private var mainThread: Thread? = null
    private var mcLock: WifiManager.MulticastLock? = null
    private var lastStatusText = ""
    private var statusSeen = 0L
    private val loggedFirst = HashSet<Int>()
    private val cache = ConcurrentHashMap<String, Array<String>>()
    private val inflight = HashSet<String>()
    private val attempts = HashMap<String, Int>()
    private val waiting = HashMap<String, MutableList<Long>>()
    private var nextToken = 1L
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "link-meta").also { it.isDaemon = true } }

    private val mac: ByteArray = try {
        macText.split(':').map { it.toInt(16).toByte() }.toByteArray().also { require(it.size == 6) }
    } catch (_: Exception) { byteArrayOf(2, 0x44, 0x4a, 0x4d, 0x52, 1) }

    fun start() {
        if (running) return
        running = true
        mainThread = Thread({ main() }, "link-main").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { mainThread?.interrupt() } catch (_: Exception) {}
        closeSockets()
        try { exec.shutdownNow() } catch (_: Exception) {}
        releaseLocks()
    }

    /** Forget which tracks were already logged, so decks that are live right now are logged again (a recording just started). */
    fun resetLogged() { synchronized(lock) { for (d in decks.values) d.logged = null } }

    // ------------------------------------------------------------------ main loop
    private fun sleepMs(ms: Long): Boolean {
        return try { Thread.sleep(ms); running } catch (_: InterruptedException) { false }
    }

    private fun main() {
        while (running) {
            try {
                val n = findNetwork()
                if (n == null) { status("Link: no network"); if (!sleepMs(3000)) break; continue }
                if (!bringUp(n)) { status("Link: no network"); if (!sleepMs(3000)) break; continue }
                runUp()
            } catch (e: Exception) {
                log("link: ${e.javaClass.simpleName}: ${e.message}")
            }
            up = false
            closeSockets()
            releaseLocks()
            if (running) { status("Link: reconnecting"); if (!sleepMs(3000)) break }
        }
        closeSockets()
    }

    @Suppress("DEPRECATION")
    private fun findNetwork(): Network? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        var wifi: Network? = null
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return n
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && wifi == null) wifi = n
        }
        return wifi
    }

    private fun udp(port: Int, n: Network): DatagramSocket {
        val s = DatagramSocket(null)
        try {
            s.reuseAddress = true
            s.broadcast = true
            n.bindSocket(s)
            s.bind(InetSocketAddress(port))
            s.soTimeout = 1000
        } catch (e: Exception) { try { s.close() } catch (_: Exception) {}; throw e }
        return s
    }

    private fun bringUp(n: Network): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(n) ?: return false
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: run {
            log("link: network has no IPv4 address yet"); return false
        }
        val ip = la.address.address
        val prefix = la.prefixLength.coerceIn(1, 32)
        val maskInt = if (prefix == 32) -1 else (-1 shl (32 - prefix))
        var ipInt = 0
        for (b in ip) ipInt = (ipInt shl 8) or (b.toInt() and 0xff)
        val bInt = ipInt or maskInt.inv()
        val bb = byteArrayOf((bInt ushr 24).toByte(), (bInt ushr 16).toByte(), (bInt ushr 8).toByte(), bInt.toByte())
        net = n; myAddr = la.address; myIp = ip; bcast = InetAddress.getByAddress(bb)
        val caps = cm.getNetworkCapabilities(n)
        val kind = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) "ethernet" else "wifi"
        log("link: using $kind network ${la.address.hostAddress}/$prefix, broadcast ${bcast?.hostAddress}")
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            mcLock = wm?.createMulticastLock("djmrecorder:link")?.also { it.setReferenceCounted(false); it.acquire() }
        } catch (e: Exception) { log("link: multicast lock: ${e.message}") }
        netDown = false
        try {
            s0 = udp(50000, n); s1 = udp(50001, n); s2 = udp(50002, n)
        } catch (e: Exception) {
            log("link: cannot bind UDP ports 50000-50002: ${e.message}")
            closeSockets(); releaseLocks()
            return false
        }
        synchronized(lock) { devices.clear(); decks.clear(); loggedFirst.clear(); airSeen = false; statusSeen = 0 }
        up = true
        startReceiver(s0!!, "link-50000") { p, l, src -> onKeepAlive(p, l, src) }
        startReceiver(s1!!, "link-50001") { p, l, _ -> onAir(p, l) }
        startReceiver(s2!!, "link-50002") { p, l, src -> onStatus(p, l, src) }
        return true
    }

    private fun startReceiver(sock: DatagramSocket, name: String, h: (ByteArray, Int, InetAddress) -> Unit) {
        Thread({
            val buf = ByteArray(1024)
            val pkt = DatagramPacket(buf, buf.size)
            while (running && !netDown) {
                try {
                    pkt.length = buf.size
                    sock.receive(pkt)
                    val len = pkt.length
                    if (len > 0) {
                        val src = pkt.address
                        try { h(buf, len, src) } catch (e: Exception) { log("link: bad packet on $name: ${e.javaClass.simpleName}") }
                    }
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    if (running && !netDown) { log("link: $name stopped: ${e.message}"); netDown = true }
                    break
                }
            }
        }, name).also { it.isDaemon = true; it.start() }
    }

    private fun closeSockets() {
        for (s in listOf(s0, s1, s2)) try { s?.close() } catch (_: Exception) {}
        s0 = null; s1 = null; s2 = null
    }

    private fun releaseLocks() { try { mcLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}; mcLock = null }

    private fun runUp() {
        status("Link: searching…")
        // listen 4 s first so we know which player numbers are taken
        var t = 0
        while (t < 16 && running && !netDown) { if (!sleepMs(250)) return; t++ }
        if (!running || netDown) return
        chooseNumber()
        log("link: announcing as virtual CDJ '$NAME' number $myNumber")
        var lastKa = 0L; var lastTick = 0L; var lastStat = 0L
        val begin = SystemClock.elapsedRealtime()
        var claimed = false
        while (running && !netDown) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastKa >= 1500) { lastKa = now; sendKeepAlive() }
            if (now - lastTick >= 500) { lastTick = now; tick() }
            if (now - lastStat >= 1000) { lastStat = now; updateStatus() }
            if (!claimed && now - begin > 10_000 && statusSeen == 0L && synchronized(lock) { devices.values.any { it.number in 1..6 } }) {
                claimed = true
                log("link: no CDJ status after 10 s - trying the full device-number claim")
                claimSequence()
            }
            if (!sleepMs(100)) return
        }
    }

    private fun chooseNumber() {
        synchronized(lock) {
            val used = devices.keys.toSet()
            val cdjs = devices.values.count { it.number in 1..6 }
            var pick = -1
            if (cdjs <= 3) for (n in 1..4) if (n !in used) { pick = n; break }
            if (pick < 0) { pick = 7; while (pick in used && pick < 15) pick++ }
            myNumber = pick
        }
    }

    private fun status(t: String) {
        if (t == lastStatusText) return
        lastStatusText = t
        try { cb.onStatus(t) } catch (_: Exception) {}
    }

    private fun updateStatus() {
        val now = SystemClock.elapsedRealtime()
        val n = synchronized(lock) { devices.values.count { it.number in 1..6 && now - it.seen < 10_000 } }
        status(if (n == 0) "Link: searching…" else "Link: $n CDJ${if (n == 1) "" else "s"}")
    }

    // ------------------------------------------------------------------ sending
    private fun header(b: ByteArray, type: Int) {
        System.arraycopy(MAGIC, 0, b, 0, 10)
        b[10] = type.toByte()
        b[11] = 0
    }

    private fun putName(b: ByteArray, off: Int) {
        val n = NAME.toByteArray(Charsets.US_ASCII)
        System.arraycopy(n, 0, b, off, minOf(n.size, 20))
    }

    private fun send(data: ByteArray, to: InetAddress, port: Int = 50000) {
        val s = s2 ?: return
        try { s.send(DatagramPacket(data, data.size, to, port)) } catch (e: Exception) {
            if (running && !netDown) { log("link: send failed: ${e.message}"); netDown = true }
        }
    }

    private fun sendKeepAlive() {
        val b = ByteArray(54)
        header(b, 0x06); putName(b, 0x0c)
        b[0x20] = 1; b[0x21] = 2; b[0x22] = 0; b[0x23] = 0x36
        b[0x24] = myNumber.toByte(); b[0x25] = 1
        System.arraycopy(mac, 0, b, 0x26, 6)
        System.arraycopy(myIp, 0, b, 0x2c, 4)
        b[0x30] = 2; b[0x34] = 1; b[0x35] = 0x64
        val bc = bcast ?: return
        send(b, bc)
        // unicast copies help on networks that filter broadcast
        val ips = synchronized(lock) { devices.values.map { it.ip } }
        for (ip in ips) send(b, ip)
    }

    /** hello x3, stage 1 x3, stage 2 x3, stage 3 x3, 300 ms apart (beat-link's sequence). Only used if no status arrives. */
    private fun claimSequence() {
        val bc = bcast ?: return
        try {
            for (i in 1..3) { val b = ByteArray(38); header(b, 0x0a); putName(b, 0x0c); b[0x20] = 1; b[0x21] = 4; b[0x23] = 0x26; b[0x24] = 1; b[0x25] = 0x40; send(b, bc); if (!sleepMs(300)) return }
            for (i in 1..3) { val b = ByteArray(44); header(b, 0x00); putName(b, 0x0c); b[0x20] = 1; b[0x21] = 3; b[0x23] = 0x2c; b[0x24] = i.toByte(); b[0x25] = 1
                System.arraycopy(mac, 0, b, 0x26, 6); send(b, bc); if (!sleepMs(300)) return }
            for (i in 1..3) { val b = ByteArray(50); header(b, 0x02); putName(b, 0x0c); b[0x20] = 1; b[0x21] = 3; b[0x23] = 0x32
                System.arraycopy(myIp, 0, b, 0x24, 4); System.arraycopy(mac, 0, b, 0x28, 6)
                b[0x2e] = myNumber.toByte(); b[0x2f] = i.toByte(); b[0x30] = 1; b[0x31] = 2; send(b, bc); if (!sleepMs(300)) return }
            for (i in 1..3) { val b = ByteArray(38); header(b, 0x04); putName(b, 0x0c); b[0x20] = 1; b[0x21] = 3; b[0x23] = 0x26
                b[0x24] = myNumber.toByte(); b[0x25] = i.toByte(); send(b, bc); if (!sleepMs(300)) return }
        } catch (e: Exception) { log("link: claim sequence: ${e.message}") }
    }

    // ------------------------------------------------------------------ receiving (bounds checked everywhere)
    private fun hasMagic(p: ByteArray, len: Int): Boolean {
        if (len < 11) return false
        for (i in 0 until 10) if (p[i] != MAGIC[i]) return false
        return true
    }

    private fun u8(p: ByteArray, o: Int) = p[o].toInt() and 0xff
    private fun u32(p: ByteArray, o: Int): Long =
        (u8(p, o).toLong() shl 24) or (u8(p, o + 1).toLong() shl 16) or (u8(p, o + 2).toLong() shl 8) or u8(p, o + 3).toLong()

    private fun asciiName(p: ByteArray, off: Int, len: Int): String {
        val sb = StringBuilder()
        for (i in off until off + len) { val c = p[i].toInt() and 0xff; if (c == 0) break; sb.append(if (c in 32..126) c.toChar() else '?') }
        return sb.toString().trim()
    }

    private fun isMe(src: InetAddress?) = src != null && myAddr != null && src == myAddr

    private fun onKeepAlive(p: ByteArray, len: Int, src: InetAddress) {
        if (len < 54 || !hasMagic(p, len) || p[10].toInt() != 0x06) return
        if (isMe(src)) return
        val name = asciiName(p, 0x0c, 20)
        if (name == NAME) return
        val number = u8(p, 0x24)
        if (number == 0) return
        var ip: InetAddress = src
        try { val a = InetAddress.getByAddress(byteArrayOf(p[0x2c], p[0x2d], p[0x2e], p[0x2f])); if (!a.isAnyLocalAddress) ip = a } catch (_: Exception) {}
        val type = u8(p, 0x34)
        val now = SystemClock.elapsedRealtime()
        var isNew = false
        synchronized(lock) {
            val d = devices[number]
            if (d == null) { devices[number] = Dev(number, name, ip, type, now); isNew = true }
            else { d.name = name; d.ip = ip; d.type = type; d.seen = now }
        }
        if (isNew) log("link: device $number '$name' at ${ip.hostAddress} (type $type)")
    }

    private fun onAir(p: ByteArray, len: Int) {
        if (len < 0x2d || !hasMagic(p, len) || p[10].toInt() != 0x03) return
        synchronized(lock) {
            for (i in 0..3) air[i] = u8(p, 0x24 + i) == 1
            if (len >= 0x35) { air[4] = u8(p, 0x2d) == 1; air[5] = u8(p, 0x2e) == 1 }
            airAt = SystemClock.elapsedRealtime(); airSeen = true
        }
    }

    private fun onStatus(p: ByteArray, len: Int, src: InetAddress) {
        if (len < 0xcc || !hasMagic(p, len) || p[10].toInt() != 0x0a) return
        if (isMe(src)) return
        val d = u8(p, 0x21)
        if (d !in 1..6 || d == myNumber) return
        val st = St(u8(p, 0x28), u8(p, 0x29), u8(p, 0x2a), u32(p, 0x2c), (u8(p, 0x89) and 0x40) != 0, SystemClock.elapsedRealtime())
        val key = "${st.dr}:${st.sr}:${st.tr}:${st.id}"
        var first = false; var fetch = false
        synchronized(lock) {
            statusSeen = st.at
            val dev = devices.getOrPut(d) { Dev(d, asciiName(p, 0x0b, 20), src, 1, st.at) }
            dev.st = st; dev.seen = st.at
            first = loggedFirst.add(d)
            val dk = decks.getOrPut(d) { Deck() }
            if (dk.key != key) { dk.key = key; fetch = st.sr != 0 && st.dr != 0 && st.id != 0L }
        }
        if (first) {
            val sb = StringBuilder()
            for (i in 0 until minOf(len, 0xcc)) sb.append("%02x".format(p[i]))
            log("link: first status from player $d, length $len: $sb")
        }
        if (fetch) ensureMeta(key, st.dr, st.sr, st.tr, st.id)
    }

    // ------------------------------------------------------------------ track-live state machine
    private fun onAirFor(n: Int, now: Long): Boolean {
        if (airSeen && now - airAt < 30_000 && n in 1..6) return air[n - 1]
        midiAudible(n)?.let { return it }
        return true
    }

    private class Emit(val deck: Int, val startMs: Long, val key: String)

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val emits = ArrayList<Emit>()
        synchronized(lock) {
            for ((n, dv) in devices) {
                val st = dv.st ?: continue
                val dk = decks.getOrPut(n) { Deck() }
                val key = "${st.dr}:${st.sr}:${st.tr}:${st.id}"
                val valid = st.id != 0L && st.sr != 0 && st.dr != 0
                val live = now - st.at < 3000 && st.playing && valid && onAirFor(n, now)
                if (live) {
                    dk.offSince = 0
                    if (dk.liveKey != key) { dk.liveKey = key; dk.liveSince = now }
                    if (now - dk.liveSince >= HOLD_MS && dk.logged != key) { dk.logged = key; emits.add(Emit(n, dk.liveSince, key)) }
                } else {
                    if (dk.offSince == 0L) dk.offSince = now
                    if (now - dk.offSince >= 3000) { dk.liveKey = null; dk.liveSince = 0 }   // short interruptions keep the window
                    if (now - dk.offSince > 30_000) dk.logged = null
                }
            }
        }
        for (e in emits) {
            val meta = cache[e.key]
            val token: Long
            synchronized(lock) {
                token = nextToken++
                if (meta == null) waiting.getOrPut(e.key) { ArrayList() }.add(token)
            }
            try { cb.onEntry(token, e.deck, e.startMs, meta?.get(0) ?: "", meta?.get(1) ?: "") } catch (_: Exception) {}
            if (meta == null) {
                val parts = e.key.split(':')
                if (parts.size == 4) ensureMeta(e.key, parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toLong())
            }
        }
    }

    // ------------------------------------------------------------------ metadata (dbserver)
    private fun ensureMeta(key: String, dr: Int, sr: Int, tr: Int, id: Long) {
        synchronized(lock) { if (cache.containsKey(key) || !inflight.add(key)) return }
        try { exec.execute { runQuery(key, dr, sr, tr, id) } }
        catch (_: RejectedExecutionException) { synchronized(lock) { inflight.remove(key) } }
    }

    private fun runQuery(key: String, dr: Int, sr: Int, tr: Int, id: Long) {
        if (!running) return
        var res: Array<String>? = null
        try { res = query(dr, sr, tr, id) } catch (e: Exception) { log("link: metadata query for $key failed: ${e.javaClass.simpleName}: ${e.message}") }
        if (res != null) {
            cache[key] = res
            log("link: track $key = '${res[0]}' - '${res[1]}'")
            val tokens: List<Long>
            synchronized(lock) { inflight.remove(key); tokens = waiting.remove(key) ?: emptyList() }
            for (t in tokens) try { cb.onMeta(t, res[0], res[1]) } catch (_: Exception) {}
            return
        }
        val n = synchronized(lock) { val a = (attempts[key] ?: 0) + 1; attempts[key] = a; a }
        if (n < 5 && running) {
            try { exec.schedule({ runQuery(key, dr, sr, tr, id) }, n.toLong() + 1, TimeUnit.SECONDS) }
            catch (_: RejectedExecutionException) { synchronized(lock) { inflight.remove(key) } }
        } else {
            log("link: giving up on metadata for $key")
            synchronized(lock) { inflight.remove(key) }
        }
    }

    private class Msg(val txid: Long, val type: Int, val args: List<Any>)

    private fun num(o: DataOutputStream, v: Long) { o.writeByte(0x11); o.writeInt(v.toInt()) }

    private fun msgBytes(txid: Long, type: Int, args: LongArray): ByteArray {
        val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
        num(o, 0x872349aeL); num(o, txid)
        o.writeByte(0x10); o.writeShort(type)
        o.writeByte(0x0f); o.writeByte(args.size)
        o.writeByte(0x14); o.writeInt(12)
        for (i in 0 until 12) o.writeByte(if (i < args.size) 6 else 0)
        for (a in args) num(o, a)
        return bo.toByteArray()
    }

    private fun readField(i: DataInputStream): Any {
        return when (val tag = i.readUnsignedByte()) {
            0x0f -> i.readUnsignedByte().toLong()
            0x10 -> i.readUnsignedShort().toLong()
            0x11 -> i.readInt().toLong() and 0xffffffffL
            0x14 -> {
                val n = i.readInt()
                if (n < 0 || n > (1 shl 20)) throw IOException("blob length $n")
                val b = ByteArray(n); i.readFully(b); b
            }
            0x26 -> {
                val n = i.readInt()
                if (n < 0 || n > 4096) throw IOException("string length $n")
                val b = ByteArray(n * 2); i.readFully(b)
                String(b, Charsets.UTF_16BE).trimEnd('\u0000')
            }
            else -> throw IOException("unknown field tag 0x%02x".format(tag))
        }
    }

    private fun readMsg(i: DataInputStream): Msg {
        val magic = readField(i)
        if (magic != 0x872349aeL) throw IOException("bad message magic")
        val txid = readField(i) as? Long ?: throw IOException("bad txid")
        val type = (readField(i) as? Long ?: throw IOException("bad type")).toInt()
        val argc = (readField(i) as? Long ?: throw IOException("bad argc")).toInt()
        val tags = readField(i) as? ByteArray ?: throw IOException("bad arg tags")
        if (argc < 0 || argc > 12 || tags.size != 12) throw IOException("bad argument count $argc")
        val args = ArrayList<Any>()
        for (k in 0 until argc) {
            val prev = args.lastOrNull()
            if (tags[k].toInt() == 3 && prev is Long && prev == 0L) { args.add(ByteArray(0)); continue }   // empty blob has no body
            args.add(readField(i))
        }
        return Msg(txid, type, args)
    }

    private fun isLimited(d: Dev) = d.number < 7 && d.name !in FLEXIBLE

    /** The player number to ask as (docs/prodjlink.md 5.7), or -1 if none is usable. */
    private fun askingNumber(target: Dev): Int {
        val me = myNumber
        if (me > 4 && !isLimited(target)) return target.number
        if (target.number > 15 || me in 1..4) return me
        synchronized(lock) {
            for (c in devices.values) {
                val r = c.number
                if (r != target.number && r in 1..4) { val l = c.st; if (l != null && l.dr != target.number) return r }
            }
        }
        return -1
    }

    /** Returns [artist, title] or null (retry later). Opens, queries and closes (teardown) per call. */
    private fun query(dr: Int, sr: Int, tr: Int, id: Long): Array<String>? {
        val target = synchronized(lock) { devices[dr] } ?: run { log("link: player $dr not known yet, cannot ask for track metadata"); return null }
        val asking = askingNumber(target)
        if (asking < 0) { log("link: no usable player number to ask player $dr for metadata"); return null }
        val n = net ?: return null
        // step A: ask for the dbserver port
        var port = 0
        for (attempt in 0 until 4) {
            if (!running) return null
            val s = Socket()
            try {
                n.bindSocket(s)
                s.connect(InetSocketAddress(target.ip, 12523), 3000); s.soTimeout = 3000
                s.getOutputStream().write(DB_QUERY); s.getOutputStream().flush()
                val b = ByteArray(2); DataInputStream(s.getInputStream()).readFully(b)
                port = (u8(b, 0) shl 8) or u8(b, 1)
            } finally { try { s.close() } catch (_: Exception) {} }
            if (port != 0xffff && port != 0) break
            port = 0
            if (!sleepMs(1000L * (attempt + 1))) return null
        }
        if (port == 0) { log("link: dbserver port of player $dr not ready"); return null }
        // step B: connect, greet, set up, query, render, tear down
        val s = Socket()
        try {
            n.bindSocket(s)
            s.connect(InetSocketAddress(target.ip, port), 3000); s.soTimeout = 4000
            val out = s.getOutputStream(); val inp = DataInputStream(s.getInputStream())
            val hello = byteArrayOf(0x11, 0, 0, 0, 1)
            out.write(hello); out.flush()
            val echo = ByteArray(5); inp.readFully(echo)
            if (!echo.contentEquals(hello)) throw IOException("greeting not echoed")
            out.write(msgBytes(0xfffffffeL, 0x0000, longArrayOf(asking.toLong()))); out.flush()
            val setup = readMsg(inp)
            if (setup.type != 0x4000) throw IOException("setup rejected (type 0x%04x)".format(setup.type))
            val who = (setup.args.getOrNull(1) as? Long)?.toInt()
            if (who != null && who != dr) log("link: setup reply says player $who, expected $dr")
            val dmst = ((asking and 0xff).toLong() shl 24) or (1L shl 16) or ((sr and 0xff).toLong() shl 8) or (tr and 0xff).toLong()
            val reqType = if (tr == 1) 0x2002 else 0x2202
            out.write(msgBytes(1, reqType, longArrayOf(dmst, id))); out.flush()
            val r = readMsg(inp)
            if (r.type != 0x4000) throw IOException("metadata request rejected (type 0x%04x)".format(r.type))
            val count = (r.args.getOrNull(1) as? Long) ?: 0L
            if (count == 0xffffffffL || count <= 0L) { log("link: player $dr has no such track (slot $sr id $id)"); return null }
            val limit = minOf(count, 64L)
            out.write(msgBytes(2, 0x3000, longArrayOf(dmst, 0, limit, 0, limit, 0))); out.flush()
            var title = ""; var artist = ""
            var guard = 0
            while (guard++ < 200) {
                val m = readMsg(inp)
                if (m.type == 0x4201) break
                if (m.type == 0x4101 && m.args.size >= 7) {
                    val t = ((m.args[6] as? Long) ?: -1L) and 0xffff
                    val label = m.args[3] as? String ?: ""
                    if (t == 0x04L && title.isEmpty()) title = label
                    if (t == 0x07L && artist.isEmpty()) artist = label
                }
            }
            try { out.write(msgBytes(0xfffffffeL, 0x0100, LongArray(0))); out.flush() } catch (_: Exception) {}
            if (title.isEmpty() && artist.isEmpty()) return null
            return arrayOf(artist, title)
        } finally { try { s.close() } catch (_: Exception) {} }
    }

    companion object {
        const val HOLD_MS = 8000L
        private const val NAME = "DJMRecorder"
        private val MAGIC = byteArrayOf(0x51, 0x73, 0x70, 0x74, 0x31, 0x57, 0x6d, 0x4a, 0x4f, 0x4c)
        private val DB_QUERY = byteArrayOf(0, 0, 0, 0x0f, 0x52, 0x65, 0x6d, 0x6f, 0x74, 0x65, 0x44, 0x42, 0x53, 0x65, 0x72, 0x76, 0x65, 0x72, 0)
        private val FLEXIBLE = setOf("CDJ-3000", "CDJ-3000X", "XDJ-AZ")
    }
}
