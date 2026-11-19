package app.djmrecorder

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** The Files tab: recordings as cards. The list scrolls (it is a list); the file facts are gathered off the main thread. */
class FilesPage(private val a: MainActivity) {
    val view = LinearLayout(a)
    private val list = LinearLayout(a)
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var gen = 0

    private class Item(val file: File, val name: String, val date: String, val dur: String, val size: String, val stems: Boolean,
                       val drops: Int, val recon: Int, val tracks: Int)

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setPadding(a.px(20f), 0, a.px(20f), 0)
        view.addView(a.tv("Files", 19f, C.TEXT, Fonts.LIGHT).apply { gravity = Gravity.CENTER_VERTICAL },
            lp(-1, a.px(52f)))
        list.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list) }
        view.addView(sv, lp(-1, 0, weight = 1f))
    }

    fun refresh() {
        val g = ++gen
        val dir = a.getExternalFilesDir(null)
        if (worker.isShutdown) return
        worker.execute {
            val items = ArrayList<Item>()
            try {
                val files = dir?.listFiles { f -> f.name.endsWith(".wav") && !f.name.contains("-12ch") }
                    ?.sortedByDescending { it.lastModified() }.orEmpty()
                for (f in files) {
                    val ev = Rec.loadEvents(f)
                    items.add(Item(f, Rec.displayName(Rec.base(f)), Rec.date(f),
                        WavInfo.read(f)?.let { Rec.clock(it.seconds.toInt()) } ?: "?", Rec.size(f.length()),
                        File(f.parent, f.name.removeSuffix(".wav") + "-12ch.wav").exists(),
                        ev.count { !it.reconnect }, ev.count { it.reconnect }, TrackFile.load(f).size))
                }
            } catch (_: Exception) {}
            ui.post { if (g == gen) show(items) }
        }
    }

    private fun badge(text: String, color: Int): TextView = a.tv(text, 11f, color, Fonts.MED).apply {
        setPadding(a.px(8f), a.px(3f), a.px(8f), a.px(3f)); background = a.bg(0, 8f, color)
    }

    private fun show(items: List<Item>) {
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(a.tv("No recordings yet.\nPress record on the Record tab.", 14f, C.DIM).apply { setLineSpacing(0f, 1.2f) }, lp(top = a.px(8f)))
            return
        }
        for (it in items) {
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                background = a.ripple(a.bg(C.CARD, 16f, C.LINE)); setPadding(a.px(16f), a.px(14f), a.px(16f), a.px(14f))
            }
            val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(a.tv(it.name, 16f, C.TEXT, Fonts.MED), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(a.icon(R.drawable.ic_chevron_right, C.DIM, 18f))
            card.addView(top)
            card.addView(a.tv("${it.date}  ·  ${it.dur}  ·  ${it.size}", 12.5f, C.DIM), lp(top = a.px(4f)))
            val badges = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            val m = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = a.px(6f) }
            if (it.stems) badges.addView(badge("STEMS", C.DIM), m)
            val dtxt = "${it.drops} dropout${if (it.drops == 1) "" else "s"}" + if (it.recon > 0) " · ${it.recon} reconnect${if (it.recon == 1) "" else "s"}" else ""
            badges.addView(badge(dtxt, if (it.drops > 0 || it.recon > 0) C.RED else C.GREEN), m)
            if (it.tracks > 0) badges.addView(badge("${it.tracks} TRACK${if (it.tracks == 1) "" else "S"}", C.CYAN), m)
            card.addView(badges, lp(top = a.px(10f)))
            val f = it.file
            card.setOnClickListener {
                if (f == a.svc?.currentFile) Toast.makeText(a, "Still recording", Toast.LENGTH_SHORT).show()
                else a.startActivity(Intent(a, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_PATH, f.absolutePath))
            }
            list.addView(card, lp(top = a.px(10f)))
        }
        list.addView(View(a), lp(-1, a.px(12f)))
    }

    fun destroy() { gen++; worker.shutdown() }
}
