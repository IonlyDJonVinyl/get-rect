package app.djmrecorder

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.Locale
import java.util.Random
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Fmt(val label: String, val ext: String, val mime: String, val suffix: String) {
    WAV24("WAV 24-bit", "wav", "audio/x-wav", ""),
    WAV16("WAV 16-bit", "wav", "audio/x-wav", "-16bit"),
    FLAC("FLAC", "flac", "audio/flac", ""),
    AAC("AAC 256k", "m4a", "audio/mp4", "-256k")
}

class ExportParams(
    val info: WavInfo, val startFrame: Long, val endFrame: Long, val gainDb: Float,
    val fade: Boolean, val fmt: Fmt, val baseName: String, val markers: List<Double>
)

class ExportResult(val uri: Uri?, val mime: String, val message: String)

class Cancelled : Exception()

/** One output file in Music/DJM Recorder (MediaStore on API 29+, plain public-dir file before). */
class OutTarget private constructor(
    private val ctx: Context, val name: String, val mime: String, val uri: Uri?, val file: File?
) {
    private var pfd: ParcelFileDescriptor? = null

    fun stream(): OutputStream {
        return if (uri != null) {
            val p = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("cannot open $name")
            pfd = p
            ParcelFileDescriptor.AutoCloseOutputStream(p)
        } else FileOutputStream(file!!)
    }

    fun muxer(): MediaMuxer {
        return if (uri != null) {
            val p = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("cannot open $name")
            pfd = p
            MediaMuxer(p.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else MediaMuxer(file!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun closeFd() { try { pfd?.close() } catch (_: Exception) {}; pfd = null }

    /** Final display name (MediaStore may have de-duplicated it). */
    fun finalName(): String {
        if (uri == null) return file!!.name
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (_: Exception) {}
        return name
    }

    fun commit(done: (Uri?) -> Unit) {
        closeFd()
        if (uri != null) {
            try {
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (ctx.contentResolver.update(uri, cv, null, null) != 1) {
                    throw IOException("Could not publish ${finalName()}")
                }
            } catch (e: Exception) {
                abort()
                throw e
            }
            done(uri)
        } else {
            MediaScannerConnection.scanFile(ctx, arrayOf(file!!.absolutePath), arrayOf(mime)) { _, u -> done(u) }
        }
    }

    fun abort() {
        closeFd()
        try { if (uri != null) ctx.contentResolver.delete(uri, null, null) else file?.delete() } catch (_: Exception) {}
    }

    companion object {
        const val FOLDER = "Get Rect"

        fun create(ctx: Context, name: String, mime: String, music: Boolean = true): OutTarget {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, (if (music) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOCUMENTS) + "/" + FOLDER)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val coll = if (music) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val u = ctx.contentResolver.insert(coll, cv) ?: throw IOException("MediaStore refused $name")
                return OutTarget(ctx, name, mime, u, null)
            }
            val dir = File(Environment.getExternalStoragePublicDirectory(
                if (music) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOCUMENTS), FOLDER)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
            var f = File(dir, name)
            val stem = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
            var i = 1
            while (f.exists()) { f = File(dir, "$stem ($i).$ext"); i++ }
            return OutTarget(ctx, name, mime, null, f)
        }
    }
}

/** Produces trimmed, gained, optionally faded stereo float frames from a 24-bit stereo WAV. */
class PcmReader(private val p: ExportParams, private val cancel: () -> Boolean) : AutoCloseable {
    private val info = p.info
    private val raf = RandomAccessFile(info.file, "r")
    private val total = p.endFrame - p.startFrame
    private val g = 10.0.pow(p.gainDb / 20.0).toFloat()
    private val fadeLen = if (p.fade) min(2L * info.rate, total / 2) else 0L
    private var pos = 0L
    private val raw = ByteArray(8192 * 6)
    val done get() = pos
    val length get() = total

    init { raf.seek(info.dataOffset + p.startFrame * info.stride) }

    /** Fills [out] with interleaved stereo floats (clamped to +-1 later). Returns frames read (0 = end). */
    fun next(out: FloatArray, max: Int = Int.MAX_VALUE): Int {
        if (cancel()) throw Cancelled()
        val want = minOf(minOf(out.size / 2, 8192, max).toLong(), total - pos).toInt()
        if (want <= 0) return 0
        if (info.channels == 2) raf.readFully(raw, 0, want * 6)
        else {
            // multichannel source: take channels 0/1
            val tmp = ByteArray(want * info.stride)
            raf.readFully(tmp)
            for (i in 0 until want) System.arraycopy(tmp, i * info.stride, raw, i * 6, 6)
        }
        var o = 0
        for (i in 0 until want) {
            var m = g
            if (fadeLen > 0) {
                val f = pos + i
                if (f < fadeLen) m *= f.toFloat() / fadeLen
                val rem = total - f
                if (rem < fadeLen) m *= rem.toFloat() / fadeLen
            }
            for (c in 0..1) {
                val v = (raw[o].toInt() and 0xff) or ((raw[o + 1].toInt() and 0xff) shl 8) or (raw[o + 2].toInt() shl 16)
                out[i * 2 + c] = v * m / 8388608f
                o += 3
            }
        }
        pos += want
        return want
    }

    override fun close() { try { raf.close() } catch (_: Exception) {} }
}

object Exporter {
    private fun le(v: Long, n: Int, b: ByteArray, o: Int) { for (i in 0 until n) b[o + i] = (v shr (8 * i)).toByte() }

    /**
     * Header for [frames] frames. Up to 0xFFFFFF00 data bytes this is the classic 44 byte RIFF header;
     * beyond that an 80 byte RF64 header (EBU Tech 3306): RF64/0xFFFFFFFF, WAVE, ds64 (riff, data, samples, table), fmt, data/0xFFFFFFFF.
     */
    fun wavHeader(rate: Int, ch: Int, bits: Int, frames: Long): ByteArray {
        val data = frames * ch * (bits / 8)
        if (data > 0xFFFFFF00L) {
            val h = ByteArray(80)
            "RF64".toByteArray().copyInto(h, 0); le(0xFFFFFFFFL, 4, h, 4)
            "WAVE".toByteArray().copyInto(h, 8)
            "ds64".toByteArray().copyInto(h, 12); le(28, 4, h, 16)
            le(72 + data, 8, h, 20); le(data, 8, h, 28); le(frames, 8, h, 36); le(0, 4, h, 44)
            "fmt ".toByteArray().copyInto(h, 48); le(16, 4, h, 52)
            le(1, 2, h, 56); le(ch.toLong(), 2, h, 58); le(rate.toLong(), 4, h, 60)
            le(rate.toLong() * ch * bits / 8, 4, h, 64); le((ch * bits / 8).toLong(), 2, h, 68); le(bits.toLong(), 2, h, 70)
            "data".toByteArray().copyInto(h, 72); le(0xFFFFFFFFL, 4, h, 76)
            return h
        }
        val h = ByteArray(44)
        "RIFF".toByteArray().copyInto(h, 0); le(data + 36, 4, h, 4)
        "WAVEfmt ".toByteArray().copyInto(h, 8); le(16, 4, h, 16)
        le(1, 2, h, 20); le(ch.toLong(), 2, h, 22); le(rate.toLong(), 4, h, 24)
        le(rate.toLong() * ch * bits / 8, 4, h, 28); le((ch * bits / 8).toLong(), 2, h, 32); le(bits.toLong(), 2, h, 34)
        "data".toByteArray().copyInto(h, 36); le(data, 4, h, 40)
        return h
    }

    fun run(ctx: Context, p: ExportParams, progress: (Float) -> Unit, cancel: () -> Boolean): ExportResult {
        val frames = p.endFrame - p.startFrame
        if (frames <= 0) throw IOException("empty selection")
        val name = p.baseName + p.fmt.suffix + "." + p.fmt.ext
        val out = OutTarget.create(ctx, name, p.fmt.mime)
        var ok = false
        try {
            PcmReader(p, cancel).use { r ->
                when (p.fmt) {
                    Fmt.WAV24, Fmt.WAV16 -> writeWav(out, r, p, progress)
                    Fmt.FLAC -> encode(out, r, p, "audio/flac", progress, cancel)
                    Fmt.AAC -> encode(out, r, p, "audio/mp4a-latm", progress, cancel)
                }
            }
            ok = true
        } finally { if (!ok) out.abort() }

        var result: Uri? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        out.commit { result = it; latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        var cueNote = ""
        if (p.markers.isNotEmpty()) cueNote = try { writeCue(ctx, p, out.finalName()) } catch (_: Exception) { " (cue file failed)" }
        return ExportResult(result, p.fmt.mime, "Saved Music/${OutTarget.FOLDER}/${out.finalName()}$cueNote")
    }

    private fun writeWav(out: OutTarget, r: PcmReader, p: ExportParams, progress: (Float) -> Unit) {
        val bits = if (p.fmt == Fmt.WAV24) 24 else 16
        val bps = bits / 8
        val rnd = Random()
        val fb = FloatArray(8192 * 2)
        val ob = ByteArray(8192 * 2 * bps)
        BufferedOutputStream(out.stream(), 1 shl 20).use { os ->
            os.write(wavHeader(p.info.rate, 2, bits, r.length))
            while (true) {
                val n = r.next(fb)
                if (n == 0) break
                var o = 0
                for (i in 0 until n * 2) {
                    val x = fb[i]
                    if (bits == 24) {
                        val v = (x * 8388608f).roundToInt().coerceIn(-8388608, 8388607)
                        ob[o] = v.toByte(); ob[o + 1] = (v shr 8).toByte(); ob[o + 2] = (v shr 16).toByte(); o += 3
                    } else {
                        val v = dither16(x, rnd)
                        ob[o] = v.toByte(); ob[o + 1] = (v shr 8).toByte(); o += 2
                    }
                }
                os.write(ob, 0, o)
                progress(r.done.toFloat() / r.length)
            }
        }
    }

    /** 16-bit with TPDF dither (+-1 LSB triangular). */
    fun dither16(x: Float, rnd: Random): Int {
        val d = rnd.nextFloat() - rnd.nextFloat()
        return (x * 32767f + d).roundToInt().coerceIn(-32768, 32767)
    }

    private fun encode(out: OutTarget, r: PcmReader, p: ExportParams, mime: String, progress: (Float) -> Unit, cancel: () -> Boolean) {
        val isAac = mime == "audio/mp4a-latm"
        val srcRate = p.info.rate
        val decim = if (isAac && srcRate > 48000) 2 else 1   // AAC encoders top out at 48 kHz on most phones
        val outRate = srcRate / decim
        val fmt = MediaFormat.createAudioFormat(mime, outRate, 2)
        if (isAac) {
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
        } else fmt.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
        val codec = try { MediaCodec.createEncoderByType(mime) }
        catch (_: Exception) { throw IOException("this phone has no ${if (isAac) "AAC" else "FLAC"} encoder") }
        var muxer: MediaMuxer? = null
        var os: OutputStream? = null
        var started = false
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            started = true
            if (isAac) muxer = out.muxer() else os = BufferedOutputStream(out.stream(), 1 shl 18)
            var track = -1; var muxStarted = false
            val info = MediaCodec.BufferInfo()
            val fb = FloatArray(8192 * 2)
            val rnd = Random()
            var inDone = false; var outDone = false
            var ptsFrames = 0L
            while (!outDone) {
                if (cancel()) throw Cancelled()
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear(); ib.order(ByteOrder.LITTLE_ENDIAN)
                        val maxOut = ib.capacity() / 4
                        var got = 0
                        while (got < maxOut) {
                            val want = min(maxOut - got, fb.size / 2 / decim)
                            val n = r.next(fb, want * decim)
                            if (n == 0) break
                            val outN = n / decim
                            for (i in 0 until outN) for (c in 0..1) {
                                var x = fb[i * decim * 2 + c]
                                if (decim == 2) x = (x + fb[(i * 2 + 1) * 2 + c]) * 0.5f
                                ib.putShort(dither16(x, rnd).toShort())
                            }
                            got += outN
                            if (n < want * decim) break
                        }
                        val pts = ptsFrames * 1_000_000L / outRate
                        if (got == 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, got * 4, pts, 0); ptsFrames += got
                            progress(r.done.toFloat() / r.length)
                        }
                    }
                }
                val info2 = info
                val oi = codec.dequeueOutputBuffer(info2, 10_000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    val isCfg = (info2.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (info2.size > 0 && (!isAac || !isCfg)) {
                        ob.position(info2.offset); ob.limit(info2.offset + info2.size)
                        if (isAac) {
                            if (muxStarted) muxer!!.writeSampleData(track, ob, info2)
                        } else {
                            val tmp = ByteArray(info2.size); ob.get(tmp); os!!.write(tmp)
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info2.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && isAac) {
                    track = muxer!!.addTrack(codec.outputFormat); muxer.start(); muxStarted = true
                }
            }
            os?.flush()
            if (muxStarted) { muxer?.stop() }
        } finally {
            try { os?.close() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { if (started) codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            out.closeFd()
        }
    }

    private fun cueTime(sec: Double): String {
        val frames = (sec * 75).roundToInt()
        return "%02d:%02d:%02d".format(Locale.US, frames / 75 / 60, frames / 75 % 60, frames % 75)
    }

    private fun writeCue(ctx: Context, p: ExportParams, audioName: String): String {
        val start = p.startFrame.toDouble() / p.info.rate; val end = p.endFrame.toDouble() / p.info.rate
        val inside = p.markers.filter { it > start + 0.05 && it < end - 0.05 }.map { it - start }
        if (inside.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("TITLE \"").append(p.baseName).append("\"\nFILE \"").append(audioName).append("\" WAVE\n")
        sb.append("  TRACK 01 AUDIO\n    TITLE \"Start\"\n    INDEX 01 00:00:00\n")
        inside.forEachIndexed { i, t ->
            sb.append("  TRACK %02d AUDIO\n    TITLE \"Marker %d\"\n    INDEX 01 %s\n".format(Locale.US, i + 2, i + 1, cueTime(t)))
        }
        val cueName = audioName.substringBeforeLast('.') + ".cue"
        // Audio collection only takes audio/* on API 29+, so a .cue may have to go to Documents instead.
        var where = "Music"
        val t = try { OutTarget.create(ctx, cueName, "application/x-cue", true) }
        catch (_: Exception) { where = "Documents"; OutTarget.create(ctx, cueName, "text/plain", false) }
        try {
            t.stream().use { it.write(sb.toString().toByteArray()) }
        } catch (e: Exception) { t.abort(); throw e }
        t.commit { }
        return " + ${inside.size} markers in $where/${OutTarget.FOLDER}/$cueName"
    }

    /** Saves a CUE sheet (text) to Music/DJM Recorder, or Documents/DJM Recorder when Music refuses a non-audio file. */
    fun saveCue(ctx: Context, cueName: String, text: String): String {
        var where = "Music"
        val t = try { OutTarget.create(ctx, cueName, "application/x-cue", true) }
        catch (_: Exception) { where = "Documents"; OutTarget.create(ctx, cueName, "text/plain", false) }
        try { t.stream().use { it.write(text.toByteArray()) } } catch (e: Exception) { t.abort(); throw e }
        val latch = java.util.concurrent.CountDownLatch(1)
        t.commit { latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return "Saved $where/${OutTarget.FOLDER}/${t.finalName()}"
    }

    /** Writes deck 1-4 and REC OUT as separate 24-bit stereo WAVs (trim only, no gain). */
    fun exportStems(ctx: Context, stems: File, info: WavInfo, startFrame: Long, endFrame: Long, baseName: String,
                    progress: (Float) -> Unit, cancel: () -> Boolean): String {
        val si = WavInfo.read(stems) ?: throw IOException("stems file unreadable")
        if (si.channels < 10) throw IOException("stems file has only ${si.channels} channels")
        val start = minOf(startFrame, si.frames); val end = minOf(endFrame, si.frames)
        val frames = end - start
        if (frames <= 0) throw IOException("empty selection")
        val names = listOf("deck1", "deck2", "deck3", "deck4", "master")
        val targets = ArrayList<OutTarget>()
        val streams = ArrayList<OutputStream>()
        var ok = false
        try {
            for (n in names) targets.add(OutTarget.create(ctx, "$baseName-$n.wav", Fmt.WAV24.mime))
            for (t in targets) streams.add(BufferedOutputStream(t.stream(), 1 shl 19))
            val hdr = wavHeader(si.rate, 2, 24, frames)
            for (s in streams) s.write(hdr)
            val chunk = 8192
            val stride = si.stride
            val buf = ByteArray(chunk * stride)
            val obuf = Array(5) { ByteArray(chunk * 6) }
            RandomAccessFile(stems, "r").use { r ->
                r.seek(si.dataOffset + start * stride)
                var pos = 0L
                while (pos < frames) {
                    if (cancel()) throw Cancelled()
                    val n = minOf(chunk.toLong(), frames - pos).toInt()
                    r.readFully(buf, 0, n * stride)
                    for (i in 0 until n) for (pair in 0 until 5) {
                        System.arraycopy(buf, i * stride + pair * 6, obuf[pair], i * 6, 6)
                    }
                    for (pair in 0 until 5) streams[pair].write(obuf[pair], 0, n * 6)
                    pos += n
                    progress(pos.toFloat() / frames)
                }
            }
            for (s in streams) s.close()
            streams.clear()
            val latch = java.util.concurrent.CountDownLatch(targets.size)
            for (t in targets) t.commit { latch.countDown() }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            ok = true
        } finally {
            for (s in streams) try { s.close() } catch (_: Exception) {}
            if (!ok) for (t in targets) t.abort()
        }
        return "Saved 5 stem files ($baseName-deck1..4, -master) in Music/${OutTarget.FOLDER}"
    }
}
