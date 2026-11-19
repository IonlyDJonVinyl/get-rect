package app.djmrecorder

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Growable list of wave entries, [STRIDE] bytes per 10 ms: low, mid, high (3-band of the stereo mix), then the
 * left and right channel peak. All values are sqrt-encoded peaks (linear = (b/255)^2).
 */
class WaveData(initial: Int = 4096 * STRIDE) {
    var bytes = ByteArray(maxOf(initial, STRIDE)); private set
    var count = 0; private set

    fun append(src: ByteArray, off: Int = 0, len: Int = src.size - off) {
        val n = len / STRIDE * STRIDE
        if (n <= 0) return
        val need = count * STRIDE + n
        if (need > bytes.size) bytes = bytes.copyOf(maxOf(need, bytes.size * 2))
        System.arraycopy(src, off, bytes, count * STRIDE, n)
        count += n / STRIDE
    }

    fun clear() { count = 0 }

    /** Keeps only the newest [max] entries (used for the rolling pre-roll view). */
    fun trimTo(max: Int) {
        if (count <= max) return
        val drop = count - max
        System.arraycopy(bytes, drop * STRIDE, bytes, 0, max * STRIDE)
        count = max
    }

    fun appendZeros(entries: Int) {
        if (entries <= 0) return
        val need = (count + entries) * STRIDE
        if (need > bytes.size) bytes = bytes.copyOf(maxOf(need, bytes.size * 2))
        java.util.Arrays.fill(bytes, count * STRIDE, need, 0)
        count += entries
    }

    companion object {
        const val PER_SEC = 100
        const val STRIDE = 5
        /** `.wave` file header (version 2): "DJW" + 0x02, then 5-byte entries. Files without it are the 0.3/0.4 format (3-byte entries). */
        val MAGIC = byteArrayOf('D'.code.toByte(), 'J'.code.toByte(), 'W'.code.toByte(), 2)

        /** Parses a `.wave` file (either version). Old 3-byte entries get left = right = loudest band. */
        fun fromFile(raw: ByteArray): WaveData {
            if (raw.size >= 4 && raw[0] == MAGIC[0] && raw[1] == MAGIC[1] && raw[2] == MAGIC[2] && raw[3] == MAGIC[3]) {
                return WaveData(maxOf(raw.size - 4, STRIDE)).also { it.append(raw, 4, raw.size - 4) }
            }
            val n = raw.size / 3
            val d = WaveData(maxOf(n * STRIDE, STRIDE))
            val e = ByteArray(STRIDE)
            for (i in 0 until n) {
                val l = raw[i * 3]; val m = raw[i * 3 + 1]; val h = raw[i * 3 + 2]
                val mx = maxOf(l.toInt() and 0xff, m.toInt() and 0xff, h.toInt() and 0xff).toByte()
                e[0] = l; e[1] = m; e[2] = h; e[3] = mx; e[4] = mx
                d.append(e)
            }
            return d
        }

        /** sqrt-encoded byte -> linear 0..1 */
        fun linear(b: Int): Float { val x = b / 255f; return x * x }

        /** Packed max of each band over entries [i0, i1): (low shl 16) | (mid shl 8) | high. */
        fun maxRange(d: WaveData, i0: Int, i1: Int): Int {
            val a = maxOf(i0, 0); val b = minOf(i1, d.count)
            var l = 0; var m = 0; var h = 0
            val by = d.bytes
            var o = a * STRIDE
            for (i in a until b) {
                val x = by[o].toInt() and 0xff; val y = by[o + 1].toInt() and 0xff; val z = by[o + 2].toInt() and 0xff
                if (x > l) l = x; if (y > m) m = y; if (z > h) h = z
                o += STRIDE
            }
            return (l shl 16) or (m shl 8) or h
        }

        /** Max of low, mid, high, left, right over entries [i0, i1) into [out] (5 ints). */
        fun maxRangeLR(d: WaveData, i0: Int, i1: Int, out: IntArray) {
            val a = maxOf(i0, 0); val b = minOf(i1, d.count)
            var l = 0; var m = 0; var h = 0; var pl = 0; var pr = 0
            val by = d.bytes
            var o = a * STRIDE
            for (i in a until b) {
                val x = by[o].toInt() and 0xff; val y = by[o + 1].toInt() and 0xff; val z = by[o + 2].toInt() and 0xff
                val cl = by[o + 3].toInt() and 0xff; val cr = by[o + 4].toInt() and 0xff
                if (x > l) l = x; if (y > m) m = y; if (z > h) h = z
                if (cl > pl) pl = cl; if (cr > pr) pr = cr
                o += STRIDE
            }
            out[0] = l; out[1] = m; out[2] = h; out[3] = pl; out[4] = pr
        }
    }
}

/**
 * Info parsed from a recording's WAV header (24-bit PCM written by djmusb.c). The header is walked chunk by chunk:
 * `RIFF` or `RF64` (+ `ds64`), any `JUNK`, then `fmt ` and `data`. New files have an 80 byte header, 0.3 files 44.
 */
class WavInfo(val file: File, val rate: Int, val channels: Int, val frames: Long, val dataOffset: Long = 44L) {
    val seconds get() = frames.toDouble() / rate
    val stride get() = channels * 3
    companion object {
        fun read(f: File): WavInfo? = try {
            RandomAccessFile(f, "r").use { r ->
                val len = r.length()
                if (len < 12) return null
                val h = ByteArray(12)
                r.readFully(h)
                val magic = String(h, 0, 4, Charsets.US_ASCII)
                if ((magic != "RIFF" && magic != "RF64") || String(h, 8, 4, Charsets.US_ASCII) != "WAVE") return null
                var pos = 12L
                var ch = 0; var rate = 0; var bits = 0; var fmtTag = 0
                var dataOff = -1L
                val hd = ByteArray(8)
                val body = ByteArray(16)
                while (pos + 8 <= len && dataOff < 0) {
                    r.seek(pos); r.readFully(hd)
                    val id = String(hd, 0, 4, Charsets.US_ASCII)
                    val size = u32(hd, 4)
                    val start = pos + 8
                    when (id) {
                        "fmt " -> {
                            if (size < 16 || start + 16 > len) return null
                            r.readFully(body)
                            fmtTag = u16(body, 0); ch = u16(body, 2); rate = u32(body, 4).toInt(); bits = u16(body, 14)
                        }
                        "data" -> dataOff = start
                        // "ds64" holds the 64-bit sizes of an RF64 file. The real data length comes from the
                        // file size below (the header is refreshed once a second, so a crashed file keeps its tail).
                    }
                    pos = start + size + (size and 1L)
                }
                if (dataOff < 0 || ch < 1 || rate < 8000 || bits != 24 || (fmtTag != 1 && fmtTag != 0xFFFE)) return null
                WavInfo(f, rate, ch, (len - dataOff) / (ch * 3), dataOff)
            }
        } catch (_: Exception) { null }
        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    }
}

/** A glitch (or a mixer reconnect) at [sec] in the recording; [ms] is how much time is missing / how long the mixer was gone. */
class DropEvent(val sec: Double, val ms: Long, val reconnect: Boolean = false)

object Rec {
    fun base(wav: File) = wav.name.removeSuffix(".wav")
    private fun sib(wav: File, suffix: String) = File(wav.parentFile, base(wav) + suffix)
    fun stems(wav: File) = sib(wav, "-12ch.wav")
    fun waveFile(wav: File) = sib(wav, ".wave")
    fun cueFile(wav: File) = sib(wav, ".cue.txt")
    fun eventsFile(wav: File) = sib(wav, ".events.txt")
    fun tracksFile(wav: File) = sib(wav, ".tracks.txt")

    /** `<name>.events.txt`: one line per event, "seconds durationMs" (+ " reconnect" for an unplug/replug). */
    fun loadEvents(wav: File): List<DropEvent> = try {
        eventsFile(wav).readLines().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            val t = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val ms = p.getOrNull(1)?.toDoubleOrNull()?.toLong() ?: 0L
            DropEvent(t, ms, p.getOrNull(2) == "reconnect")
        }.sortedBy { it.sec }
    } catch (_: Exception) { emptyList() }

    fun appendEvent(wav: File, e: DropEvent) {
        try { eventsFile(wav).appendText("%.3f %d%s\n".format(Locale.US, e.sec, e.ms, if (e.reconnect) " reconnect" else "")) } catch (_: Exception) {}
    }

    fun dropoutCount(wav: File): Int = loadEvents(wav).count { !it.reconnect }

    fun loadMarkers(wav: File): MutableList<Double> = try {
        cueFile(wav).readLines().mapNotNull { it.trim().toDoubleOrNull() }.sorted().toMutableList()
    } catch (_: Exception) { mutableListOf() }

    fun saveMarkers(wav: File, m: List<Double>) {
        try { cueFile(wav).writeText(m.joinToString("\n") { "%.3f".format(Locale.US, it) } + "\n") } catch (_: Exception) {}
    }

    private val stampRe = Regex("mix-(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})(\\d{2})(?:-(\\d+))?")
    fun displayName(base: String): String {
        val m = stampRe.matchEntire(base) ?: return base
        val (y, mo, d, h, mi, n) = m.destructured
        return "$y-$mo-$d $h:$mi" + if (n.isNotEmpty()) " ($n)" else ""
    }

    fun clock(sec: Int) = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
    fun clockT(sec: Double): String {
        val s = if (sec < 0) 0.0 else sec
        val t = (s * 10).toLong()
        val whole = t / 10
        return if (whole >= 3600) "%d:%02d:%02d.%d".format(whole / 3600, whole / 60 % 60, whole % 60, t % 10)
        else "%02d:%02d.%d".format(whole / 60, whole % 60, t % 10)
    }
    fun size(b: Long) = if (b > 1 shl 30) "%.1f GB".format(Locale.US, b / 1073741824.0) else "%.0f MB".format(Locale.US, b / 1048576.0)
    fun date(f: File): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
    fun dbOf(lin: Float) = if (lin <= 0f) -90f else (20 * log10(lin.toDouble())).toFloat()
}

/** Same filters as djmusb.c, so old recordings without a .wave get an identical analysis. */
object WaveAnalyzer {
    fun analyze(info: WavInfo, cancel: () -> Boolean, progress: (Float) -> Unit): WaveData? {
        val rate = info.rate
        val win = maxOf(1, rate / 100)
        val aLo = (1.0 - exp(-6.2831853 * 200.0 / rate)).toFloat()
        val aHi = (1.0 - exp(-6.2831853 * 2500.0 / rate)).toFloat()
        var lpLo = 0f; var lpHi = 0f
        var n = 0; var pkL = 0f; var pkM = 0f; var pkH = 0f
        val out = WaveData(maxOf(WaveData.STRIDE, (info.frames / win * WaveData.STRIDE + WaveData.STRIDE).toInt()))
        val e = ByteArray(WaveData.STRIDE)
        var pkCl = 0f; var pkCr = 0f
        val stride = info.stride
        val chunkFrames = 16384
        val buf = ByteArray(chunkFrames * stride)
        try {
            RandomAccessFile(info.file, "r").use { r ->
                r.seek(info.dataOffset)
                var done = 0L
                while (done < info.frames) {
                    if (cancel()) return null
                    val nf = minOf(chunkFrames.toLong(), info.frames - done).toInt()
                    r.readFully(buf, 0, nf * stride)
                    var o = 0
                    for (i in 0 until nf) {
                        // channels 0/1 of the file are the stereo mix (stereo file: stride 6)
                        val l = (buf[o].toInt() and 0xff) or ((buf[o + 1].toInt() and 0xff) shl 8) or (buf[o + 2].toInt() shl 16)
                        val rr = (buf[o + 3].toInt() and 0xff) or ((buf[o + 4].toInt() and 0xff) shl 8) or (buf[o + 5].toInt() shl 16)
                        o += stride
                        val m = (l + rr) * (0.5f / 8388608f)
                        val al = abs(l) / 8388608f; val ar = abs(rr) / 8388608f
                        if (al > pkCl) pkCl = al; if (ar > pkCr) pkCr = ar
                        lpLo += aLo * (m - lpLo); lpHi += aHi * (m - lpHi)
                        val lo = abs(lpLo); val hi = abs(m - lpHi); val mi = abs(lpHi - lpLo)
                        if (lo > pkL) pkL = lo; if (mi > pkM) pkM = mi; if (hi > pkH) pkH = hi
                        if (++n >= win) {
                            e[0] = enc(pkL); e[1] = enc(pkM); e[2] = enc(pkH); e[3] = enc(pkCl); e[4] = enc(pkCr)
                            out.append(e)
                            n = 0; pkL = 0f; pkM = 0f; pkH = 0f; pkCl = 0f; pkCr = 0f
                        }
                    }
                    done += nf
                    progress(done.toFloat() / info.frames)
                }
            }
        } catch (_: Exception) { return if (out.count > 0) out else null }
        return out
    }

    private fun enc(pk: Float): Byte = (sqrt(minOf(pk, 1f)) * 255f + 0.5f).toInt().toByte()
}

object WaveStore {
    /** Loads `<name>.wave`, or (re)builds it from the WAV and caches it. Runs on a background thread. */
    fun loadOrBuild(info: WavInfo, cancel: () -> Boolean, progress: (Float) -> Unit): WaveData? {
        val wf = Rec.waveFile(info.file)
        val expected = (info.frames * 100 / info.rate).toInt()
        try {
            if (wf.exists() && wf.length() >= 3) {
                val raw = wf.readBytes()
                val d = WaveData.fromFile(raw)
                if (d.count >= expected - 300) return d   // else: interrupted/short, rebuild
            }
        } catch (_: Exception) {}
        val d = WaveAnalyzer.analyze(info, cancel, progress) ?: return null
        try {
            val tmp = File(wf.parentFile, wf.name + ".tmp")
            tmp.outputStream().use { it.write(WaveData.MAGIC); it.write(d.bytes, 0, d.count * WaveData.STRIDE) }
            if (!tmp.renameTo(wf)) { wf.delete(); tmp.renameTo(wf) }
        } catch (_: Exception) {}
        return d
    }
}

object PeakScanner {
    /** True sample peak (linear 0..1, both channels) of frames [start, end) of a 24-bit stereo WAV, or -1 if cancelled. */
    fun scan(info: WavInfo, start: Long, end: Long, cancel: () -> Boolean): Float {
        val stride = info.stride
        val chunk = 32768
        val buf = ByteArray(chunk * stride)
        var peak = 0
        try {
            RandomAccessFile(info.file, "r").use { r ->
                r.seek(info.dataOffset + start * stride)
                var pos = start
                while (pos < end) {
                    if (cancel()) return -1f
                    val nf = minOf(chunk.toLong(), end - pos).toInt()
                    r.readFully(buf, 0, nf * stride)
                    var o = 0
                    for (i in 0 until nf) {
                        for (c in 0..1) {
                            val b = o + c * 3
                            var v = (buf[b].toInt() and 0xff) or ((buf[b + 1].toInt() and 0xff) shl 8) or (buf[b + 2].toInt() shl 16)
                            if (v < 0) v = -v
                            if (v > peak) peak = v
                        }
                        o += stride
                    }
                    pos += nf
                }
            }
        } catch (_: Exception) { return -1f }
        return minOf(1f, peak / 8388608f)
    }
}

/** One tracklist line. [deck] 0 = unknown; [source] is "link", "midi", "audio" (after-analysis recognition) or "manual". */
class TrackEntry(var sec: Double, var deck: Int, var artist: String, var title: String, var source: String) {
    fun copy() = TrackEntry(sec, deck, artist, title, source)
    fun hasName() = artist.isNotBlank() || title.isNotBlank()
    /** "Artist - Title", or a placeholder for midi-only entries. */
    fun label(): String = when {
        artist.isNotBlank() && title.isNotBlank() -> "$artist - $title"
        title.isNotBlank() -> title
        artist.isNotBlank() -> artist
        deck > 0 -> "Deck $deck"
        else -> "Unknown track"
    }
}

object TrackFile {
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    /** `<name>.tracks.txt`: `seconds<TAB>deck<TAB>artist<TAB>title<TAB>source` per line. */
    @Synchronized fun load(wav: File): MutableList<TrackEntry> = try {
        tracksFile(wav).readLines().mapNotNull { line ->
            val p = line.split('\t')
            val t = p.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            TrackEntry(t, p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0, p.getOrNull(2)?.trim() ?: "",
                p.getOrNull(3)?.trim() ?: "", p.getOrNull(4)?.trim()?.ifEmpty { null } ?: "manual")
        }.sortedBy { it.sec }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    @Synchronized fun save(wav: File, list: List<TrackEntry>) {
        try {
            val f = tracksFile(wav)
            if (list.isEmpty()) { f.delete(); return }
            f.writeText(list.sortedBy { it.sec }.joinToString("") {
                "%.3f\t%d\t%s\t%s\t%s\n".format(Locale.US, it.sec, it.deck, clean(it.artist), clean(it.title), it.source)
            })
        } catch (_: Exception) {}
    }

    private fun tracksFile(wav: File) = Rec.tracksFile(wav)
}
