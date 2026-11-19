package app.djmrecorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Recording detail: waveforms and transport on top, then four sections (segmented control):
 * Edit (trim, gain), Tracks (tracklist and its exports), Export (formats, stems) and Info.
 */
class DetailActivity : Activity() {
    companion object {
        const val EXTRA_PATH = "path"
        private const val REQ_STORAGE = 77
        private const val SILENCE_DB = -50.0
    }

    private lateinit var wav: File
    private lateinit var info: WavInfo
    private lateinit var settings: Settings
    private var player: Player? = null
    private var wave: WaveData? = null
    private val markers = ArrayList<Double>()
    private val tracks = ArrayList<TrackEntry>()
    private var events: List<DropEvent> = emptyList()
    private var durSec = 0.0
    private var inSec = 0.0
    private var outSec = 0.0
    private var gainDb = 0f
    private var trimPeak = -2f           // linear; <0 = unknown/scanning
    private var scanGen = 0
    private var pendingNormalize = false
    private var fmt = Fmt.WAV24
    private var fade = false
    private var analyzing = false
    private var destroyed = false
    @Volatile private var deleting = false
    @Volatile private var cancelExport = false
    private var exporting = false
    private var lastUri: Uri? = null
    private var lastMime = "audio/x-wav"
    private var pendingAction: (() -> Unit)? = null
    private var previewKind = -1
    private var previewText = ""

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val scanRunnable = Runnable { startPeakScan() }

    private lateinit var titleT: TextView
    private lateinit var infoT: TextView
    private lateinit var evT: TextView
    private lateinit var ov: OverviewView
    private lateinit var zoom: ZoomView
    private lateinit var zoomT: TextView
    private lateinit var timeT: TextView
    private lateinit var playB: ImageView
    private lateinit var trimT: TextView
    private lateinit var gainBar: SeekBar
    private lateinit var gainT: TextView
    private lateinit var peakT: TextView
    private lateinit var fmtChips: List<TextView>
    private lateinit var exportB: TextView
    private lateinit var stemsB: TextView
    private lateinit var prog: ProgressBar
    private lateinit var resultT: TextView
    private lateinit var shareB: TextView
    private lateinit var infoBody: TextView
    private lateinit var segs: List<TextView>
    private lateinit var panes: List<View>
    private lateinit var trackList: LinearLayout
    private lateinit var previewCard: LinearLayout
    private lateinit var previewT: TextView
    private lateinit var saveCueB: TextView
    private lateinit var idStatus: TextView
    private lateinit var idBar: ProgressBar
    private lateinit var idBtn: TextView
    private lateinit var idCancel: TextView
    private var idJob: RecognizeJob? = null

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        settings = Settings(this)
        val path = intent.getStringExtra(EXTRA_PATH)
        val f = path?.let { File(it) }
        val wi = f?.let { WavInfo.read(it) }
        if (f == null || wi == null) { Toast.makeText(this, "Cannot read this recording", Toast.LENGTH_LONG).show(); finish(); return }
        wav = f; info = wi
        durSec = info.seconds; inSec = 0.0; outSec = durSec
        fmt = if (settings.bitDepth == 16) Fmt.WAV16 else Fmt.WAV24
        markers.addAll(Rec.loadMarkers(wav))
        tracks.addAll(TrackFile.load(wav))
        events = Rec.loadEvents(wav)
        buildUi()
        refreshInfo(); refreshTrim(); refreshGain(); refreshMarkers(); refreshTracks()
        ov.events = events; zoom.events = events
        player = Player(info) { ui.post { if (!destroyed) updatePlayBtn() } }.also { it.start() }
        Recognition.get(wav)?.let { bindJob(it) }
        loadWave()
        startPeakScan()
        posTick()
    }

    override fun onPause() { player?.pause(); super.onPause() }

    override fun onDestroy() {
        destroyed = true; cancelExport = true; scanGen++
        idJob?.listener = null
        ui.removeCallbacksAndMessages(null)
        player?.release()
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- ui ----------
    private fun weighted(v: View, left: Int = 0) = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = left }

    private fun section(title: String): LinearLayout = card().apply { addView(label(title)) }

    private fun hrow(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG); setPadding(px(20f), 0, px(20f), 0) }

        // header
        val head = hrow()
        head.addView(iconButton(R.drawable.ic_back, C.TEXT, 40f) { finish() }, LinearLayout.LayoutParams(px(40f), px(40f)).apply { leftMargin = -px(8f); rightMargin = px(6f) })
        titleT = tv("", 18f, C.TEXT, Fonts.LIGHT).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        head.addView(titleT, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(iconButton(R.drawable.ic_edit, C.TEXT, 40f) { renameDialog() })
        root.addView(head, lp(-1, px(52f)))
        infoT = tv("", 12f, C.DIM)
        root.addView(infoT)
        evT = tv("", 12f, C.GREEN, Fonts.MED)
        root.addView(evT, lp(top = px(2f)))

        // waveforms
        ov = OverviewView(this).apply {
            durationSec = this@DetailActivity.durSec
            totalEntries = (durSec * 100).toInt()
            onSeek = { s -> seekSec(s) }
            onTrim = { i, o -> setTrim(i, o) }
        }
        root.addView(ov, lp(-1, px(72f), top = px(10f)))
        val zh = hrow()
        zh.addView(label("Zoom"), LinearLayout.LayoutParams(0, -2, 1f))
        zoomT = tv("", 12f, C.DIM, Fonts.MONO)
        zh.addView(zoomT, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(8f) })
        zh.addView(pillButton("−") { zoom.windowSec = zoom.windowSec * 1.5f; zoomChanged() }.apply { setPadding(px(14f), px(4f), px(14f), px(4f)) },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(6f) })
        zh.addView(pillButton("+") { zoom.windowSec = zoom.windowSec / 1.5f; zoomChanged() }.apply { setPadding(px(14f), px(4f), px(14f), px(4f)) })
        root.addView(zh, lp(top = px(10f)))
        zoom = ZoomView(this).apply { onScrub = { s -> seekSec(s) }; onWindow = { zoomChanged() } }
        root.addView(zoom, lp(-1, px(92f), top = px(6f)))

        // transport
        val tr = hrow()
        timeT = tv("", 15f, C.TEXT, Fonts.MONO)
        tr.addView(timeT, LinearLayout.LayoutParams(0, -2, 1f))
        tr.addView(iconButton(R.drawable.ic_skip_prev, C.TEXT, 42f) { jumpMarker(-1) })
        playB = ImageView(this).apply {
            setImageResource(R.drawable.ic_play); setColorFilter(0xFFFFFFFF.toInt()); scaleType = ImageView.ScaleType.FIT_CENTER
            val p = px(13f); setPadding(p, p, p, p)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.RED) }
            setOnClickListener { togglePlay() }
        }
        tr.addView(playB, LinearLayout.LayoutParams(px(52f), px(52f)).apply { leftMargin = px(4f); rightMargin = px(4f) })
        tr.addView(iconButton(R.drawable.ic_skip_next, C.TEXT, 42f) { jumpMarker(1) })
        tr.addView(iconButton(R.drawable.ic_marker, C.TEXT, 42f) { addMarkerHere() })
        root.addView(tr, lp(top = px(6f)))

        // segmented control
        val names = listOf("Edit", "Tracks", "Export", "Info")
        val sg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = bg(C.CARD, 14f, C.LINE); setPadding(px(3f), px(3f), px(3f), px(3f)) }
        segs = names.mapIndexed { i, n ->
            tv(n, 13.5f, C.DIM, Fonts.MED).apply { gravity = Gravity.CENTER; setPadding(0, px(9f), 0, px(9f)); setOnClickListener { showPane(i) } }
                .also { sg.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        root.addView(sg, lp(top = px(8f)))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panes = listOf(buildEdit(), buildTracks(), buildExport(), buildInfo())
        for (p in panes) body.addView(p)
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, lp(-1, 0, top = px(10f), weight = 1f))

        root.fitSystemBars()
        setContentView(root)
        showPane(0)
    }

    private fun showPane(i: Int) {
        panes.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
        segs.forEachIndexed { k, t ->
            t.background = if (k == i) bg(C.RED, 11f) else null
            t.setTextColor(if (k == i) 0xFFFFFFFF.toInt() else C.DIM)
        }
    }

    private fun buildEdit(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val trc = section("Trim · drag the green handles")
        trimT = tv("", 13f, C.TEXT, Fonts.MONO)
        trc.addView(trimT, lp(top = px(10f)))
        val r1 = hrow()
        r1.addView(pillButton("Set IN here") { setInHere() }, weighted(View(this)))
        r1.addView(pillButton("Set OUT here") { setOutHere() }, weighted(View(this), px(8f)))
        trc.addView(r1, lp(top = px(12f)))
        val r2 = hrow()
        r2.addView(pillButton("Auto-trim silence") { autoTrim() }, weighted(View(this)))
        r2.addView(pillButton("Reset") { setTrim(0.0, durSec) }, weighted(View(this), px(8f)))
        trc.addView(r2, lp(top = px(8f)))
        col.addView(trc)

        val gc = section("Gain")
        val gh = hrow()
        gh.addView(tv("Level change on export and playback", 12f, C.DIM), LinearLayout.LayoutParams(0, -2, 1f))
        gainT = tv("", 16f, C.TEXT, Fonts.MONO)
        gh.addView(gainT)
        gc.addView(gh, lp(top = px(8f)))
        gainBar = SeekBar(this).apply {
            max = 48
            progressTintList = ColorStateList.valueOf(C.RED); thumbTintList = ColorStateList.valueOf(C.RED)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) { gainDb = p / 2f - 12f; refreshGain() }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        gc.addView(gainBar, lp(top = px(6f)))
        peakT = tv("", 12f, C.DIM)
        gc.addView(peakT, lp(top = px(4f)))
        val gr = hrow()
        gr.addView(pillButton("Normalize to -0.3 dB") { normalize() }, weighted(View(this)))
        gr.addView(pillButton("0 dB") { gainDb = 0f; refreshGain() }, weighted(View(this), px(8f)))
        gc.addView(gr, lp(top = px(12f)))
        col.addView(gc, lp(top = px(10f)))
        col.addView(View(this), lp(-1, px(16f)))
        return col
    }

    private fun buildTracks(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tc = section("Tracklist · tap a line to jump there")
        trackList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tc.addView(trackList, lp(top = px(6f)))
        tc.addView(pillButton("+ Add track at playhead") { editTrack(null) }, lp(top = px(10f)))
        col.addView(tc)

        val ic = section("Identify tracks from the audio")
        ic.addView(tv("Sends short clips of this recording to AudD and fills the tracklist. Your own entries are never overwritten.", 12f, C.DIM).apply { setLineSpacing(0f, 1.1f) }, lp(top = px(6f)))
        idBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            progressTintList = ColorStateList.valueOf(C.RED)
        }
        ic.addView(idBar, lp(-1, px(6f), top = px(10f)))
        idStatus = tv("", 12.5f, C.DIM).apply { setLineSpacing(0f, 1.1f); visibility = View.GONE }
        ic.addView(idStatus, lp(top = px(8f)))
        val ir = hrow()
        idBtn = pillButton("Identify tracks", primary = true) { startIdentify(false) }
        idCancel = pillButton("Cancel") { idJob?.cancel(); idCancel.isEnabled = false }.apply { visibility = View.GONE }
        ir.addView(idBtn, weighted(View(this)))
        ir.addView(idCancel, weighted(View(this), px(8f)))
        ir.addView(pillButton("Simulate") { startIdentify(true) }, weighted(View(this), px(8f)))
        ic.addView(ir, lp(top = px(12f)))
        col.addView(ic, lp(top = px(10f)))

        val xc = section("Export tracklist")
        xc.addView(tv("Times follow the trim (IN to OUT) and the format chosen under Export.", 12f, C.DIM), lp(top = px(6f)))
        val xr = hrow()
        xr.addView(pillButton("CUE sheet") { showPreview(0) }, weighted(View(this)))
        xr.addView(pillButton("Mixcloud") { showPreview(1) }, weighted(View(this), px(8f)))
        xr.addView(pillButton("Caption") { showPreview(2) }, weighted(View(this), px(8f)))
        xc.addView(xr, lp(top = px(12f)))
        previewCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        previewT = tv("", 12f, C.TEXT, Fonts.MONO).apply { setTextIsSelectable(true); background = bg(C.BG, 10f, C.LINE); setPadding(px(10f), px(10f), px(10f), px(10f)) }
        previewCard.addView(previewT, lp(top = px(12f)))
        val pr = hrow()
        pr.addView(pillButton("Copy") { copyText(previewText) }, weighted(View(this)))
        pr.addView(pillButton("Share") { shareText(previewText) }, weighted(View(this), px(8f)))
        saveCueB = pillButton("Save .cue") { saveCue() }
        pr.addView(saveCueB, weighted(View(this), px(8f)))
        previewCard.addView(pr, lp(top = px(10f)))
        xc.addView(previewCard)
        col.addView(xc, lp(top = px(10f)))
        col.addView(View(this), lp(-1, px(16f)))
        return col
    }

    private fun buildExport(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ec = section("Format")
        val fr = hrow()
        fmtChips = Fmt.values().map { f ->
            chip(f.label) { fmt = f; styleFmt() }.apply { setPadding(px(10f), px(9f), px(10f), px(9f)) }
                .also { fr.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(6f) }) }
        }
        styleFmt()
        ec.addView(fr, lp(top = px(10f)))
        ec.addView(switchRow("Fade in / out", "2 seconds at both ends of the trimmed mix", false) { fade = it }, lp(top = px(6f)))
        exportB = pillButton("Export trimmed mix", primary = true) { onExportTap() }
        ec.addView(exportB, lp(top = px(8f)))
        stemsB = pillButton("Export stems (4 decks + master)") { onStemsTap() }
        ec.addView(stemsB, lp(top = px(8f)))
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            progressTintList = ColorStateList.valueOf(C.GREEN)
        }
        ec.addView(prog, lp(top = px(10f)))
        resultT = tv("", 13f, C.DIM)
        ec.addView(resultT, lp(top = px(8f)))
        shareB = pillButton("Share export") { share() }.apply { visibility = View.GONE }
        ec.addView(shareB, lp(top = px(8f)))
        col.addView(ec)
        col.addView(View(this), lp(-1, px(16f)))
        return col
    }

    private fun buildInfo(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ic = section("Recording")
        infoBody = tv("", 13.5f, C.TEXT).apply { setLineSpacing(0f, 1.25f) }
        ic.addView(infoBody, lp(top = px(10f)))
        col.addView(ic)
        col.addView(pillButton("Delete recording") { confirmDelete() }.apply { setTextColor(C.RED); background = ripple(bg(0, 20f, C.RED)) }, lp(top = px(14f)))
        col.addView(View(this), lp(-1, px(24f)))
        return col
    }

    // ---------- refreshers ----------
    private fun refreshInfo() {
        titleT.text = Rec.displayName(Rec.base(wav))
        val st = Rec.stems(wav)
        val hasStems = st.exists()
        stemsB.visibility = if (hasStems) View.VISIBLE else View.GONE
        infoT.text = Rec.date(wav) + " · " + Rec.clock(durSec.toInt()) + " · " + Rec.size(wav.length()) +
            (if (analyzing) "\nAnalyzing waveform…" else "")
        val drops = events.count { !it.reconnect }; val recon = events.count { it.reconnect }
        evT.text = "$drops dropout" + (if (drops == 1) "" else "s") +
            (if (recon > 0) " · $recon reconnect" + (if (recon == 1) "" else "s") else "") +
            (if (drops > 0 || recon > 0) " (red marks)" else "")
        evT.setTextColor(if (drops > 0 || recon > 0) C.RED else C.GREEN)
        infoBody.text = "Date  ${Rec.date(wav)}\nLength  ${Rec.clock(durSec.toInt())}\nFile  ${Rec.size(wav.length())}\n" +
            "Format  %.1f kHz, 24-bit stereo WAV\n".format(info.rate / 1000.0).replace(",", ".") +
            (if (hasStems) "Stems  ${Rec.size(st.length())} (4 decks + master)\n" else "Stems  none\n") +
            "Markers  ${markers.size}\nTracks  ${tracks.size}\nDropouts  $drops, reconnects $recon\n\n${wav.absolutePath}"
    }

    private fun refreshTrim() {
        trimT.text = "IN  ${Rec.clockT(inSec)}   OUT ${Rec.clockT(outSec)}\nLEN ${Rec.clockT(outSec - inSec)}"
        ov.inSec = inSec; ov.outSec = outSec; zoom.inSec = inSec; zoom.outSec = outSec
    }

    private fun refreshMarkers() { ov.markers = markers.toList(); zoom.markers = markers.toList() }

    private fun zoomChanged() { zoomT.text = "%.0f s".format(zoom.windowSec) }

    private fun gainLin() = 10.0.pow(gainDb / 20.0).toFloat()

    private fun refreshGain() {
        gainBar.progress = ((gainDb.coerceIn(-12f, 12f) + 12f) * 2).roundToInt()
        gainT.text = "%+.1f dB".format(gainDb).replace(',', '.')
        player?.gain = gainLin()
        if (trimPeak < 0f) { peakT.text = "Scanning peak in IN–OUT…"; peakT.setTextColor(C.DIM); return }
        val pk = Rec.dbOf(trimPeak)
        val after = pk + gainDb
        if (after > 0.0001f) {
            peakT.text = "CLIPPING: peak would reach %+.1f dBFS (now %.1f)".format(after, pk).replace(',', '.')
            peakT.setTextColor(C.RED)
        } else {
            peakT.text = "Peak %.1f dBFS → %.1f dBFS after gain".format(pk, after).replace(',', '.')
            peakT.setTextColor(C.DIM)
        }
    }

    private fun styleFmt() { Fmt.values().forEachIndexed { i, f -> styleChip(fmtChips[i], fmt == f) } }

    private fun updatePlayBtn() {
        val p = player?.wantPlay == true
        playB.setImageResource(if (p) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ---------- wave loading ----------
    private fun loadWave() {
        analyzing = true; refreshInfo()
        val i = info
        Thread({
            var lastPct = -1
            val d = WaveStore.loadOrBuild(i, { destroyed || deleting }) { f ->
                val pct = (f * 100).toInt()
                if (pct != lastPct) { lastPct = pct; ui.post { if (!destroyed) infoT.text = infoT.text.toString().substringBefore("\nAnalyzing") + "\nAnalyzing waveform… $pct%" } }
            }
            ui.post {
                if (destroyed) return@post
                analyzing = false
                if (d != null) { wave = d; ov.data = d; zoom.data = d }
                else toast("Could not build waveform")
                refreshInfo()
            }
        }, "wave-load").start()
    }

    // ---------- playback ----------
    private fun posTick() {
        if (destroyed) return
        val p = player
        if (p != null) {
            val s = p.position().toDouble() / info.rate
            ov.playSec = s; zoom.playSec = s
            timeT.text = Rec.clockT(s) + " / " + Rec.clockT(durSec)
            updatePlayBtn()
        }
        zoomChanged()
        ui.postDelayed({ posTick() }, 33)
    }

    private fun seekSec(s: Double) {
        val c = s.coerceIn(0.0, durSec)
        player?.seek((c * info.rate).roundToLong())
        ov.playSec = c; zoom.playSec = c
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.wantPlay) p.pause()
        else {
            if (p.position() >= info.frames - 100) p.seek(0)
            p.play()
        }
        updatePlayBtn()
    }

    private fun curSec() = (player?.position() ?: 0L).toDouble() / info.rate

    /** Jumps between markers and track changes (both are cue points). */
    private fun jumpMarker(dir: Int) {
        val now = curSec()
        val pts = (markers + tracks.map { it.sec }).sorted()
        val t = if (dir > 0) pts.firstOrNull { it > now + 0.05 } else pts.lastOrNull { it < now - 0.6 }
        if (t == null) { toast(if (pts.isEmpty()) "No markers yet" else "No more markers that way"); return }
        seekSec(t)
    }

    private fun addMarkerHere() {
        val t = curSec()
        if (markers.any { kotlin.math.abs(it - t) < 0.2 }) return
        markers.add(t); markers.sort()
        refreshMarkers()
        val snapshot = markers.toList(); val w = wav
        worker.execute { Rec.saveMarkers(w, snapshot) }
        toast("Marker at ${Rec.clockT(t)}")
    }

    // ---------- tracklist ----------
    private fun saveTracks() {
        val snap = tracks.map { it.copy() }; val w = wav
        worker.execute { TrackFile.save(w, snap) }
    }

    private fun refreshTracks() {
        tracks.sortBy { it.sec }
        val secs = tracks.map { it.sec }
        ov.tracks = secs; zoom.tracks = secs
        trackList.removeAllViews()
        if (tracks.isEmpty()) {
            trackList.addView(tv("No tracks yet. Fader tracking or CDJ link fill this while recording; Identify tracks below fills it from the audio afterwards; you can also add lines by hand.", 13f, C.DIM)
                .apply { setLineSpacing(0f, 1.15f) }, lp(top = px(4f)))
        }
        for (e in tracks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = ripple(null); setPadding(0, px(8f), 0, px(8f))
                setOnClickListener { seekSec(e.sec) }
            }
            row.addView(tv(Rec.clockT(e.sec), 12.5f, C.CYAN, Fonts.MONO), LinearLayout.LayoutParams(px(62f), -2))
            val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(tv(if (e.hasName()) e.label() else "Deck ${if (e.deck > 0) e.deck else "?"} (no title)", 14f, if (e.hasName()) C.TEXT else C.DIM, Fonts.REG)
                .apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
            if (e.source == "audio") {
                val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                if (e.deck > 0) sub.addView(tv("Deck ${e.deck}  ", 11f, C.DIM))
                sub.addView(tv("AUDIO", 9.5f, C.CYAN, Fonts.MED).apply { setPadding(px(6f), px(2f), px(6f), px(2f)); background = bg(0, 6f, C.CYAN) })
                mid.addView(sub, lp(top = px(3f)))
                row.setOnLongClickListener { openSongLink(e); true }
            } else {
                mid.addView(tv((if (e.deck > 0) "Deck ${e.deck} · " else "") + e.source, 11f, C.DIM), lp(top = px(2f)))
            }
            row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(iconButton(R.drawable.ic_edit, C.DIM, 36f) { editTrack(e) })
            row.addView(iconButton(R.drawable.ic_trash, C.DIM, 36f) {
                tracks.remove(e); saveTracks(); refreshTracks(); refreshInfo(); refreshPreview()
            })
            trackList.addView(row)
            trackList.addView(hline())
        }
        refreshInfo()
    }

    // ---------- recognition ----------
    private fun startIdentify(simulate: Boolean) {
        if (idJob != null) return
        if (!simulate && settings.auddToken.isBlank()) {
            AlertDialog.Builder(this).setTitle("Set up track recognition")
                .setMessage("Track recognition needs an AudD API token. Get one at audd.io, then paste it in Settings (gear icon on the Record tab) under Track recognition.\n\nYou can also try Simulate to see how it looks without a token.")
                .setPositiveButton("OK", null).show()
            return
        }
        idBtn.isEnabled = false
        val w = wav; val i = info
        worker.execute {
            val p = try { Recognition.plan(w, i, simulate) } catch (_: Exception) { null }
            ui.post {
                if (destroyed) return@post
                idBtn.isEnabled = true
                if (p == null || p.total == 0) { toast("Nothing to identify in this recording"); return@post }
                val msg = if (simulate) "Simulation: fills in made-up results for ${p.total} probes. No network, no quota."
                else "Uses up to ${p.fresh} request${if (p.fresh == 1) "" else "s"} of your AudD quota (${p.total} probes" +
                    (if (p.cached > 0) ", ${p.cached} already answered from an earlier run" else "") +
                    "; clips that are too quiet are skipped and do not count).\n\n12-second clips of this recording are sent to AudD."
                AlertDialog.Builder(this).setTitle(if (simulate) "Simulate identification" else "Identify tracks").setMessage(msg)
                    .setPositiveButton("Start") { _, _ -> bindJob(Recognition.launch(this, wav, simulate)) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun bindJob(j: RecognizeJob) {
        idJob = j
        j.listener = { job -> if (!destroyed) renderJob(job) }
        renderJob(j)
    }

    private fun renderJob(j: RecognizeJob) {
        idStatus.visibility = View.VISIBLE
        if (j.phase != "done") {
            idBtn.isEnabled = false
            idCancel.visibility = View.VISIBLE
            idBar.visibility = View.VISIBLE
            idBar.progress = if (j.total > 0) j.done * 100 / j.total else 0
            idStatus.setTextColor(C.DIM)
            idStatus.text = if (j.phase == "queued") "Waiting for another job to finish..."
            else "Probe ${minOf(j.done + 1, maxOf(j.total, 1))}/${j.total} · ${j.found} track${if (j.found == 1) "" else "s"} found" + (if (j.simulate) " (simulated)" else "")
            return
        }
        j.listener = null
        idJob = null
        idBtn.isEnabled = true
        idCancel.visibility = View.GONE; idCancel.isEnabled = true
        idBar.visibility = View.GONE
        idStatus.setTextColor(if (j.fatal) C.RED else C.TEXT)
        idStatus.text = j.summary
        tracks.clear(); tracks.addAll(TrackFile.load(wav))
        refreshTracks(); refreshPreview()
    }

    /** Long-press on an audio row: open the song page (lis.tn link from AudD) in a browser. */
    private fun openSongLink(e: TrackEntry) {
        val w = wav; val a = e.artist; val t = e.title
        worker.execute {
            val link = RecogCache.findLink(w, a, t)
            ui.post {
                if (destroyed) return@post
                if (!link.startsWith("http")) { toast("No song link saved for this track"); return@post }
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } catch (_: Exception) { toast("No browser found") }
            }
        }
    }

    /** Add (null) or edit one tracklist line in a small dialog. */
    private fun editTrack(e: TrackEntry?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(8f), px(20f), 0) }
        fun field(hint: String, value: String, type: Int) = EditText(this).apply {
            setText(value); this.hint = hint; inputType = type; setSingleLine()
        }.also { box.addView(it) }
        val artist = field("Artist", e?.artist ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val title = field("Title", e?.title ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val deck = field("Deck (1-4, optional)", if (e != null && e.deck > 0) e.deck.toString() else "", InputType.TYPE_CLASS_NUMBER)
        AlertDialog.Builder(this).setTitle(if (e == null) "Add track at ${Rec.clockT(curSec())}" else "Edit track at ${Rec.clockT(e.sec)}")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val a = artist.text.toString().trim(); val t = title.text.toString().trim()
                val d = deck.text.toString().trim().toIntOrNull()?.coerceIn(0, 9) ?: 0
                if (e == null) tracks.add(TrackEntry(curSec(), d, a, t, "manual"))
                else {
                    if (e.artist != a || e.title != t) e.source = "manual"
                    e.artist = a; e.title = t; e.deck = d
                }
                saveTracks(); refreshTracks(); refreshPreview()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun audioName() = Rec.base(wav) + fmt.suffix + "." + fmt.ext

    private fun buildExportText(kind: Int): String? {
        val list = TrackExport.inRange(tracks, inSec, outSec)
        if (list.isEmpty()) return null
        val title = Rec.displayName(Rec.base(wav))
        return when (kind) {
            0 -> TrackExport.cue(list, title, audioName())
            1 -> TrackExport.mixcloud(list)
            else -> TrackExport.caption(list, title)
        }
    }

    private fun showPreview(kind: Int) {
        val t = buildExportText(kind)
        if (t == null) { toast(if (tracks.isEmpty()) "No tracks to export" else "No tracks inside the trim"); return }
        previewKind = kind; previewText = t
        previewT.text = t
        saveCueB.visibility = if (kind == 0) View.VISIBLE else View.GONE
        previewCard.visibility = View.VISIBLE
    }

    private fun refreshPreview() {
        if (previewKind < 0 || previewCard.visibility != View.VISIBLE) return
        val t = buildExportText(previewKind)
        if (t == null) { previewCard.visibility = View.GONE; previewKind = -1 } else { previewText = t; previewT.text = t }
    }

    private fun copyText(t: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Tracklist", t))
        toast("Copied")
    }

    private fun shareText(t: String) {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t) }
        try { startActivity(Intent.createChooser(i, "Share tracklist")) } catch (_: Exception) { toast("No app to share with") }
    }

    private fun saveCue() {
        if (previewKind != 0) return
        val text = previewText; val name = audioName().substringBeforeLast('.') + ".cue"
        val app = applicationContext
        worker.execute {
            val msg = try { Exporter.saveCue(app, name, text) } catch (e: Exception) { "Could not save the cue file: ${e.message}" }
            ui.post { if (!destroyed) toast(msg) }
        }
    }

    // ---------- trim ----------
    private fun setTrim(i: Double, o: Double) {
        inSec = i.coerceIn(0.0, durSec); outSec = o.coerceIn(0.0, durSec)
        if (outSec - inSec < 0.2) { if (inSec > durSec - 0.2) inSec = (durSec - 0.2).coerceAtLeast(0.0); outSec = (inSec + 0.2).coerceAtMost(durSec) }
        refreshTrim()
        trimPeak = -2f; refreshGain()
        ui.removeCallbacks(scanRunnable); ui.postDelayed(scanRunnable, 400)
        refreshPreview()
    }

    private fun setInHere() {
        val t = curSec()
        if (t >= outSec - 0.5) { toast("IN must be before OUT"); return }
        setTrim(t, outSec)
    }

    private fun setOutHere() {
        val t = curSec()
        if (t <= inSec + 0.5) { toast("OUT must be after IN"); return }
        setTrim(inSec, t)
    }

    private fun autoTrim() {
        val d = wave
        if (d == null || d.count == 0) { toast(if (analyzing) "Waveform still analyzing" else "No waveform data"); return }
        val thr = 10.0.pow(SILENCE_DB / 20.0).toFloat()
        fun level(i: Int): Float {
            val o = i * WaveData.STRIDE
            return WaveData.linear(d.bytes[o].toInt() and 0xff) + WaveData.linear(d.bytes[o + 1].toInt() and 0xff) +
                WaveData.linear(d.bytes[o + 2].toInt() and 0xff)
        }
        var first = -1; var last = -1
        for (i in 0 until d.count) if (level(i) > thr) { first = i; break }
        for (i in d.count - 1 downTo 0) if (level(i) > thr) { last = i; break }
        if (first < 0) { toast("Nothing above -50 dBFS (silent recording)"); return }
        setTrim(first / 100.0, (last + 1) / 100.0)
        seekSec(inSec)
        toast("Trimmed: IN ${Rec.clockT(inSec)}, OUT ${Rec.clockT(outSec)}")
    }

    // ---------- gain ----------
    private fun startPeakScan() {
        val g = ++scanGen
        val s = (inSec * info.rate).roundToLong(); val e = (outSec * info.rate).roundToLong().coerceAtMost(info.frames)
        val i = info
        trimPeak = -2f
        Thread({
            val pk = PeakScanner.scan(i, s, e) { scanGen != g || destroyed }
            ui.post {
                if (destroyed || scanGen != g || pk < 0f) return@post
                trimPeak = pk
                if (pendingNormalize) { pendingNormalize = false; applyNormalize() } else refreshGain()
            }
        }, "peak-scan").start()
    }

    private fun normalize() {
        if (trimPeak < 0f) { pendingNormalize = true; toast("Scanning peak, then normalizing…"); return }
        applyNormalize()
    }

    private fun applyNormalize() {
        if (trimPeak <= 0f) { toast("Selection is digital silence"); return }
        val pk = Rec.dbOf(trimPeak)
        val want = -0.3f - pk
        gainDb = want.coerceIn(-12f, 12f)
        refreshGain()
        if (want > 12f) toast("Very quiet: gain limited to +12 dB")
    }

    // ---------- export ----------
    private fun needStoragePermission(action: () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return false
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false
        pendingAction = action
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        return true
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != REQ_STORAGE) return
        val act = pendingAction; pendingAction = null
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) act?.invoke()
        else resultT.text = "Storage permission denied; cannot save to Music."
    }

    private fun setBusy(b: Boolean) {
        exporting = b
        prog.visibility = if (b) View.VISIBLE else View.GONE
        exportB.text = if (b) "Cancel export" else "Export trimmed mix"
        stemsB.isEnabled = !b; stemsB.alpha = if (b) 0.4f else 1f
        if (b) { prog.progress = 0; shareB.visibility = View.GONE }
    }

    private fun progressCb(): (Float) -> Unit {
        var last = -1
        return { f ->
            val v = (f * 1000).toInt().coerceIn(0, 1000)
            if (v != last) { last = v; ui.post { if (!destroyed) prog.progress = v } }
        }
    }

    private fun onExportTap() {
        if (exporting) { cancelExport = true; return }
        if (needStoragePermission { onExportTap() }) return
        val sf = (inSec * info.rate).roundToLong(); val ef = (outSec * info.rate).roundToLong().coerceAtMost(info.frames)
        val p = ExportParams(info, sf, ef, gainDb, fade, fmt, Rec.base(wav), markers.toList())
        val app = applicationContext
        cancelExport = false; setBusy(true)
        resultT.text = "Exporting ${fmt.label}…"; resultT.setTextColor(C.DIM)
        val pcb = progressCb()
        worker.execute {
            var msg: String; var ok = false; var uri: Uri? = null; var mime = p.fmt.mime
            try {
                val r = Exporter.run(app, p, pcb) { cancelExport }
                msg = r.message; uri = r.uri; mime = r.mime; ok = true
            } catch (_: Cancelled) { msg = "Export cancelled" }
            catch (e: Exception) { msg = "Export failed: ${e.message ?: e.javaClass.simpleName}" }
            ui.post {
                if (destroyed) return@post
                setBusy(false)
                resultT.text = msg; resultT.setTextColor(if (ok) C.GREEN else C.RED)
                lastUri = uri; lastMime = mime
                shareB.visibility = if (ok && uri != null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onStemsTap() {
        if (exporting) return
        if (needStoragePermission { onStemsTap() }) return
        val sf = (inSec * info.rate).roundToLong(); val ef = (outSec * info.rate).roundToLong().coerceAtMost(info.frames)
        val app = applicationContext; val st = Rec.stems(wav); val base = Rec.base(wav); val i = info
        cancelExport = false; setBusy(true)
        exportB.text = "Cancel export"
        resultT.text = "Exporting stems…"; resultT.setTextColor(C.DIM)
        val pcb = progressCb()
        worker.execute {
            var msg: String; var ok = false
            try { msg = Exporter.exportStems(app, st, i, sf, ef, base, pcb) { cancelExport }; ok = true }
            catch (_: Cancelled) { msg = "Stem export cancelled" }
            catch (e: Exception) { msg = "Stem export failed: ${e.message ?: e.javaClass.simpleName}" }
            ui.post {
                if (destroyed) return@post
                setBusy(false)
                resultT.text = msg; resultT.setTextColor(if (ok) C.GREEN else C.RED)
            }
        }
    }

    private fun share() {
        val u = lastUri ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = lastMime; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(i, "Share export")) } catch (_: Exception) { toast("No app to share with") }
    }

    // ---------- rename / delete ----------
    private fun renameDialog() {
        if (analyzing) { toast("Wait for waveform analysis to finish"); return }
        if (exporting) { toast("Wait for the export to finish"); return }
        val et = EditText(this).apply {
            setText(Rec.base(wav)); inputType = InputType.TYPE_CLASS_TEXT; setSingleLine()
            selectAll()
        }
        AlertDialog.Builder(this).setTitle("Rename recording").setView(et)
            .setPositiveButton("Rename") { _, _ -> doRename(et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doRename(raw: String) {
        val n = raw.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().removeSuffix(".wav").trim()
        if (n.isEmpty()) { toast("Name is empty"); return }
        if (n.contains("-12ch")) { toast("Name cannot contain -12ch"); return }
        if (n == Rec.base(wav)) return
        val dir = wav.parentFile!!
        val nw = File(dir, "$n.wav")
        if (nw.exists()) { toast("That name already exists"); return }
        val pairs = listOf(
            wav to nw, Rec.stems(wav) to File(dir, "$n-12ch.wav"),
            Rec.waveFile(wav) to File(dir, "$n.wave"), Rec.cueFile(wav) to File(dir, "$n.cue.txt"),
            Rec.eventsFile(wav) to File(dir, "$n.events.txt"), Rec.tracksFile(wav) to File(dir, "$n.tracks.txt")
        )
        for ((a, b) in pairs) if (a.exists() && b.exists()) { toast("Target files already exist"); return }
        val moved = mutableListOf<Pair<File, File>>()
        for ((a, b) in pairs) {
            if (!a.exists()) continue
            if (!a.renameTo(b)) {
                val failed = moved.asReversed().filterNot { (original, renamed) -> renamed.renameTo(original) }
                toast(if (failed.isEmpty()) "Rename failed for ${a.name}" else "Rename incomplete. Check both recording names.")
                if (failed.isNotEmpty()) { setResult(RESULT_OK); finish() }
                return
            }
            moved.add(a to b)
        }
        wav = nw
        info = WavInfo(nw, info.rate, info.channels, info.frames, info.dataOffset)
        refreshInfo(); refreshPreview()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this).setTitle("Delete recording?")
            .setMessage("${Rec.displayName(Rec.base(wav))}\nThis permanently deletes the WAV, stems file, waveform, markers and tracklist from this phone. Exports in Music are not touched.")
            .setPositiveButton("Delete") { _, _ ->
                deleting = true; player?.pause(); player?.release(); player = null
                cancelExport = true
                val files = listOf(wav, Rec.stems(wav), Rec.waveFile(wav), Rec.cueFile(wav), Rec.eventsFile(wav), Rec.tracksFile(wav))
                // give the player thread a moment to close its file handle (harmless on Linux, but tidy)
                worker.execute {
                    val failed = files.filter { f -> runCatching { f.exists() && !f.delete() && f.exists() }.getOrDefault(true) }
                    ui.post {
                        if (!destroyed) {
                            if (failed.isNotEmpty()) toast("Couldn't delete ${failed.joinToString { it.name }}")
                            setResult(RESULT_OK)
                            finish()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
