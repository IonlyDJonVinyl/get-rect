package app.djmrecorder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process

/**
 * Plays the REC OUT pair live through the phone's audio output. The native capture thread copies each stereo frame
 * into a float ring (nativeMonitor); this thread drains it into a low-latency AudioTrack. The path adds the USB
 * packet time, the AudioTrack buffer and the phone's output latency, so it is for checking, not for cueing.
 * The ring reader skips ahead when more than 100 ms are queued (the two clocks drift).
 */
class Monitor(private val rate: Int, private val log: (String) -> Unit) {
    @Volatile private var run = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start(): Boolean {
        if (run) return true
        val t = try {
            val ch = AudioFormat.CHANNEL_OUT_STEREO
            val enc = AudioFormat.ENCODING_PCM_FLOAT
            val min = AudioTrack.getMinBufferSize(rate, ch, enc)
            val size = maxOf(if (min > 0) min * 2 else 0, rate / 50 * 8)   // at least 20 ms of float stereo
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(enc).setSampleRate(rate).setChannelMask(ch).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(size)
                .build()
        } catch (e: Exception) { log("monitor: cannot open audio output: ${e.message}"); return false }
        track = t
        Native.nativeMonitor(1)
        run = true
        thread = Thread({ loop(t) }, "monitor").also { it.isDaemon = true; it.start() }
        log("monitor on (${rate} Hz, buffer ${t.bufferSizeInFrames} frames)")
        return true
    }

    private fun loop(t: AudioTrack) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        val buf = FloatArray(2048 * 2)
        try {
            t.play()
            while (run) {
                val n = Native.nativeMonitorRead(buf, 2048, rate / 10)
                if (n <= 0) { Thread.sleep(2); continue }
                var off = 0; val total = n * 2
                while (off < total && run) {
                    val w = t.write(buf, off, total - off, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) { log("monitor: audio write error $w"); run = false; break }
                    off += w
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) { log("monitor: ${e.message}") }
        finally {
            try { t.pause(); t.flush() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }
    }

    fun stop() {
        val th = thread
        if (!run && th == null) return
        run = false
        try { Native.nativeMonitor(0) } catch (_: Exception) {}
        try { th?.join(800) } catch (_: Exception) {}
        thread = null; track = null
        log("monitor off")
    }
}
