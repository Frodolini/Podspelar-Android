package no.frodolini.podspelar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Lastar ned episodar éin om gongen. Køyrer som forgrunnsteneste med eit varsel,
 * slik at nedlastinga held fram sjølv om ein går ut av appen.
 */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "nedlasting"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_ADD = "legg-til"
        private const val ACTION_CANCEL = "avbryt"

        /** Siste feilmelding, vist i appen. */
        var error by mutableStateOf<String?>(null)

        fun start(ctx: Context, e: Episode) {
            if (Store.isDownloaded(e) || Store.progress.containsKey(e.id)) return
            error = null
            Store.progress[e.id] = 0f
            val i = Intent(ctx, DownloadService::class.java).setAction(ACTION_ADD)
                .putExtra("episode", episodeToJson(e).toString())
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context, e: Episode) {
            val i = Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra("id", e.id)
            runCatching { ctx.startService(i) }
            Store.progress.remove(e.id)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = ArrayDeque<Episode>()
    private var current: Episode? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Nedlasting", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ADD -> {
                val e = runCatching { episodeFromJson(JSONObject(intent.getStringExtra("episode")!!)) }.getOrNull()
                // Forgrunnstenesta må melda seg med varsel med ein gong ho er starta.
                foreground(e?.title ?: "Lastar ned", 0)
                if (e != null) queue.addLast(e)
                next()
            }
            ACTION_CANCEL -> {
                val id = intent.getStringExtra("id")
                queue.removeAll { it.id == id }
                if (current?.id == id) job?.cancel()
                if (current == null && queue.isEmpty()) stopSelf()
            }
            else -> if (current == null && queue.isEmpty()) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun next() {
        if (current != null) return
        val e = queue.removeFirstOrNull()
        if (e == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        current = e
        job = scope.launch {
            val part = File(Store.downloadDir, "${e.id}.del")
            try {
                download(e, part)
                val target = Store.fileFor(e)
                target.delete()
                if (!part.renameTo(target)) throw java.io.IOException("Klarte ikkje lagra fila")
                Store.addDownload(e)
            } catch (ex: kotlinx.coroutines.CancellationException) {
                part.delete()
                Store.progress.remove(e.id)
            } catch (ex: Exception) {
                part.delete()
                Store.progress.remove(e.id)
                error = "Nedlastinga av «${e.title}» feila: ${ex.message ?: ex.javaClass.simpleName}"
            }
            current = null
            next()
        }
    }

    private suspend fun download(e: Episode, part: File) = withContext(Dispatchers.IO) {
        val c = openUrl(e.audioUrl)
        try {
            val total = c.contentLengthLong
            var done = 0L
            var lastShown = -1
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastShown) {
                            lastShown = pct
                            withContext(Dispatchers.Main) {
                                Store.progress[e.id] = pct / 100f
                                foreground(e.title, pct)
                            }
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw java.io.IOException("Sambandet vart brote")
        } finally {
            c.disconnect()
        }
    }

    private fun foreground(title: String, pct: Int) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val waiting = queue.size
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (waiting > 0) "Lastar ned · $waiting i kø" else "Lastar ned")
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    override fun onDestroy() {
        scope.cancel()
        current?.let { Store.progress.remove(it.id) }
        queue.forEach { Store.progress.remove(it.id) }
        super.onDestroy()
    }
}
