package app.djmrecorder

import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.sin

/**
 * The Record tab: one screen, no scrolling. Top to bottom: header, timer + state, format line, waveform (takes all
 * the leftover height), LED meters, quality / bit depth / stems card, record button, monitoring + hardware gain.
 * On short screens the timer and button shrink (and the idle stats line goes away) before the waveform gets squeezed.
 */
class RecordPage(private val a: MainActivity) {
    val view = LinearLayout(a)
    val wave = LiveWaveView(a)

    private val status: TextView
    private val linkPill: TextView
    private val autoBadge: TextView
    private val banner: TextView
    private val timer: TextView
    private val recPill: LinearLayout
    private val recDot: View
    private val recText: TextView
    private val hint: TextView
    private val fmtT: TextView
    private val statsT: TextView
    private val infoT: TextView
    private val meter = LedMeter(a)
    private val qualityV: TextView
    private val bitV: TextView
    private val stemsV: TextView
    private val qualityCell: LinearLayout
    private val bitCell: LinearLayout
    private val stemsCell: LinearLayout
    private val recBtn = RecordButton(a)
    private val markerBtn: LinearLayout
    private val markerT: TextView
    private val monSwitch: Switch
    private val gainV: TextView
    private var compact = false
    private var tickN = 0
    private var lastLayoutH = -1

    private fun rec() = a.svc?.let { it.state == RecordService.State.RECORDING || it.state == RecordService.State.WAITING } ?: false

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setPadding(a.px(20f), 0, a.px(20f), 0)

        // ---- header ----
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(SegmentLogo(a), LinearLayout.LayoutParams(0, a.px(36f), 1f))
        status = a.tv("", 10.5f, C.DIM, Fonts.MED).apply { setPadding(a.px(9f), a.px(4f), a.px(9f), a.px(4f)); setSingleLine() }
        status.setOnClickListener { a.svc?.findMixer() }
        head.addView(status, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = a.px(8f) })
        linkPill = a.tv("", 10.5f, C.CYAN, Fonts.MED).apply {
            setPadding(a.px(9f), a.px(4f), a.px(9f), a.px(4f)); setSingleLine(); visibility = View.GONE
            background = a.bg(0, 14f, C.CYAN)
        }
        head.addView(linkPill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = a.px(6f) })
        autoBadge = a.tv("AUTO", 10f, C.BG, Fonts.MED).apply {
            setPadding(a.px(7f), a.px(3f), a.px(7f), a.px(3f)); background = a.bg(C.AMBER, 6f); visibility = View.GONE
        }
        head.addView(autoBadge, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = a.px(6f) })
        head.addView(a.iconButton(R.drawable.ic_gear, C.TEXT, 40f) { a.openSettings() }, LinearLayout.LayoutParams(a.px(40f), a.px(40f)).apply { leftMargin = a.px(4f) })
        view.addView(head, lp(-1, a.px(52f)))

        banner = a.tv("", 13f, C.AMBER, Fonts.MED).apply { setPadding(a.px(14f), a.px(9f), a.px(14f), a.px(9f)); visibility = View.GONE }
        view.addView(banner, lp(top = a.px(2f)))

        // ---- timer, state, format ----
        timer = a.tv("00:00:00", 60f, C.DIM, Fonts.LIGHT).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum"; setSingleLine(); setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 60f) }
        view.addView(timer, lp(top = a.px(2f)))

        val pillRow = FrameLayout(a)
        recPill = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(a.px(12f), 0, a.px(14f), 0); visibility = View.INVISIBLE
        }
        recDot = View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.RED) } }
        recPill.addView(recDot, LinearLayout.LayoutParams(a.px(8f), a.px(8f)).apply { rightMargin = a.px(7f) })
        recText = a.tv("REC", 13f, C.RED, Fonts.MED).apply { letterSpacing = 0.1f }
        recPill.addView(recText)
        pillRow.addView(recPill, FrameLayout.LayoutParams(-2, a.px(28f), Gravity.CENTER))
        hint = a.tv("", 13f, C.DIM).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        pillRow.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        view.addView(pillRow, lp(-1, a.px(28f), top = a.px(4f)))

        fmtT = a.tv("", 13f, C.DIM).apply { gravity = Gravity.CENTER; setSingleLine() }
        view.addView(fmtT, lp(top = a.px(6f)))
        statsT = a.tv("", 12f, C.DIM).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        view.addView(statsT, lp(top = a.px(3f)))
        infoT = a.tv("", 12f, C.CYAN).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = TextUtils.TruncateAt.END; visibility = View.INVISIBLE }
        view.addView(infoT, lp(top = a.px(3f)))

        // ---- waveform (flexible) ----
        view.addView(wave, lp(-1, 0, top = a.px(8f), weight = 1f))

        // ---- meters ----
        meter.setOnClickListener { meter.resetClip(); a.svc?.maxPk?.fill(-90f) }
        view.addView(meter, lp(top = a.px(8f)))

        // ---- quality / bit depth / stems ----
        val sc = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; background = a.bg(C.CARD, 16f, C.LINE) }
        qualityV = a.tv("", 17f, C.TEXT, Fonts.REG).apply { setSingleLine() }
        bitV = a.tv("", 17f, C.TEXT, Fonts.REG).apply { setSingleLine() }
        stemsV = a.tv("", 17f, C.TEXT, Fonts.REG).apply { setSingleLine() }
        qualityCell = cell("Quality", qualityV, null)
        bitCell = cell("Bit depth", bitV, null)
        stemsCell = cell("Stems", stemsV, R.drawable.ic_stems)
        sc.addView(qualityCell, LinearLayout.LayoutParams(0, -2, 1f))
        sc.addView(a.vline().apply { layoutParams = LinearLayout.LayoutParams(1, a.px(38f)).apply { gravity = Gravity.CENTER_VERTICAL } })
        sc.addView(bitCell, LinearLayout.LayoutParams(0, -2, 1f))
        sc.addView(a.vline().apply { layoutParams = LinearLayout.LayoutParams(1, a.px(38f)).apply { gravity = Gravity.CENTER_VERTICAL } })
        sc.addView(stemsCell, LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(sc, lp(top = a.px(12f)))
        qualityCell.setOnClickListener { pick(qualityCell, listOf("44.1 kHz", "48 kHz", "96 kHz"), RecordService.RATES.indexOf(a.settings.rate), locked = true) { i ->
            a.settings.rate = RecordService.RATES[i]; a.settingsChanged(true) } }
        bitCell.setOnClickListener { pick(bitCell, listOf("16-bit (export)", "24-bit"), if (a.settings.bitDepth == 16) 0 else 1, locked = true) { i ->
            a.settings.bitDepth = if (i == 0) 16 else 24; a.settingsChanged(false) } }
        stemsCell.setOnClickListener { pick(stemsCell, listOf("On", "Off"), if (a.settings.stems) 0 else 1, locked = true) { i ->
            a.settings.stems = i == 0; a.settingsChanged(true) } }

        // ---- record button row ----
        val rr = FrameLayout(a)
        rr.addView(recBtn, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        markerT = a.tv("Marker", 14f, C.TEXT, Fonts.MED)
        markerBtn = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(a.px(14f), a.px(10f), a.px(16f), a.px(10f))
            background = a.ripple(a.bg(C.CARD, 22f, C.LINE)); visibility = View.INVISIBLE
            addView(a.icon(R.drawable.ic_marker, C.TEXT, 18f), LinearLayout.LayoutParams(a.px(18f), a.px(18f)).apply { rightMargin = a.px(6f) })
            addView(markerT)
            setOnClickListener { a.svc?.addMarker(); performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) }
        }
        rr.addView(markerBtn, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        recBtn.setOnClickListener { onRecordTap() }
        view.addView(rr, lp(top = a.px(6f)))

        // ---- monitoring + hardware gain ----
        val mc = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = a.bg(C.CARD, 16f, C.LINE) }
        val left = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.px(16f), 0, a.px(12f), 0) }
        left.addView(a.icon(R.drawable.ic_headphones, C.TEXT, 22f), LinearLayout.LayoutParams(a.px(22f), a.px(22f)).apply { rightMargin = a.px(10f) })
        left.addView(a.tv("Monitoring", 14f, C.TEXT, Fonts.REG), LinearLayout.LayoutParams(0, -2, 1f))
        monSwitch = a.styledSwitch(a.settings.monitor) { on -> onMonitorToggle(on) }
        left.addView(monSwitch)
        mc.addView(left, LinearLayout.LayoutParams(0, -1, 1f))
        mc.addView(a.vline().apply { layoutParams = LinearLayout.LayoutParams(1, a.px(30f)) })
        val right = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.px(14f), 0, a.px(12f), 0); background = a.ripple(null) }
        right.addView(a.icon(R.drawable.ic_gain, C.DIM, 20f), LinearLayout.LayoutParams(a.px(20f), a.px(20f)).apply { rightMargin = a.px(8f) })
        right.addView(a.tv("Hardware gain", 13.5f, C.DIM, Fonts.REG).apply { setSingleLine() }, LinearLayout.LayoutParams(0, -2, 1f))
        gainV = a.tv("", 14f, C.TEXT, Fonts.MED).apply { setSingleLine() }
        right.addView(gainV)
        right.addView(a.icon(R.drawable.ic_chevron_right, C.DIM, 18f), LinearLayout.LayoutParams(a.px(18f), a.px(18f)))
        right.setOnClickListener {
            pick(right, listOf("Level 1 (lowest)", "Level 2", "Level 3", "Level 4 (highest)"), a.settings.level) { i ->
                a.svc?.setLevel(i) ?: run { a.settings.level = i }; refreshStatic() }
        }
        mc.addView(right, LinearLayout.LayoutParams(0, -1, 1.15f))
        view.addView(mc, lp(-1, a.px(56f), top = a.px(12f)).apply { bottomMargin = a.px(10f) })

        view.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
            val h = b - t
            if (h != lastLayoutH) { lastLayoutH = h; applyDensity(h / a.resources.displayMetrics.density) }
        }
        refreshStatic(); refreshState()
    }

    /** Short screens: smaller timer and button, no stats line. The waveform stays the flexible part. */
    private fun applyDensity(hDp: Float) {
        val c = hDp < 690f
        if (c == compact) return
        compact = c
        timer.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, if (c) 46f else 60f)
        recBtn.sizeDp = if (c) 96f else 132f
    }

    private fun cell(name: String, value: TextView, ic: Int?): LinearLayout {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(a.px(16f), a.px(11f), a.px(10f), a.px(11f)); background = a.ripple(null)
        }
        col.addView(a.label(name))
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (ic != null) row.addView(a.icon(ic, C.DIM, 18f), LinearLayout.LayoutParams(a.px(18f), a.px(18f)).apply { rightMargin = a.px(6f) })
        row.addView(value)
        row.addView(a.icon(R.drawable.ic_chevron_down, C.DIM, 18f), LinearLayout.LayoutParams(a.px(18f), a.px(18f)).apply { leftMargin = a.px(6f) })
        col.addView(row, lp(top = a.px(5f)))
        return col
    }

    private fun pick(anchor: View, items: List<String>, checked: Int, locked: Boolean = false, onPick: (Int) -> Unit) {
        if (locked && rec()) { Toast.makeText(a, "Locked while recording", Toast.LENGTH_SHORT).show(); return }
        val pm = PopupMenu(a, anchor, Gravity.START)
        items.forEachIndexed { i, s -> pm.menu.add(0, i, i, s).apply { isCheckable = true; isChecked = i == checked } }
        pm.menu.setGroupCheckable(0, true, true)
        pm.setOnMenuItemClickListener { onPick(it.itemId); true }
        pm.show()
    }

    private fun onMonitorToggle(on: Boolean) {
        val s = a.svc
        if (s != null) s.setMonitor(on) else a.settings.monitor = on
        if (on) Toast.makeText(a, "Monitoring through the phone adds latency (about 30-100 ms). Use it to check the signal, not to cue. Mind feedback if the phone is near speakers.", Toast.LENGTH_LONG).show()
    }

    private fun onRecordTap() {
        val s = a.svc ?: return
        when (s.state) {
            RecordService.State.RECORDING, RecordService.State.WAITING -> s.stopRecording()
            else -> s.startRecording()
        }
    }

    fun bind(s: RecordService) {
        wave.data = s.wave; wave.markers = s.markers; wave.events = s.events; wave.tracks = s.tracks
    }

    /** Settings-driven text: format line, gain, selectors, locks. */
    fun refreshStatic() {
        val st = a.settings
        val rate = "${st.rate / 1000.0}".removeSuffix(".0") + " kHz"
        qualityV.text = rate
        bitV.text = "${st.bitDepth}-bit"
        stemsV.text = if (st.stems) "On" else "Off"
        fmtT.text = rate + (if (st.bitDepth == 16) " · 24-bit rec · 16-bit export" else " · 24-bit WAV") + (if (st.stems) " · + stems" else "")
        gainV.text = "Level ${st.level + 1}"
        autoBadge.visibility = if (st.autoStart || st.autoStopMin > 0) View.VISIBLE else View.GONE
        if (monSwitch.isChecked != st.monitor) monSwitch.isChecked = st.monitor
        val locked = rec()
        for (c in listOf(qualityCell, bitCell, stemsCell)) c.alpha = if (locked) 0.4f else 1f
    }

    /** State-driven text: status pill, REC/ARMED pill, hint, marker button. */
    fun refreshState() {
        val s = a.svc
        val dev = s?.mixer()
        val st = s?.state ?: RecordService.State.IDLE
        val (t, col) = when {
            s?.demo == true -> "DEMO" to C.AMBER
            st == RecordService.State.WAITING -> "Waiting for mixer" to C.AMBER
            dev == null -> "No mixer" to C.DIM
            s?.hasMixerPermission() != true -> "Tap to allow" to C.AMBER
            dev.productId == RecordService.PID_900NXS2 -> "DJM-900NXS2" to C.GREEN
            else -> (dev.productName ?: "Pioneer DJ") to C.AMBER
        }
        status.text = t; status.setTextColor(col); status.background = a.bg(0, 14f, if (col == C.DIM) C.LINE else col)

        recBtn.mode = when {
            s == null -> 0
            st == RecordService.State.RECORDING -> 2
            st == RecordService.State.WAITING -> 3
            st == RecordService.State.ARMED -> 1
            else -> 0
        }
        when {
            s?.demo == true -> setPill("DEMO", C.AMBER)
            st == RecordService.State.RECORDING -> setPill("REC", C.RED)
            st == RecordService.State.WAITING -> setPill("PAUSED", C.AMBER)
            st == RecordService.State.ARMED -> setPill("ARMED", C.AMBER)
            else -> { recPill.visibility = View.INVISIBLE; hint.visibility = View.VISIBLE }
        }
        hint.text = when {
            dev == null -> "Try demo"
            s?.hasMixerPermission() != true -> "Tap the status to allow USB access"
            else -> "Ready"
        }
        val canDemo = s != null && dev == null && st == RecordService.State.IDLE
        hint.setTextColor(if (canDemo) C.TEXT else C.DIM)
        hint.setOnClickListener(if (canDemo) View.OnClickListener { s?.startDemo() } else null)
        hint.isClickable = canDemo
        markerBtn.visibility = if (st == RecordService.State.RECORDING) View.VISIBLE else View.INVISIBLE
        wave.active = st != RecordService.State.IDLE
        refreshStatic()
    }

    private fun setPill(text: String, col: Int) {
        recText.text = text; recText.setTextColor(col)
        recPill.background = a.bg(0, 14f, col, 1.2f)
        (recDot.background as GradientDrawable).setColor(col)
        recPill.visibility = View.VISIBLE; hint.visibility = View.INVISIBLE
    }

    private fun hms(sec: Long): String = if (sec >= 3600) "${sec / 3600}h ${sec / 60 % 60}m" else "${sec / 60}m"

    /** Every service tick (80 ms). */
    fun render() {
        val s = a.svc ?: return
        val st = s.state
        val recNow = st == RecordService.State.RECORDING || st == RecordService.State.WAITING
        tickN++
        timer.text = Rec.clock(if (recNow) s.elapsedSec() else 0)
        timer.setTextColor(when (st) { RecordService.State.RECORDING -> C.TEXT; RecordService.State.WAITING -> C.AMBER; else -> C.DIM })
        if (st == RecordService.State.IDLE) meter.reset() else meter.set(s.levelL, s.levelR)
        if (recNow) recDot.alpha = (0.55 + 0.45 * ((sin(tickN / 3.0) + 1) / 2)).toFloat() else recDot.alpha = 1f

        val sb = SpannableStringBuilder()
        if (recNow) {
            if (!s.demo) sb.append("REC ").append(Rec.size(s.recBytes)).append(" · ")
            sb.append(Rec.size(s.freeBytes)).append(" free · ")
            val n = s.dropouts
            val d = "$n dropout" + (if (n == 1) "" else "s") + (if (s.reconnects > 0) ", ${s.reconnects} reconnect${if (s.reconnects == 1) "" else "s"}" else "")
            val at = sb.length
            sb.append(d)
            sb.setSpan(ForegroundColorSpan(if (n > 0 || s.reconnects > 0) C.RED else C.GREEN), at, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sb.append(Rec.size(s.freeBytes)).append(" free · ~").append(hms(s.leftSec)).append(" left")
            if (st == RecordService.State.ARMED && s.preArmedSec > 0) sb.append(" · pre-roll %.0f/%d s".format(Locale.US, s.preFilledSec, s.preArmedSec))
        }
        statsT.text = sb
        statsT.visibility = if (compact && st == RecordService.State.IDLE) View.GONE else View.VISIBLE   // short screens: only hide it when idle
        statsT.setTextColor(when (s.storageLevel) { 0 -> C.DIM; 1 -> C.AMBER; else -> C.RED })

        // now playing / hints line
        val last = s.tracks.lastOrNull()
        val hintMidi = if (st != RecordService.State.IDLE && !s.demo) s.midiHint() else null
        when {
            last != null && recNow -> {
                infoT.text = "♪ " + (if (last.deck > 0) "Deck ${last.deck} · " else "") + (if (last.hasName()) last.label() else "no title")
                infoT.setTextColor(C.CYAN); infoT.visibility = View.VISIBLE
            }
            hintMidi != null -> { infoT.text = hintMidi; infoT.setTextColor(C.AMBER); infoT.visibility = View.VISIBLE }
            else -> infoT.visibility = View.INVISIBLE
        }
        if (s.linkStatus.isNotEmpty() && st != RecordService.State.IDLE) { linkPill.text = s.linkStatus; linkPill.visibility = View.VISIBLE }
        else linkPill.visibility = View.GONE

        val w = s.warnings()
        if (w.isEmpty()) banner.visibility = View.GONE
        else {
            banner.visibility = View.VISIBLE
            banner.text = w.joinToString("\n") { it.first }
            val worst = if (w.any { it.second == C.RED }) C.RED else C.AMBER
            banner.setTextColor(worst); banner.background = a.bg(C.CARD, 12f, worst)
        }
        if (recNow) markerT.text = if (s.markers.isEmpty()) "Marker" else "Marker (${s.markers.size})"
    }

    /** Called when the state changes from recording to something else (timer back to zero, meters reset). */
    fun onStopped() { timer.text = "00:00:00"; meter.resetClip() }

    fun onStateChanged(was: RecordService.State, now: RecordService.State) {
        if (was != now) meter.resetClip()
    }

    fun idleTick() {
        val s = a.svc
        if (s == null || s.state == RecordService.State.IDLE) {
            meter.reset()
            timer.setTextColor(C.DIM)
            refreshState()
            s?.let { it.updateStorage(); render() }
        }
    }
}
