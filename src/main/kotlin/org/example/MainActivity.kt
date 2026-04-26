package org.example

import android.app.AlarmManager
import android.app.PendingIntent
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val etMinutes = findViewById<EditText>(R.id.etMinutes)
        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSchedule = findViewById<Button>(R.id.btnSchedule)

        btnSchedule.setOnClickListener {
            val minutesText = etMinutes.text.toString().trim()
            val message = etMessage.text.toString().trim()

            if (minutesText.isEmpty()) {
                Toast.makeText(this, "请输入延迟时间", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (message.isEmpty()) {
                Toast.makeText(this, "请输入提醒内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val minutes = minutesText.toLongOrNull()
            if (minutes == null || minutes <= 0) {
                Toast.makeText(this, "请输入有效的分钟数（大于0）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            scheduleNotification(minutes, message)
            Toast.makeText(this, "${minutes} 分钟后将发送提醒", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleNotification(minutes: Long, message: String) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, NotificationReceiver::class.java).apply {
            putExtra(NotificationReceiver.EXTRA_MESSAGE, message)
        }
        val requestCode = System.currentTimeMillis().toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = System.currentTimeMillis() + minutes * 60_000L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}
