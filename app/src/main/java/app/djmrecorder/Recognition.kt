package app.djmrecorder

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

/** One probe (a 12 s clip at [t] seconds) and what the service said. [status]: match, none, quiet, error, skip. */
class ProbeResult(val t: Int, var status: String = "skip") {
    var artist = ""; var title = ""; var album = ""; var label = ""; var date = ""; var link = ""; var timecode = ""
    var error = ""; var raw = ""

    fun toJson() = JSONObject().apply {
        put("t", t); put("status", status)
        if (artist.isNotEmpty()) put("artist", artist)
        if (title.isNotEmpty()) put("title", title)
        if (album.isNotEmpty()) put("album", album)
        if (label.isNotEmpty()) put("label", label)
        if (date.isNotEmpty()) put("release_date", date)
        if (link.isNotEmpty()) put("song_link", link)
        if (timecode.isNotEmpty()) put("timecode", timecode)
        if (error.isNotEmpty()) put("error", error)
        if (raw.isNotEmpty()) put("raw", raw)
    }

    fun key() = norm(artist, title)

    companion object {
        fun fromJson(o: JSONObject) = ProbeResult(o.getInt("t"), o.optString("status", "skip")).also {
            it.artist = o.optString("artist"); it.title = o.optString("title"); it.album = o.optString("album")
            it.label = o.optString("label"); it.date = o.optString("release_date"); it.link = o.optString("song_link")
            it.timecode = o.optString("timecode"); it.error = o.optString("error"); it.raw = o.optString("raw")
        }
        fun norm(artist: String, title: String) =
            (artist.trim() + "|" + title.trim()).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        /** Statuses a re-run may reuse. Errors and skips are retried. */
        fun cacheable(s: String) = s == "match" || s == "none" || s == "quiet"
    }
}

/** Consecutive probes that returned the same song. */
class Group(val first: Int, var last: Int, val artist: String, val title: String, val link: String, val key: String)

/** Which probes to send for a recording. */
object Planner {
    const val CLIP_SEC = 12
    const val STEP_SEC = 90
    const val MIX_SKIP_SEC = 20     // after a transition the new track is usually still blended with the old one
    const val START_SKIP_SEC = 3
    const val TAIL_SEC = 4          // keep clear of the next transition

    /**
     * Cuts the recording at the [transitions] (seconds) and probes every segment every [STEP_SEC]: the first probe
     * [MIX_SKIP_SEC] after the cut, the last one ending [TAIL_SEC] before the next cut. Segments too short for that get
     * one probe in the middle, or none below 20 s. Returns sorted whole seconds.
     */
    fun plan(durSec: Double, transitions: List<Double>): List<Int> {
        val cuts = ArrayList<Double>()
        for (t in transitions.sorted()) if (t > 1.0 && t < durSec - 5.0 && (cuts.isEmpty() || t - cuts.last() >= 3.0)) cuts.add(t)
        val starts = listOf(0.0) + cuts
        val ends = cuts + durSec
        val out = ArrayList<Int>()
        for (i in starts.indices) {
            val s = starts[i]; val e = ends[i]
            val first = s + if (i == 0) START_SKIP_SEC else MIX_SKIP_SEC
            val lastStart = e - CLIP_SEC - TAIL_SEC
            if (lastStart >= first) {
                var t = first
                while (t <= lastStart) { out.add(t.toInt()); t += STEP_SEC }
            } else if (e - s >= CLIP_SEC + 8) {
                out.add((s + (e - s - CLIP_SEC) / 2).toInt())
            }
        }
        return out.distinct().sorted()
    }
}

/** `<name>.recognize.json`: every probe time with the response, so a re-run can skip answered probes. */
object RecogCache {
    fun file(wav: File) = File(wav.parentFile, Rec.base(wav) + ".recognize.json")

    fun load(wav: File, provider: String): TreeMap<Int, ProbeResult> {
        val m = TreeMap<Int, ProbeResult>()
        try {
            val f = file(wav)
            if (!f.exists()) return m
            val j = JSONObject(f.readText())
            if (j.optString("provider") != provider) return m
            val a = j.optJSONArray("probes") ?: return m
            for (i in 0 until a.length()) { val p = ProbeResult.fromJson(a.getJSONObject(i)); m[p.t] = p }
        } catch (_: Exception) {}
        return m
    }

    fun save(wav: File, provider: String, m: Map<Int, ProbeResult>) {
        try {
            val a = JSONArray()
            for (p in m.values) a.put(p.toJson())
            val j = JSONObject().put("version", 1).put("provider", provider).put("clip_sec", Planner.CLIP_SEC).put("probes", a)
            val f = file(wav)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(j.toString(1))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) {}
    }

    /** The saved song link for an artist/title pair, or "". */
    fun findLink(wav: File, artist: String, title: String): String {
        val key = ProbeResult.norm(artist, title)
        return load(wav, "audd").values.firstOrNull { it.status == "match" && it.key() == key && it.link.isNotEmpty() }?.link ?: ""
    }
}

object Merger {
    /** Matches in time order; probes of the same song that follow each other (unmatched probes in between are ignored) become one group. */
    fun group(results: Collection<ProbeResult>): List<Group> {
        val out = ArrayList<Group>()
        for (r in results.sortedBy { it.t }) {
            if (r.status != "match") continue
            val last = out.lastOrNull()
            if (last != null && last.key == r.key()) { last.last = r.t; continue }
            out.add(Group(r.t, r.t, r.artist, r.title, r.link, r.key()))
        }
        return out
    }

    class Stats { var filled = 0; var added = 0 }

    /**
     * Writes the groups into the tracklist file. Entries from the user (manual, link) are never touched; empty midi entries get
     * their name filled in (source becomes "audio", time and deck stay); other groups become new audio entries at their first probe.
     * Audio entries without a deck from an earlier run are replaced.
     */
    fun apply(wav: File, groups: List<Group>, results: Collection<ProbeResult>): Stats = synchronized(TrackFile) {
        val list = TrackFile.load(wav)
        list.removeAll { it.source == "audio" && it.deck == 0 }
        val probes = results.sortedBy { it.t }
        val used = HashSet<TrackEntry>()
        val st = Stats()
        for (g in groups) {
            var t = list.filter { it.sec <= g.first + 2.0 }.maxByOrNull { it.sec }
            if (t != null && t in used) t = null
            if (t != null) {
                val gap = g.first - t.sec
                val ts = t.sec
                // 60 s as asked; up to 150 s when the probes in between found nothing (the first probe missed the song)
                val ok = gap <= 60.0 || (gap <= 150.0 && probes.none { it.t > ts && it.t < g.first && it.status == "match" })
                if (!ok) t = null
            }
            if (t != null) {
                used.add(t)
                if (!t.hasName() && (t.source == "midi" || t.source == "audio")) {
                    t.artist = g.artist; t.title = g.title; t.source = "audio"; st.filled++
                }
                continue
            }
            if (list.any { it.hasName() && ProbeResult.norm(it.artist, it.title) == g.key && abs(it.sec - g.first) < 120.0 }) continue
            val e = TrackEntry(g.first.toDouble(), 0, g.artist, g.title, "audio")
            list.add(e); used.add(e); st.added++
        }
        TrackFile.save(wav, list)
        st
    }
}

/** Registry of running jobs and the entry points used by the UI and the auto-run. */
object Recognition {
    val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()     // one job at a time, so one request in flight
    private val jobs = ConcurrentHashMap<String, RecognizeJob>()
    /** Set by [RecognizeService]; called on the main thread after every job update. */
    @Volatile var hook: ((RecognizeJob) -> Unit)? = null

    fun get(wav: File): RecognizeJob? = jobs[wav.absolutePath]
    fun active(): Int = jobs.size
    fun cancelAll() { for (j in jobs.values) j.cancel() }

    /** Queues a job for [wav] (or returns the one already running) and starts the foreground service that shows its notification. */
    fun launch(ctx: Context, wav: File, simulate: Boolean, auto: Boolean = false): RecognizeJob {
        val app = ctx.applicationContext
        val job: RecognizeJob
        synchronized(this) {
            val cur = jobs[wav.absolutePath]
            if (cur != null) return cur
            job = RecognizeJob(app, wav, simulate, auto)
            jobs[wav.absolutePath] = job
            exec.execute(job)
        }
        try {
            val i = Intent(app, RecognizeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (_: Exception) { /* no notification then; the job still runs */ }
        return job
    }

    fun finished(job: RecognizeJob) { jobs.remove(job.wav.absolutePath) }

    fun changed(job: RecognizeJob) { main.post { job.listener?.invoke(job); hook?.invoke(job) } }

    /** True on an unmetered network (Wi-Fi without a data limit). */
    fun unmetered(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: Exception) { false }

    /** Called after a recording was finalized: starts a job when the setting is on, a token is set and the network is unmetered. */
    fun maybeAuto(ctx: Context, wav: File) {
        try {
            val s = Settings(ctx)
            if (!s.autoRecognize || s.auddToken.isBlank() || !wav.exists()) return
            if (!unmetered(ctx)) return
            if ((WavInfo.read(wav)?.seconds ?: 0.0) < 45.0) return
            launch(ctx, wav, simulate = false, auto = true)
        } catch (_: Exception) {}
    }

    class Plan(val total: Int, val cached: Int) { val fresh get() = total - cached }

    /** What a job would do right now (reads the tracklist and the cache; call off the main thread). */
    fun plan(wav: File, info: WavInfo, simulate: Boolean): Plan {
        val trans = transitions(TrackFile.load(wav))
        val p = Planner.plan(info.seconds, trans)
        val cache = if (simulate) null else RecogCache.load(wav, "audd")
        val cached = if (cache == null) 0 else p.count { cache[it]?.let { r -> ProbeResult.cacheable(r.status) } == true }
        return Plan(p.size, cached)
    }

    /** Entries that mark where a track starts: everything except what an earlier audio run produced on its own. */
    fun transitions(list: List<TrackEntry>): List<Double> =
        list.filter { !(it.source == "audio" && it.deck == 0) }.map { it.sec }

    /** Checks a token with one tiny request (1.5 s of silence). Blocking; returns a message for the user. */
    fun testToken(token: String): String {
        if (token.isBlank()) return "Enter a token first."
        return try {
            val r = AuddRecognizer(token).recognize(ClipEncoder.wav16(FloatArray((ClipEncoder.OUT_RATE * 1.5).toInt())))
            if (r.error != null) "Token accepted (the test clip itself was rejected: ${r.error}). This used 1 request."
            else "Token accepted. This used 1 request of your AudD quota."
        } catch (e: RecognizerFatal) {
            "Token problem: ${e.message}"
        } catch (e: IOException) {
            "Could not reach AudD (${e.message}). Check the internet connection."
        } catch (e: Exception) {
            "Test failed: ${e.message}"
        }
    }
}

/** One recognition run over one recording. Runs on [Recognition]'s executor; [listener] is called on the main thread. */
class RecognizeJob(private val ctx: Context, val wav: File, val simulate: Boolean, val auto: Boolean) : Runnable {
    @Volatile var phase = "queued"          // queued, running, done
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var found = 0
    @Volatile var sent = 0
    @Volatile var fatal = false
    @Volatile var cancelled = false
    @Volatile var summary = ""
    @Volatile var listener: ((RecognizeJob) -> Unit)? = null
    @Volatile private var recognizer: Recognizer? = null

    fun cancel() { cancelled = true; recognizer?.abort() }

    private fun changed() = Recognition.changed(this)

    override fun run() {
        try {
            if (cancelled) summary = "Cancelled." else runInner()
        } catch (e: Throwable) {
            fatal = true; summary = "Failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            phase = "done"
            Recognition.finished(this)
            changed()
        }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun runInner() {
        phase = "running"; changed()
        val info = WavInfo.read(wav) ?: run { fatal = true; summary = "Cannot read the recording."; return }
        val plan = Planner.plan(info.seconds, Recognition.transitions(TrackFile.load(wav)))
        total = plan.size
        val provider = "audd"
        val token = Settings(ctx).auddToken
        val rec: Recognizer? = if (simulate) null else AuddRecognizer(token).also { recognizer = it }
        if (!simulate && token.isBlank()) { fatal = true; summary = "No AudD token set (Settings > Track recognition)."; return }

        val cache: TreeMap<Int, ProbeResult> = if (simulate) TreeMap() else RecogCache.load(wav, provider)
        val results = TreeMap<Int, ProbeResult>()           // this run's plan: cached answers first, then new ones
        val todo = ArrayList<Int>()
        for (t in plan) {
            val c = cache[t]
            if (c != null && ProbeResult.cacheable(c.status)) results[t] = c else todo.add(t)
        }
        done = results.size
        found = Merger.group(results.values).size
        changed()

        var lastReq = 0L
        var netFails = 0
        var stopMsg: String? = null
        var sinceSave = 0
        for (t in todo) {
            if (cancelled) break
            val r = ProbeResult(t)
            if (simulate) {
                sleep(120)
                fake(r)
            } else {
                val clip = try { ClipEncoder.encode(info, t, Planner.CLIP_SEC) } catch (e: Exception) { r.error = e.message ?: "read error"; null }
                if (clip == null) {
                    r.status = if (r.error.isEmpty()) "skip" else "error"
                } else if (clip.rmsDb < QUIET_DB) {
                    r.status = "quiet"; r.error = "%.1f dBFS".format(Locale.US, clip.rmsDb)
                } else {
                    var reply: Reply? = null
                    var ioMsg = ""
                    for (attempt in 0..1) {
                        if (cancelled) break
                        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastReq)
                        if (wait > 0) sleep(wait)
                        try {
                            reply = rec!!.recognize(clip.bytes)
                            lastReq = System.currentTimeMillis(); sent++
                            break
                        } catch (e: RecognizerFatal) {
                            lastReq = System.currentTimeMillis(); stopMsg = e.message ?: "token or quota error"; break
                        } catch (e: IOException) {
                            lastReq = System.currentTimeMillis(); ioMsg = e.message ?: "network error"
                            if (attempt == 0 && !cancelled) sleep(1500)
                        }
                    }
                    if (stopMsg != null || cancelled) break
                    if (reply == null) {
                        r.status = "error"; r.error = ioMsg
                        if (++netFails >= 3) { stopMsg = "No connection to AudD ($ioMsg)"; results[t] = r; break }
                    } else {
                        netFails = 0
                        r.raw = reply.raw
                        val m = reply.match
                        if (m != null) {
                            r.status = "match"; r.artist = m.artist; r.title = m.title; r.album = m.album; r.label = m.label
                            r.date = m.date; r.link = m.link; r.timecode = m.timecode
                        } else if (reply.error != null) { r.status = "error"; r.error = reply.error } else r.status = "none"
                    }
                }
            }
            results[t] = r
            if (ProbeResult.cacheable(r.status)) cache[t] = r
            done = results.size
            found = Merger.group(results.values).size
            changed()
            if (!simulate && ++sinceSave >= 10) { sinceSave = 0; RecogCache.save(wav, provider, cache) }
        }
        if (!simulate) RecogCache.save(wav, provider, cache)

        val groups = Merger.group(results.values)
        val st = Merger.apply(wav, groups, results.values)
        val quiet = results.values.count { it.status == "quiet" }
        val errs = results.values.count { it.status == "error" }
        val head = when {
            stopMsg != null -> { fatal = true; "Stopped: $stopMsg." }
            cancelled -> "Cancelled."
            else -> "Done."
        }
        val cachedN = plan.size - todo.size
        summary = head + " " + (if (stopMsg != null || cancelled) "Partial results saved: " else "") +
            "${groups.size} track${if (groups.size == 1) "" else "s"} identified (${st.added} added, ${st.filled} filled in)" +
            " from ${results.size}/${plan.size} probes · $sent request${if (sent == 1) "" else "s"}" +
            (if (cachedN > 0) ", $cachedN cached" else "") + (if (quiet > 0) ", $quiet too quiet" else "") +
            (if (errs > 0) ", $errs failed" else "") + (if (simulate) " (simulated)" else "") + "."
    }

    /** Demo path: invented results so the UI can be seen without a token. */
    private fun fake(r: ProbeResult) {
        val idx = r.t / 200
        if (r.t % 7 == 3) { r.status = "none"; return }
        r.status = "match"; r.artist = DEMO_ARTISTS[idx % DEMO_ARTISTS.size]; r.title = DEMO_TITLES[idx % DEMO_TITLES.size]
        r.link = "https://audd.io/"; r.timecode = "00:%02d".format(Locale.US, r.t % 60)
    }

    companion object {
        const val QUIET_DB = -40.0
        const val MIN_GAP_MS = 300L
        private val DEMO_ARTISTS = listOf("Demo Artist", "Sample Crew", "Test Pilot", "Mock Collective", "Fake Fader", "Dummy Dub")
        private val DEMO_TITLES = listOf("Simulated Track", "Placeholder Roller", "Not A Real Song", "Fake Neurofunk", "Demo Liquid", "Lorem Ipsum Dub")
    }
}
