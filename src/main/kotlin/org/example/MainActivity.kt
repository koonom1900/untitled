package org.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var selectedFolderUri: Uri? = null
    private lateinit var tvSelectedPath: TextView
    private lateinit var btnGenerateVideo: Button
    private lateinit var pbVideoGeneration: ProgressBar
    private lateinit var tvStatus: TextView

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "需要通知权限才能发送提醒", Toast.LENGTH_LONG).show()
        }
    }

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            selectedFolderUri = it
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            tvSelectedPath.text = "已选择: ${it.path}"
            btnGenerateVideo.isEnabled = true
        }
    }

    private val requestStoragePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "需要存储权限才能读取图片和保存视频", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()
        startForegroundService()

        checkPermissions()

        val etSeconds = findViewById<EditText>(R.id.etSeconds)
        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnSchedule = findViewById<Button>(R.id.btnSchedule)

        tvSelectedPath = findViewById(R.id.tvSelectedPath)
        btnGenerateVideo = findViewById(R.id.btnGenerateVideo)
        pbVideoGeneration = findViewById(R.id.pbVideoGeneration)
        tvStatus = findViewById(R.id.tvStatus)
        val btnSelectFolder = findViewById<Button>(R.id.btnSelectFolder)

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

        btnSelectFolder.setOnClickListener {
            selectFolderLauncher.launch(null)
        }

        btnGenerateVideo.setOnClickListener {
            generateTimeLapseVideo()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            requestStoragePermissions.launch(permissions.toTypedArray())
        }
    }

    private fun generateTimeLapseVideo() {
        val uri = selectedFolderUri ?: return
        val folder = DocumentFile.fromTreeUri(this, uri) ?: return
        
        val files = folder.listFiles()
            .filter { it.isFile && (it.name?.endsWith(".jpg", true) == true || it.name?.endsWith(".jpeg", true) == true) }
            .sortedByDescending { it.name }

        if (files.isEmpty()) {
            Toast.makeText(this, "文件夹内没有找到图片", Toast.LENGTH_SHORT).show()
            return
        }

        // 创建临时目录存放链接或拷贝的文件，因为FFmpegKit对SAF支持有限，最好使用文件路径
        val tempDir = File(cacheDir, "timelapse_temp")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        tvStatus.text = "正在准备图片..."
        pbVideoGeneration.visibility = View.VISIBLE
        pbVideoGeneration.isIndeterminate = true
        btnGenerateVideo.isEnabled = false

        Thread {
            try {
                // FFmpeg -i DSCF%04d.JPG 要求文件名连续。
                // 更好的办法是创建一个 concat 文件列表
                val listFile = File(tempDir, "input.txt")
                FileOutputStream(listFile).use { fos ->
                    files.forEach { docFile ->
                        // 将图片拷贝到临时目录，以便 FFmpeg 访问
                        val localFile = File(tempDir, docFile.name ?: "img.jpg")
                        contentResolver.openInputStream(docFile.uri)?.use { input ->
                            localFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        fos.write("file '${localFile.absolutePath}'\n".toByteArray())
                    }
                }

                val outputPath = File(
                    getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    "timelapse_${System.currentTimeMillis()}.mp4"
                ).absolutePath

                // 执行 FFmpeg 命令
                // 尝试使用 h264_mediacodec 以获得硬件加速和更好的兼容性 (libx264 在 LGPL 版本中通常不可用)
                val ffmpegCommand = "-f concat -safe 0 -r 24 -i \"${listFile.absolutePath}\" -c:v h264_mediacodec -pix_fmt yuv420p -y \"$outputPath\""

                tvStatus.post { tvStatus.text = "正在生成视频..." }

                FFmpegKit.executeAsync(ffmpegCommand) { session ->
                    val returnCode = session.returnCode
                    runOnUiThread {
                        pbVideoGeneration.visibility = View.GONE
                        btnGenerateVideo.isEnabled = true
                        if (ReturnCode.isSuccess(returnCode)) {
                            tvStatus.text = "视频生成成功: $outputPath"
                            Toast.makeText(this, "视频已保存至 Movies 目录", Toast.LENGTH_LONG).show()
                        } else {
                            val logs = session.allLogsAsString
                            Log.e("FFmpegKit", "Video generation failed with return code $returnCode. Logs: $logs")

                            // 如果 h264_mediacodec 也失败，尝试使用最基础的 mpeg4 编码器
                            if (logs.contains("Unknown encoder 'h264_mediacodec'")) {
                                tvStatus.text = "硬件编码器不可用，正在尝试基础编码器..."
                                retryWithMpeg4(listFile, outputPath)
                                return@runOnUiThread
                            }
                            
                            // 将日志写入文件
                            val logFile = File(
                                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                                "ffmpeg_log_${System.currentTimeMillis()}.txt"
                            )
                            try {
                                logFile.writeText(logs)
                                tvStatus.text = "生成失败，日志已保存至: ${logFile.absolutePath}"
                            } catch (e: Exception) {
                                tvStatus.text = "生成失败且无法写入日志文件"
                            }
                            
                            Toast.makeText(this, "生成失败，请查看日志文件", Toast.LENGTH_SHORT).show()
                        }
                        // 清理临时文件 (如果是重试，则由重试逻辑负责清理)
                        tempDir.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "发生错误: ${e.message}"
                    pbVideoGeneration.visibility = View.GONE
                    btnGenerateVideo.isEnabled = true
                }
            }
        }.start()
    }

    private fun retryWithMpeg4(listFile: File, outputPath: String) {
        val ffmpegCommand = "-f concat -safe 0 -r 24 -i \"${listFile.absolutePath}\" -c:v mpeg4 -y \"$outputPath\""
        
        pbVideoGeneration.visibility = View.VISIBLE
        btnGenerateVideo.isEnabled = false
        
        FFmpegKit.executeAsync(ffmpegCommand) { session ->
            val returnCode = session.returnCode
            runOnUiThread {
                pbVideoGeneration.visibility = View.GONE
                btnGenerateVideo.isEnabled = true
                if (ReturnCode.isSuccess(returnCode)) {
                    tvStatus.text = "视频生成成功 (mpeg4): $outputPath"
                    Toast.makeText(this, "视频已保存至 Movies 目录", Toast.LENGTH_LONG).show()
                } else {
                    val logs = session.allLogsAsString
                    Log.e("FFmpegKit", "Mpeg4 fallback failed: $logs")
                    val logFile = File(
                        getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                        "ffmpeg_log_retry_${System.currentTimeMillis()}.txt"
                    )
                    logFile.writeText(logs)
                    tvStatus.text = "所有编码器均失败，日志: ${logFile.absolutePath}"
                }
                // 最终清理
                listFile.parentFile?.deleteRecursively()
            }
        }
    }

    private fun startForegroundService() {
        val serviceIntent = Intent(this, ForegroundReminderService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
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
        val workRequest = OneTimeWorkRequestBuilder<NotificationWorker>()
            .setInitialDelay(seconds, TimeUnit.SECONDS)
            .setInputData(workDataOf(
                NotificationWorker.KEY_TITLE to "远程推送",
                NotificationWorker.KEY_MESSAGE to message
            ))
            .build()
        WorkManager.getInstance(this).enqueue(workRequest)
    }
}
