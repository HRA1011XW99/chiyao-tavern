package xyz.chiyao.tavern

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the app process in the foreground state while SillyTavern runs, so a quick trip to another app
 * does not get the server killed. "退出" in the notification stops the server and closes the app.
 */
class TavernService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            Tavern.quit()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        foreground()
        return START_NOT_STICKY
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "运行状态", NotificationManager.IMPORTANCE_MIN).apply {
            description = "SillyTavern 运行时显示"
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val settings = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val quit = PendingIntent.getService(this, 1,
            Intent(this, TavernService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("吃药酒馆")
            .setContentText("SillyTavern 正在运行")
            .setSmallIcon(R.drawable.ic_notice)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "设置", settings).build())
            .addAction(Notification.Action.Builder(null, "退出", quit).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
    }

    companion object {
        const val CHANNEL = "running"
        const val ID = 1
        const val ACTION_QUIT = "xyz.chiyao.tavern.QUIT"
    }
}
