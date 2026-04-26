package org.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "需要通知权限才能发送提醒", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val etSeconds = findViewById<EditText>(R.id.etSeconds)
        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSchedule = findViewById<Button>(R.id.btnSchedule)

        btnSchedule.setOnClickListener {
            val secondsText = etSeconds.text.toString().trim()
            val message = etMessage.text.toString().trim()

            if (secondsText.isEmpty()) {
                Toast.makeText(this, "请输入延迟时间", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (message.isEmpty()) {
                Toast.makeText(this, "请输入提醒内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val seconds = secondsText.toLongOrNull()
            if (seconds == null || seconds <= 0) {
                Toast.makeText(this, "请输入有效的秒数（大于0）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            scheduleNotification(seconds, message)
            Toast.makeText(this, "${seconds} 秒后将发送提醒", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NotificationWorker.CHANNEL_ID,
                "定时提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "用于显示定时提醒通知"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun scheduleNotification(seconds: Long, message: String) {
        val intent = Intent(this, ForegroundReminderService::class.java).apply {
            putExtra("seconds", seconds)
            putExtra("message", message)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
