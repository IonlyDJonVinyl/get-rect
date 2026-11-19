package app.djmrecorder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Full-screen settings page on top of the main screen: every secondary option, tracklist sources and diagnostics. */
class SettingsPage(private val a: MainActivity) {
    val view = LinearLayout(a)
    private val preChips: List<TextView>
    private val stopChips: List<TextView>
    private val levelChips: List<TextView>
    private val preNote: TextView
    private val midiT: TextView
    private val linkT: TextView
    private val diag: TextView
    private val diagBox = LinearLayout(a)

    private fun section(title: String): LinearLayout {
        val c = a.card(16f, 12f)
        c.addView(a.label(title))
        return c
    }

    private fun group(label: String, chips: List<TextView>): LinearLayout {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(a.tv(label, 13f, C.DIM), lp(top = a.px(12f)))
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in chips) row.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = a.px(8f) })
        col.addView(row, lp(top = a.px(8f)))
        return col
    }

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(C.BG)
        view.isClickable = true
        view.setPadding(a.px(20f), 0, a.px(20f), 0)

        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(a.iconButton(R.drawable.ic_back, C.TEXT, 40f) { a.closeSettings() }, LinearLayout.LayoutParams(a.px(40f), a.px(40f)).apply { leftMargin = -a.px(8f); rightMargin = a.px(6f) })
        head.addView(a.tv("Settings", 19f, C.TEXT, Fonts.LIGHT))
        view.addView(head, lp(-1, a.px(52f)))

        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val s = a.settings

        // ---- recording ----
        val rec = section("Recording")
        preChips = Settings.PRE_OPTIONS.map { sec -> a.chip(if (sec == 0) "Off" else "$sec s") { s.preRollSec = sec; a.settingsChanged(true); refresh() } }
        rec.addView(group("Pre-roll (kept in RAM while armed, saved when you press record)", preChips))
        preNote = a.tv("", 11.5f, C.DIM)
        rec.addView(preNote, lp(top = a.px(6f)))
        rec.addView(a.switchRow("Auto-start", "Start recording when the level stays above -50 dBFS for 2 s", s.autoStart) { s.autoStart = it; a.settingsChanged(true); refresh() }, lp(top = a.px(8f)))
        stopChips = Settings.STOP_OPTIONS.map { m -> a.chip(if (m == 0) "Off" else "$m min") { s.autoStopMin = m; a.settingsChanged(false); refresh() } }
        rec.addView(group("Auto-stop after silence (below -60 dBFS)", stopChips))
        rec.addView(a.switchRow("Keep screen on", "While armed or recording", s.keepScreenOn) { s.keepScreenOn = it; a.settingsChanged(false) }, lp(top = a.px(8f)))
        rec.addView(a.switchRow("Keep real-time gaps", "On reconnect, fill the unplugged time with silence", s.keepGaps) { s.keepGaps = it; a.settingsChanged(false) })
        rec.addView(a.switchRow("Feedback fix", "Send silent frames back to the mixer (try this if the stream stalls)", s.feedback) { s.feedback = it; a.settingsChanged(true) })
        body.addView(rec)

        // ---- mixer ----
        val mix = section("Mixer")
        levelChips = (0..3).map { i -> a.chip("${i + 1}") { a.svc?.setLevel(i) ?: run { s.level = i }; a.settingsChanged(false); refresh() } }
        mix.addView(group("Capture level 1-4 (hardware capture gain)", levelChips))
        body.addView(mix, lp(top = a.px(12f)))

        // ---- tracklist ----
        val tl = section("Tracklist sources")
        tl.addView(a.switchRow("Fader tracking (USB-MIDI)", "Press MIDI ON/OFF on the mixer so it is lit. Fader moves are logged as mixes.", s.midiTrack) {
            s.midiTrack = it; a.svc?.syncTrackers(); refresh() }, lp(top = a.px(6f)))
        midiT = a.tv("", 12f, C.DIM)
        tl.addView(midiT, lp(top = a.px(2f)))
        tl.addView(a.hline(), lp(-1, 1, top = a.px(10f)))
        tl.addView(a.switchRow("CDJ link (Pro DJ Link)", "Join the booth network as a virtual CDJ to read track titles. Needs the CDJs on the same network (USB Ethernet or Wi-Fi).", s.cdjLink) {
            s.cdjLink = it; a.svc?.syncTrackers(); refresh() })
        linkT = a.tv("", 12f, C.DIM)
        tl.addView(linkT, lp(top = a.px(2f)))
        body.addView(tl, lp(top = a.px(12f)))

        // ---- track recognition ----
        val rc = section("Track recognition")
        rc.addView(a.tv("After a set, tracks are identified from the recording itself (no booth network needed) with AudD. Run it from the Tracks tab of a recording.", 12.5f, C.DIM).apply { setLineSpacing(0f, 1.1f) }, lp(top = a.px(6f)))
        val token = EditText(a).apply {
            setText(s.auddToken); hint = "AudD API token"; setHintTextColor(C.DIM); setTextColor(C.TEXT); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine()
            background = a.bg(C.BG, 10f, C.LINE); setPadding(a.px(12f), a.px(10f), a.px(12f), a.px(10f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(x: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(x: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { s.auddToken = e?.toString().orEmpty() }
            })
        }
        rc.addView(token, lp(top = a.px(10f)))
        rc.addView(a.tv("Get a token at audd.io", 12.5f, C.CYAN).apply {
            setPadding(0, a.px(8f), 0, a.px(4f))
            setOnClickListener { try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://audd.io/"))) } catch (_: Exception) {} }
        })
        rc.addView(a.tv("Short 12-second clips of your recording are sent to AudD to identify songs.", 12f, C.DIM).apply { setLineSpacing(0f, 1.1f) }, lp(top = a.px(2f)))
        val testRes = a.tv("", 12f, C.DIM).apply { setLineSpacing(0f, 1.1f) }
        val testRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        testRow.addView(a.pillButton("Test token") {
            val tk = s.auddToken
            if (tk.isBlank()) { testRes.text = "Enter a token first."; return@pillButton }
            testRes.text = "Testing... (sends 1.5 s of silence: it can only check the token, not recognise anything)"
            Thread { val m = Recognition.testToken(tk); a.runOnUiThread { testRes.text = m } }.start()
        })
        rc.addView(testRow, lp(top = a.px(10f)))
        rc.addView(testRes, lp(top = a.px(6f)))
        rc.addView(a.hline(), lp(-1, 1, top = a.px(10f)))
        rc.addView(a.switchRow("Identify automatically after each recording", "Wi-Fi only (unmetered network). Runs in the background with a notification; needs the token above.", s.autoRecognize) { s.autoRecognize = it })
        body.addView(rc, lp(top = a.px(12f)))

        // ---- diagnostics ----
        val dg = section("Diagnostics")
        dg.addView(a.switchRow("Show log", "USB, MIDI and link events, also saved as log.txt", s.showDiag) { s.showDiag = it; refresh() }, lp(top = a.px(6f)))
        diag = a.tv("", 11f, C.DIM, Fonts.MONO).apply { setTextIsSelectable(true); background = a.bg(C.BG, 10f, C.LINE); setPadding(a.px(10f), a.px(10f), a.px(10f), a.px(10f)) }
        diagBox.orientation = LinearLayout.VERTICAL
        diagBox.addView(a.switchRow("RF64 test", "Switch the WAV to RF64 after 50 MB (new recordings only)", s.rf64Test) { s.rf64Test = it })
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(a.pillButton("Copy log") {
            val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Get Rect log", diag.text))
            Toast.makeText(a, "Log copied", Toast.LENGTH_SHORT).show()
        })
        diagBox.addView(btns, lp(top = a.px(6f)))
        diagBox.addView(diag, lp(top = a.px(10f)))
        dg.addView(diagBox)
        body.addView(dg, lp(top = a.px(12f)))
        body.addView(a.tv("Get Rect 0.6", 11.5f, C.DIM).apply { gravity = Gravity.CENTER }, lp(top = a.px(18f)).apply { bottomMargin = a.px(24f) })

        view.addView(ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(body) }, lp(-1, 0, weight = 1f))
        refresh()
    }

    /** Re-reads every setting into the chips and notes. Cheap; called on open and after each change. */
    fun refresh() {
        val s = a.settings
        Settings.PRE_OPTIONS.forEachIndexed { i, v -> a.styleChip(preChips[i], s.preRollSec == v) }
        Settings.STOP_OPTIONS.forEachIndexed { i, v -> a.styleChip(stopChips[i], s.autoStopMin == v) }
        levelChips.forEachIndexed { i, t -> a.styleChip(t, i == s.level) }
        val eff = s.effectivePreSec()
        preNote.text = if (eff == 0) "No pre-roll: the file starts when you press record." else {
            val cap = Native.stemsPreMaxSec(s.rate)
            (if (eff != s.preRollSec) "Auto-start needs at least 5 s of pre-roll. " else "") +
                (if (s.stems && eff > cap) "Stems file pre-roll is capped at $cap s (32 MB); the stereo mix gets the full $eff s and the stems file starts silent." else "Stereo and stems both get $eff s.")
        }
        midiT.text = a.svc?.midiText() ?: if (s.midiTrack) "MIDI: starts when the mixer is armed" else "MIDI: disabled"
        val ls = a.svc?.linkStatus.orEmpty()
        linkT.text = if (!s.cdjLink) "Link: off" else if (ls.isEmpty()) "Link: starts when the mixer is armed" else ls
        diagBox.visibility = if (s.showDiag) View.VISIBLE else View.GONE
    }

    fun setLog(text: String) { diag.text = text }

    /** Settings was opened: show the current log. */
    fun onOpen() { a.svc?.let { diag.text = it.logText.toString() }; refresh() }

    fun appendLog(line: String) {
        if (!a.settings.showDiag) return
        diag.append(line + "\n")
    }
}
