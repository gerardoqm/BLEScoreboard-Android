package com.gerardoqm.blescoreboard

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the match alive while the app is in the background: the Bluetooth link, the clock and its announcements.
 * Its ongoing notification shows the score, period and countdown (Android's take on the iOS Live Activity).
 */
class MatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as ScoreboardApp).manager
        val notification = pending ?: manager.buildMatchNotification()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
            running = true
        } catch (e: Exception) {
            Log.w("MatchService", "Could not start in the foreground", e)
            stopSelf()
        }
        if (!manager.inMatch) stop(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        private var running = false
        private var pending: Notification? = null

        /** Shows the notification, starting the service the first time. */
        fun update(context: Context, notification: Notification) {
            pending = notification
            if (running) {
                context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                return
            }
            // Android only lets an app start this service while it is on screen, which is when a match starts.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MatchService::class.java)) }
        }

        fun stop(context: Context) {
            pending = null
            if (!running) return
            running = false
            context.stopService(Intent(context, MatchService::class.java))
        }
    }
}
