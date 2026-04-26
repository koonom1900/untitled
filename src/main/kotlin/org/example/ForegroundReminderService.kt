package org.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class ForegroundReminderService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val seconds = intent?.getLongExtra("seconds", 0) ?: 0
        val message = intent?.getStringExtra("message") ?: ""

        // 1. 立即创建并启动前台通知（这是安卓系统的强制要求）
        startForeground(NOTIFICATION_ID, createForegroundNotification())

        // 2. 模拟后台定时任务
        if (seconds > 0) {
            handler.postDelayed({
                sendReminderNotification(message)
                // 提醒完成后，可以根据需要停止服务
                // stopForeground(STOP_FOREGROUND_DETACH)
                // stopSelf()
            }, seconds * 1000)
        }

        return START_NOTICKY
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("提醒服务运行中")
            .setContentText("应用正在后台为您监控提醒任务")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true) // 设置为常驻
            .setPriority(NotificationCompat.PRIORITY_LOW) // 前台服务本身的通知可以低优先级，不打扰用户
            .build()
    }

    private fun sendReminderNotification(message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("定时提醒")
            .setContentText(message)
            .setSmallIcon(applicationInfo.icon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "reminder_channel"
        private const val START_NOTICKY = 1 // START_STICKY
    }
}
