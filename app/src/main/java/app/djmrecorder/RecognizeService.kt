package app.djmrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process alive and shows a progress notification while [Recognition] jobs run (also after the app is closed or a
 * recording was just finalized). The jobs themselves run on [Recognition]'s own thread; this service only observes them.
 */
class RecognizeService : Service() {
    private lateinit var nm: NotificationManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Track recognition", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress while tracks are identified from a recording"; setShowBadge(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) Recognition.cancelAll()
        val n = build("Identifying tracks", "Starting...", 0, 0)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIF_ID, n)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        Recognition.hook = { job -> onJob(job) }
        if (Recognition.active() == 0) { finishUp(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }

    private fun onJob(job: RecognizeJob) {
        if (job.phase == "done") {
            if (job.auto) {
                val b = Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_rec)
                    .setContentTitle("Track recognition finished").setContentText(job.summary)
                    .setStyle(Notification.BigTextStyle().bigText(job.summary))
                    .setContentIntent(openApp()).setAutoCancel(true)
                try { nm.notify(DONE_ID, b.build()) } catch (_: Exception) {}
            }
            if (Recognition.active() == 0) finishUp()
            return
        }
        val text = if (job.phase == "queued") "Waiting..." else "Probe ${minOf(job.done + 1, maxOf(job.total, 1))}/${job.total} · ${job.found} track${if (job.found == 1) "" else "s"} found"
        try { nm.notify(NOTIF_ID, build("Identifying tracks", text, job.total, job.done)) } catch (_: Exception) {}
    }

    private fun finishUp() {
        Recognition.hook = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() { Recognition.hook = null; super.onDestroy() }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(title: String, text: String, max: Int, now: Int): Notification {
        val cancel = PendingIntent.getService(this, 21, Intent(this, RecognizeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_rec).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).setContentTitle(title).setContentText(text).setContentIntent(openApp())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_rec), "Cancel", cancel).build())
        if (max > 0) b.setProgress(max, now, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val CH = "recog"
        private const val NOTIF_ID = 20
        private const val DONE_ID = 22
        private const val ACTION_CANCEL = "app.djmrecorder.RECOG_CANCEL"
    }
}
