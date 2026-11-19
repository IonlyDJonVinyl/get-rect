package app.djmrecorder

import android.os.Handler
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Live tracklist of the running recording (main thread only). MIDI fader transitions and Pro DJ Link track
 * identifications are merged: a "link" entry (has a title) wins over a "midi" entry for the same deck within [MERGE_SEC].
 */
class TrackLog(private val onChanged: () -> Unit) {
    val entries = ArrayList<TrackEntry>()

    fun clear() { entries.clear() }

    private fun sort() { entries.sortBy { it.sec } }

    /** A fader transition: deck [deck] became the audible one at [sec]. Returns the entry, or null if a link entry already covers it. */
    fun addMidi(sec: Double, deck: Int): TrackEntry? {
        val t = maxOf(0.0, sec)
        for (e in entries) if (e.deck == deck && abs(e.sec - t) < MERGE_SEC && (e.source == "link" || e.source == "midi")) return null
        val e = TrackEntry(t, deck, "", "", "midi")
        entries.add(e); sort(); onChanged()
        return e
    }

    /** A Pro DJ Link identification. Upgrades a nearby midi entry of the same deck instead of adding a second line. */
    fun addLink(sec: Double, deck: Int, artist: String, title: String): TrackEntry {
        val t = maxOf(0.0, sec)
        val near = entries.firstOrNull { it.deck == deck && it.source == "midi" && abs(it.sec - t) < MERGE_SEC }
        if (near != null) {
            near.artist = artist; near.title = title; near.source = "link"; near.sec = minOf(near.sec, t)
            sort(); onChanged()
            return near
        }
        val e = TrackEntry(t, deck, artist, title, "link")
        entries.add(e); sort(); onChanged()
        return e
    }

    fun updateMeta(e: TrackEntry, artist: String, title: String) {
        if (e.artist == artist && e.title == title) return
        e.artist = artist; e.title = title
        onChanged()
    }

    companion object { const val MERGE_SEC = 30.0 }
}

/**
 * Infers which mixer channel is audible from the DJM's USB-MIDI controls (docs/djm900nxs2-midi.md, section 6).
 * Channel faders CC 0x11-0x14, crossfader CC 0x0B, crossfader assign CC 0x41-0x44 (0x00 = A, 0x40 = THRU, 0x7F = B).
 * A channel is "audible" above a fader threshold with hysteresis and when the crossfader lets it through.
 * A change only counts once it has lasted [HOLD_MS], so scrubbing a fader does not create events.
 * All methods run on the main thread; [onMix] gets (deck in, deck out or 0, SystemClock.elapsedRealtime() of the rise).
 */
class FaderTracker(private val ui: Handler, private val onMix: (Int, Int, Long) -> Unit) {
    private val fader = IntArray(4) { -1 }
    private val assign = IntArray(4) { 1 }        // 0 = A, 1 = THRU, 2 = B
    private var xf = 64
    private val raw = BooleanArray(4)
    private val conf = BooleanArray(4)
    private val since = LongArray(4)
    @Volatile var seen = false; private set
    @Volatile var lastCcAt = 0L; private set
    /** Deck (1-4) that most recently became audible and is still, or 0. */
    var current = 0; private set

    private val check = Runnable { evaluate() }

    /** Audible per the faders right now (no debounce), or null while nothing was received from that channel yet. */
    fun isAudible(deck: Int): Boolean? {
        if (!seen || deck !in 1..4 || fader[deck - 1] < 0) return null
        return raw[deck - 1]
    }

    fun cc(num: Int, v: Int) {
        val now = SystemClock.elapsedRealtime()
        when (num) {
            in 0x11..0x14 -> fader[num - 0x11] = v
            0x0B -> xf = v
            in 0x41..0x44 -> assign[num - 0x41] = when { v < 0x20 -> 0; v > 0x60 -> 2; else -> 1 }
            else -> return
        }
        seen = true; lastCcAt = now
        var changed = false
        for (d in 0..3) {
            val a = compute(d)
            if (a != raw[d]) { raw[d] = a; since[d] = now; changed = true }
        }
        if (changed) ui.postDelayed(check, HOLD_MS + 100)
    }

    private fun compute(d: Int): Boolean {
        val f = fader[d]
        if (f < 0) return false
        val pass = when (assign[d]) { 1 -> true; 0 -> xf < 100; else -> xf > 27 }
        return pass && (if (raw[d]) f >= 8 else f >= 22)
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        for (d in 0..3) {
            if (raw[d] != conf[d] && now - since[d] >= HOLD_MS) {
                conf[d] = raw[d]
                if (conf[d]) {
                    val prev = current
                    current = d + 1
                    onMix(d + 1, if (prev in 1..4 && prev != d + 1) prev else 0, since[d])
                } else if (current == d + 1) {
                    var best = -1; var bf = -1
                    for (k in 0..3) if (conf[k] && fader[k] > bf) { best = k; bf = fader[k] }
                    current = best + 1
                }
            }
        }
    }

    fun stop() { ui.removeCallbacks(check) }

    companion object { const val HOLD_MS = 8000L }
}

/** Text builders for the tracklist exports (CUE sheet, Mixcloud, caption). Times are relative to the trim start. */
object TrackExport {
    /** Entries inside [startSec, endSec) rebased to the trim start. The track that is already playing at IN becomes time 0. */
    fun inRange(all: List<TrackEntry>, startSec: Double, endSec: Double): List<TrackEntry> {
        val sorted = all.sortedBy { it.sec }
        val out = ArrayList<TrackEntry>()
        val before = sorted.lastOrNull { it.sec <= startSec + 0.05 }
        val inside = sorted.filter { it.sec > startSec + 0.05 && it.sec < endSec - 0.05 }
        if (before != null) out.add(before.copy().also { it.sec = 0.0 })
        for (e in inside) out.add(e.copy().also { it.sec = e.sec - startSec })
        return out
    }

    private fun mmss(sec: Double): String {
        val s = sec.roundToInt().coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60)
        else "%02d:%02d".format(Locale.US, s / 60, s % 60)
    }

    private fun cueTime(sec: Double): String {
        val frames = (sec * 75).roundToInt().coerceAtLeast(0)
        return "%02d:%02d:%02d".format(Locale.US, frames / 75 / 60, frames / 75 % 60, frames % 75)
    }

    private fun q(s: String) = s.replace('"', '\'')

    fun cue(list: List<TrackEntry>, mixTitle: String, audioName: String): String {
        val sb = StringBuilder()
        sb.append("TITLE \"").append(q(mixTitle)).append("\"\nFILE \"").append(q(audioName)).append("\" WAVE\n")
        list.forEachIndexed { i, e ->
            sb.append("  TRACK %02d AUDIO\n".format(Locale.US, i + 1))
            sb.append("    TITLE \"").append(q(e.title.ifBlank { e.label() })).append("\"\n")
            if (e.artist.isNotBlank()) sb.append("    PERFORMER \"").append(q(e.artist)).append("\"\n")
            sb.append("    INDEX 01 ").append(cueTime(e.sec)).append('\n')
        }
        return sb.toString()
    }

    fun mixcloud(list: List<TrackEntry>): String =
        list.joinToString("\n") { "${mmss(it.sec)} ${if (it.hasName()) it.label() else "Unknown track"}" }

    fun caption(list: List<TrackEntry>, mixTitle: String): String {
        val sb = StringBuilder()
        sb.append(mixTitle).append("\n\nTracklist\n")
        list.forEachIndexed { i, e -> sb.append("${i + 1}. ${mmss(e.sec)} ${if (e.hasName()) e.label() else "Unknown track"}\n") }
        return sb.toString().trimEnd()
    }
}
