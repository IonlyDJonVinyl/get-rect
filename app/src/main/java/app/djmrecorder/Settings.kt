package app.djmrecorder

import android.content.Context

/** User settings, persisted in SharedPreferences ("djm"). Read fresh on every access so the service and the UI always agree. */
class Settings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("djm", Context.MODE_PRIVATE)

    private fun gi(k: String, d: Int) = try { p.getInt(k, d) } catch (_: Exception) { d }
    private fun gb(k: String, d: Boolean) = try { p.getBoolean(k, d) } catch (_: Exception) { d }
    private fun gs(k: String, d: String) = try { p.getString(k, d) ?: d } catch (_: Exception) { d }
    private fun pi(k: String, v: Int) { try { p.edit().putInt(k, v).apply() } catch (_: Exception) {} }
    private fun pb(k: String, v: Boolean) { try { p.edit().putBoolean(k, v).apply() } catch (_: Exception) {} }
    private fun ps(k: String, v: String) { try { p.edit().putString(k, v).apply() } catch (_: Exception) {} }

    var level: Int get() = gi("level", 2).coerceIn(0, 3); set(v) = pi("level", v)
    var rate: Int get() = gi("rate", 48000).let { if (it in RecordService.RATES) it else 48000 }; set(v) = pi("rate", v)
    var stems: Boolean get() = gb("stems", true); set(v) = pb("stems", v)
    var feedback: Boolean get() = gb("feedback", false); set(v) = pb("feedback", v)

    /**
     * Bit depth shown on the record screen: 24 (capture and export default) or 16. Capture is always 24-bit
     * (the writer is untouched); 16 only makes the detail screen default to a dithered 16-bit export.
     */
    var bitDepth: Int get() = gi("bitdepth", 24).let { if (it == 16) 16 else 24 }; set(v) = pi("bitdepth", v)

    /** Pre-roll length in seconds: 0 (off), 15, 30 or 60. */
    var preRollSec: Int get() = gi("preroll", 30).let { if (it in PRE_OPTIONS) it else 30 }; set(v) = pi("preroll", v)
    var autoStart: Boolean get() = gb("autostart", false); set(v) = pb("autostart", v)
    /** Auto-stop after this many minutes below -60 dBFS: 0 (off), 1, 5, 10 or 15. */
    var autoStopMin: Int get() = gi("autostop", 0).let { if (it in STOP_OPTIONS) it else 0 }; set(v) = pi("autostop", v)
    var keepScreenOn: Boolean get() = gb("keepscreen", false); set(v) = pb("keepscreen", v)
    /** When the mixer is replugged: fill the unplugged time with silence (real-time timeline) or just continue. */
    var keepGaps: Boolean get() = gb("keepgaps", false); set(v) = pb("keepgaps", v)
    /** Diagnostics: switch to RF64 after 50 MB instead of 4 GB, to test the RF64 path on a phone. */
    var rf64Test: Boolean get() = gb("rf64test", false); set(v) = pb("rf64test", v)
    /** Show the diagnostics log viewer in Settings. */
    var showDiag: Boolean get() = gb("showdiag", false); set(v) = pb("showdiag", v)

    /** Play the REC OUT pair live through the phone's audio output. */
    var monitor: Boolean get() = gb("monitor", false); set(v) = pb("monitor", v)
    /** Track fader moves over the mixer's USB-MIDI interface (needs the MIDI ON button on the mixer). */
    var midiTrack: Boolean get() = gb("miditrack", true); set(v) = pb("miditrack", v)
    /** Join the Pro DJ Link network as a virtual CDJ to read track titles (needs CDJs on the same network). */
    var cdjLink: Boolean get() = gb("cdjlink", false); set(v) = pb("cdjlink", v)
    /** AudD API token for after-the-set track recognition (https://audd.io). */
    var auddToken: String get() = gs("auddtoken", "").trim(); set(v) = ps("auddtoken", v.trim())
    /** Identify tracks automatically after each recording, on an unmetered network only. */
    var autoRecognize: Boolean get() = gb("autorecog", false); set(v) = pb("autorecog", v)

    /** Stable locally administered MAC used for the virtual CDJ, "02:xx:xx:xx:xx:xx". */
    val linkMac: String get() {
        val cur = gs("linkmac", "")
        if (cur.length == 17) return cur
        val r = java.util.Random()
        val m = "02:%02x:%02x:%02x:%02x:%02x".format(r.nextInt(256), r.nextInt(256), r.nextInt(256), r.nextInt(256), r.nextInt(256))
        ps("linkmac", m)
        return m
    }

    /** Pre-roll actually used: auto-start needs some pre-roll to keep the 2 s that trigger it. */
    fun effectivePreSec(): Int = if (autoStart && preRollSec < 5) 5 else preRollSec

    companion object {
        val PRE_OPTIONS = intArrayOf(0, 15, 30, 60)
        val STOP_OPTIONS = intArrayOf(0, 1, 5, 10, 15)
    }
}
