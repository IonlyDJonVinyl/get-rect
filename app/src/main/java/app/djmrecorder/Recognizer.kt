package app.djmrecorder

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A recognised song. Fields may be empty when the service does not return them. */
class Match(val artist: String, val title: String, val album: String = "", val label: String = "",
            val date: String = "", val link: String = "", val timecode: String = "")

/** One answered request: [match] is null for "no match"; [error] is a non-fatal API error (that probe only); [raw] is the response text. */
class Reply(val match: Match?, val error: String?, val raw: String)

/** Token or quota problem: the whole job must stop and the message is shown to the user. */
class RecognizerFatal(message: String) : Exception(message)

/**
 * A song recognition provider. Implementations are blocking and must be called from a background thread, one request at a time.
 * Throws [IOException] for network trouble (the caller retries once) and [RecognizerFatal] for token/quota errors.
 * Only AudD is implemented; another provider (ACRCloud) can be added by implementing this interface.
 */
interface Recognizer {
    val name: String
    /** [wav] is a complete 16-bit mono 44.1 kHz WAV file. */
    fun recognize(wav: ByteArray): Reply
    /** Aborts a request in flight (used by Cancel). */
    fun abort() {}
}

/** AudD (https://docs.audd.io/): POST multipart `api_token` + `file` to https://api.audd.io/. */
class AuddRecognizer(private val token: String) : Recognizer {
    override val name = "audd"
    @Volatile private var conn: HttpURLConnection? = null

    override fun abort() { try { conn?.disconnect() } catch (_: Exception) {} }

    override fun recognize(wav: ByteArray): Reply {
        val boundary = "----djmrec" + System.nanoTime()
        val body = ByteArrayOutputStream(wav.size + 512)
        body.write(("--$boundary\r\nContent-Disposition: form-data; name=\"api_token\"\r\n\r\n$token\r\n").toByteArray())
        body.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray())
        body.write(wav)
        body.write(("\r\n--$boundary--\r\n").toByteArray())
        val bytes = body.toByteArray()

        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn = c
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code == 429) throw RecognizerFatal("AudD: too many requests (HTTP 429)")
            if (code >= 500 || text.isBlank()) throw IOException("HTTP $code")
            return parse(text)
        } finally {
            conn = null
            try { c.disconnect() } catch (_: Exception) {}
        }
    }

    companion object {
        const val ENDPOINT = "https://api.audd.io/"

        /** Parses an AudD response body. Throws [RecognizerFatal] for 9xx errors (token / quota), [IOException] for garbage. */
        fun parse(text: String): Reply {
            val j = try { JSONObject(text) } catch (e: Exception) { throw IOException("bad response") }
            val raw = if (text.length > 1500) text.substring(0, 1500) else text
            if (j.optString("status") == "error") {
                val er = j.optJSONObject("error")
                val code = er?.optInt("error_code", 0) ?: 0
                val msg = er?.optString("error_message").orEmpty().ifBlank { "unknown error" }
                val full = "AudD #$code: $msg"
                if (code in 900..999) throw RecognizerFatal(full)
                return Reply(null, full, raw)
            }
            val r = j.opt("result")
            if (r is JSONObject) {
                val artist = r.optString("artist").trim(); val title = r.optString("title").trim()
                if (artist.isEmpty() && title.isEmpty()) return Reply(null, null, raw)
                return Reply(Match(artist, title, r.optString("album").trim(), r.optString("label").trim(),
                    r.optString("release_date").trim(), r.optString("song_link").trim(), r.optString("timecode").trim()), null, raw)
            }
            return Reply(null, null, raw)   // null or empty array: no match
        }
    }
}

/** Cuts clips out of the 24-bit recording and encodes them as 16-bit mono 44.1 kHz WAV, in memory. */
object ClipEncoder {
    const val OUT_RATE = 44100

    /** [bytes] is a full WAV file; [rmsDb] is the RMS level of the clip in dBFS (of the downmixed source). */
    class Clip(val bytes: ByteArray, val rmsDb: Double)

    private fun s24(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or (b[o + 2].toInt() shl 16)

    /** Clip of [lenSec] seconds from [startSec], or null if fewer than 3 s are available. Throws on read errors. */
    fun encode(info: WavInfo, startSec: Int, lenSec: Int): Clip? {
        val rate = info.rate
        val stride = info.stride                       // channels * 3 bytes (6 for stereo)
        val startFrame = startSec.toLong() * rate
        val n = minOf(lenSec.toLong() * rate, info.frames - startFrame)
        if (n < 3L * rate) return null
        val mono = FloatArray(n.toInt())
        var sumSq = 0.0
        val chunk = 16384
        val buf = ByteArray(chunk * stride)
        RandomAccessFile(info.file, "r").use { r ->
            r.seek(info.dataOffset + startFrame * stride)
            var done = 0
            while (done < n) {
                val nf = minOf(chunk.toLong(), n - done).toInt()
                r.readFully(buf, 0, nf * stride)
                var o = 0
                for (i in 0 until nf) {
                    val l = s24(buf, o)
                    val rr = if (info.channels >= 2) s24(buf, o + 3) else l
                    val m = (l + rr) * (0.5 / 8388608.0)
                    mono[done + i] = m.toFloat()
                    sumSq += m * m
                    o += stride
                }
                done += nf
            }
        }
        val rms = sqrt(sumSq / mono.size)
        val db = if (rms <= 1e-6) -120.0 else 20 * log10(rms)
        return Clip(wav16(resample(mono, rate)), db)
    }

    /**
     * Resamples to 44.1 kHz. Sources of 88.2 kHz and up are first box-averaged by an integer factor (a cheap anti-alias
     * step), then everything goes through linear interpolation.
     */
    fun resample(src: FloatArray, rate: Int): FloatArray {
        var x = src
        var r = rate.toDouble()
        val dec = maxOf(1, (rate / OUT_RATE.toDouble()).toInt())
        if (dec > 1) {
            val m = src.size / dec
            val y = FloatArray(m)
            for (i in 0 until m) { var s = 0f; for (k in 0 until dec) s += src[i * dec + k]; y[i] = s / dec }
            x = y; r = rate / dec.toDouble()
        }
        val ratio = r / OUT_RATE
        val outN = (x.size / ratio).toInt()
        val out = FloatArray(outN)
        for (i in 0 until outN) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = minOf(i0 + 1, x.size - 1)
            val f = (pos - i0).toFloat()
            out[i] = x[i0] + (x[i1] - x[i0]) * f
        }
        return out
    }

    /** 44-byte-header PCM WAV, 16-bit mono at [OUT_RATE]. */
    fun wav16(s: FloatArray): ByteArray {
        val dataLen = s.size * 2
        val b = ByteArray(44 + dataLen)
        fun w32(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte() }
        fun w16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        "RIFF".toByteArray().copyInto(b, 0); w32(4, 36 + dataLen); "WAVEfmt ".toByteArray().copyInto(b, 8)
        w32(16, 16); w16(20, 1); w16(22, 1); w32(24, OUT_RATE); w32(28, OUT_RATE * 2); w16(32, 2); w16(34, 16)
        "data".toByteArray().copyInto(b, 36); w32(40, dataLen)
        for (i in s.indices) {
            val v = (s[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
            b[44 + i * 2] = v.toByte(); b[45 + i * 2] = (v shr 8).toByte()
        }
        return b
    }
}
