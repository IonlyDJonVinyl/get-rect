package app.djmrecorder

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

/**
 * DJM-900NXS2 recorder UI. The USB connection, capture engine and recording session live in [RecordService];
 * this activity binds to it, shows its live state and sends commands. Closing or rotating it never stops a recording.
 * USB recipe: docs/device-900nxs2.md. Capture engine: cpp/djmusb.c.
 *
 * Two tabs (Record, Files) share one content area; Settings is a page that slides over everything.
 */
class MainActivity : Activity(), RecordService.Listener {
    private val ui = Handler(Looper.getMainLooper())
    var svc: RecordService? = null; private set
    lateinit var settings: Settings; private set
    private lateinit var record: RecordPage
    private lateinit var files: FilesPage
    private lateinit var settingsPage: SettingsPage
    private lateinit var nav: NavBar
    private var lastState = RecordService.State.IDLE
    private var settingsOpen = false

    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val s = (b as RecordService.LocalBinder).service
            svc = s
            s.listener = this@MainActivity
            record.bind(s)
            settingsPage.setLog(s.logText.toString())
            s.setUiVisible(true)
            s.findMixer()
            lastState = s.state
            record.refreshState(); settingsPage.refresh(); files.refresh()
        }
        override fun onServiceDisconnected(n: ComponentName) { svc = null }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        settings = Settings(this)

        record = RecordPage(this)
        files = FilesPage(this)
        settingsPage = SettingsPage(this)

        val content = FrameLayout(this)
        content.addView(record.view, FrameLayout.LayoutParams(-1, -1))
        content.addView(files.view, FrameLayout.LayoutParams(-1, -1))
        files.view.visibility = View.GONE
        nav = NavBar(this, listOf(R.drawable.ic_record_dot to "Record", R.drawable.ic_folder to "Files")) { showTab(it) }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(hline())
        column.addView(nav, LinearLayout.LayoutParams(-1, px(60f)))

        val root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        root.addView(settingsPage.view, FrameLayout.LayoutParams(-1, -1))
        settingsPage.view.visibility = View.GONE
        root.fitSystemBars()
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 55)

        record.refreshStatic(); record.refreshState()
        idleTick()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, RecordService::class.java), svcConn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        svc?.let { it.listener = null; it.setUiVisible(false) }
        if (svc != null) { try { unbindService(svcConn) } catch (_: Exception) {}; svc = null }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); svc?.findMixer() }

    override fun onResume() { super.onResume(); if (nav.selected == 1) files.refresh() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        files.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsOpen) closeSettings()
        else if (nav.selected != 0) { nav.select(0, true) }
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- navigation ----------
    private fun showTab(i: Int) {
        record.view.visibility = if (i == 0) View.VISIBLE else View.GONE
        files.view.visibility = if (i == 1) View.VISIBLE else View.GONE
        if (i == 1) files.refresh()
    }

    fun openSettings() {
        settingsOpen = true
        settingsPage.onOpen()
        settingsPage.view.visibility = View.VISIBLE
    }

    fun closeSettings() {
        settingsOpen = false
        settingsPage.view.visibility = View.GONE
        record.refreshStatic()
    }

    // ---------- service callbacks ----------
    override fun onTick() { record.render() }

    override fun onState() {
        val s = svc ?: return
        val was = lastState
        lastState = s.state
        record.refreshState()
        applyKeepScreen()
        if ((was == RecordService.State.RECORDING || was == RecordService.State.WAITING) &&
            s.state != RecordService.State.RECORDING && s.state != RecordService.State.WAITING) { files.refresh(); record.onStopped() }
        record.onStateChanged(was, s.state)
        if (settingsOpen) settingsPage.refresh()
        record.render()
    }

    override fun onWave() { record.wave.pushed() }

    override fun onNote(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    override fun onLog(line: String) { settingsPage.appendLog(line) }

    override fun onFilesChanged() { files.refresh() }

    override fun onTracks() { record.render() }

    // ---------- settings plumbing ----------
    /** A setting changed: [needsRearm] settings (rate, stems, pre-roll, feedback, auto-start) restart the engine. */
    fun settingsChanged(needsRearm: Boolean) {
        if (needsRearm) svc?.reconfigure() else svc?.updateForeground()
        applyKeepScreen()
        record.refreshStatic(); record.refreshState()
    }

    private fun applyKeepScreen() {
        val on = settings.keepScreenOn && (svc?.state ?: RecordService.State.IDLE) != RecordService.State.IDLE
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun idleTick() {
        record.idleTick()
        if (settingsOpen) settingsPage.refresh()
        ui.postDelayed({ idleTick() }, 500)
    }
}
