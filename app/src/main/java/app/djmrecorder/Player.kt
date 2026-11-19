package app.djmrecorder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile

/**
 * Streams a 24-bit stereo WAV through an AudioTrack (PCM_FLOAT) with live gain.
 * A fresh AudioTrack is created on every play/seek so the head position is always relative
 * to a known frame. All file I/O and writes happen on the player thread.
 */
class Player(private val info: WavInfo, private val onEnd: () -> Unit) {
    @Volatile var gain = 1f
    @Volatile var wantPlay = false; private set
    @Volatile private var seekTo = -1L
    @Volatile private var quit = false
    private val lock = Any()
    private var track: AudioTrack? = null
    private var base = 0L        // frame at which the current track started
    private var idlePos = 0L     // position when no track is running
    private val thread = Thread({ run() }, "player")

    fun start() { thread.start() }
    fun release() { quit = true }
    fun play() { wantPlay = true }
    fun pause() { wantPlay = false }
    fun seek(frame: Long) { seekTo = frame.coerceIn(0, info.frames) }

    fun position(): Long {
        val s = seekTo
        if (s >= 0) return s
        synchronized(lock) {
            val t = track ?: return idlePos
            val head = try { t.playbackHeadPosition.toLong() and 0xffffffffL } catch (_: Exception) { 0L }
            return minOf(info.frames, base + head)
        }
    }

    private fun makeTrack(): AudioTrack {
        val ch = AudioFormat.CHANNEL_OUT_STEREO
        val enc = AudioFormat.ENCODING_PCM_FLOAT
        val min = AudioTrack.getMinBufferSize(info.rate, ch, enc)
        val size = maxOf(if (min > 0) min * 4 else 0, 65536)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(enc).setSampleRate(info.rate).setChannelMask(ch).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(size)
            .build()
    }

    private fun dropTrack() {
        val t: AudioTrack?
        synchronized(lock) { t = track; track = null }
        try { t?.pause(); t?.flush() } catch (_: Exception) {}
        try { t?.release() } catch (_: Exception) {}
    }

    private fun run() {
        val chunk = 4096
        val raw = ByteArray(chunk * 6)
        val fb = FloatArray(chunk * 2)
        var readPos = 0L
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(info.file, "r")
            while (!quit) {
                val req = seekTo
                if (req >= 0) {
                    dropTrack()
                    synchronized(lock) { idlePos = req; seekTo = -1 }
                    readPos = req
                }
                if (!wantPlay) {
                    if (track != null) { val p = position(); dropTrack(); synchronized(lock) { idlePos = p }; readPos = p }
                    Thread.sleep(15); continue
                }
                if (track == null) {
                    if (readPos >= info.frames) { finish(); continue }
                    try {
                        val t = makeTrack()
                        synchronized(lock) { base = readPos; track = t }
                        t.play()
                    } catch (_: Exception) { dropTrack(); wantPlay = false; onEnd(); continue }
                }
                if (readPos >= info.frames) {
                    // everything written: wait until the device has played it
                    if (position() >= info.frames - 1) finish() else Thread.sleep(20)
                    continue
                }
                val n = minOf(chunk.toLong(), info.frames - readPos).toInt()
                raf.seek(info.dataOffset + readPos * 6)
                raf.readFully(raw, 0, n * 6)
                val g = gain
                var o = 0
                for (i in 0 until n * 2) {
                    val v = (raw[o].toInt() and 0xff) or ((raw[o + 1].toInt() and 0xff) shl 8) or (raw[o + 2].toInt() shl 16)
                    fb[i] = (v * g / 8388608f).coerceIn(-1f, 1f)
                    o += 3
                }
                readPos += n
                var off = 0; val total = n * 2
                val t = track
                while (off < total && t != null && !quit && seekTo < 0 && wantPlay) {
                    val w = t.write(fb, off, total - off, AudioTrack.WRITE_NON_BLOCKING)
                    if (w < 0) { wantPlay = false; onEnd(); break }
                    off += w
                    if (w == 0) Thread.sleep(5)
                }
                // interrupted by pause/seek: the rest of this chunk is dropped; pause/seek logic resyncs readPos
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            wantPlay = false; onEnd()
        } finally {
            dropTrack()
            try { raf?.close() } catch (_: Exception) {}
        }
    }

    private fun finish() {
        dropTrack()
        synchronized(lock) { idlePos = info.frames }
        wantPlay = false
        onEnd()
    }
}
