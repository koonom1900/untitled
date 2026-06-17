package com.koonom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

class NotificationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val message = inputData.getString(KEY_MESSAGE) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "提醒"
        ensureChannelExists()
        Log.d("NotificationWorker", "开始执行后台通知任务: $message")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("NotificationWorker", "缺少通知权限")
                return Result.failure()
            }
        }

        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(applicationContext.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
            Log.d("NotificationWorker", "通知已成功发送")
        } catch (e: Exception) {
            Log.e("NotificationWorker", "发送通知失败", e)
            return Result.failure()
        }

        return Result.success()
    }

    private fun ensureChannelExists() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "定时提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "用于显示定时提醒通知"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "reminder_channel"
        const val KEY_MESSAGE = "message"
        const val KEY_TITLE = "title"
    }
}
