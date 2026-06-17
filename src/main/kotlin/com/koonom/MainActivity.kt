package com.koonom

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
            .sortedBy { it.name }

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
                // 优化编码策略：
                // 1. 优先尝试 hevc_mediacodec (H.265)，它在相同码率下画质更好
                // 2. 增加码率 (-b:v 8M) 提高清晰度
                // 3. 设置 profile 为 high (如果支持)
                val ffmpegCommand = "-f concat -safe 0 -r 24 -i \"${listFile.absolutePath}\" " +
                        "-c:v hevc_mediacodec -b:v 8M -pix_fmt yuv420p -y \"$outputPath\""

                tvStatus.post { tvStatus.text = "正在生成视频 (H.265)..." }

                FFmpegKit.executeAsync(ffmpegCommand) { session ->
                    val returnCode = session.returnCode
                    val logs = session.allLogsAsString
                    runOnUiThread {
                        if (ReturnCode.isSuccess(returnCode)) {
                            pbVideoGeneration.visibility = View.GONE
                            btnGenerateVideo.isEnabled = true
                            tvStatus.text = "视频生成成功: $outputPath"
                            Toast.makeText(this, "视频已保存至 Movies 目录", Toast.LENGTH_LONG).show()
                        } else {
                            Log.e("FFmpegKit", "HEVC failed, trying H.264. Logs: $logs")
                            // 如果 H.265 不支持，回退到优化的 H.264
                            retryWithH264Optimized(listFile, outputPath, tempDir)
                        }
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

    private fun retryWithH264Optimized(listFile: File, outputPath: String, tempDir: File) {
        // 增加码率到 8M，并尝试使用 high profile
        val ffmpegCommand = "-f concat -safe 0 -r 24 -i \"${listFile.absolutePath}\" " +
                "-c:v h264_mediacodec -b:v 8M -pix_fmt yuv420p -y \"$outputPath\""
        
        tvStatus.text = "正在使用 H.264 优化模式..."
        
        FFmpegKit.executeAsync(ffmpegCommand) { session ->
            val returnCode = session.returnCode
            val logs = session.allLogsAsString
            runOnUiThread {
                if (ReturnCode.isSuccess(returnCode)) {
                    pbVideoGeneration.visibility = View.GONE
                    btnGenerateVideo.isEnabled = true
                    tvStatus.text = "视频生成成功 (H.264): $outputPath"
                    Toast.makeText(this, "视频已保存至 Movies 目录", Toast.LENGTH_LONG).show()
                    tempDir.deleteRecursively()
                } else {
                    Log.e("FFmpegKit", "H.264 failed, trying MPEG4. Logs: $logs")
                    retryWithMpeg4(listFile, outputPath, tempDir)
                }
            }
        }
    }

    private fun retryWithMpeg4(listFile: File, outputPath: String, tempDir: File) {
        // MPEG4 也稍微增加一点码率，虽然它本身效率较低
        val ffmpegCommand = "-f concat -safe 0 -r 24 -i \"${listFile.absolutePath}\" -c:v mpeg4 -b:v 4M -y \"$outputPath\""
        
        tvStatus.text = "硬件加速不可用，正在使用基础编码器..."
        
        FFmpegKit.executeAsync(ffmpegCommand) { session ->
            val returnCode = session.returnCode
            runOnUiThread {
                pbVideoGeneration.visibility = View.GONE
                btnGenerateVideo.isEnabled = true
                if (ReturnCode.isSuccess(returnCode)) {
                    tvStatus.text = "视频生成成功 (基础模式): $outputPath"
                    Toast.makeText(this, "视频已保存至 Movies 目录", Toast.LENGTH_LONG).show()
                } else {
                    val logs = session.allLogsAsString
                    val logFile = File(
                        getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                        "ffmpeg_log_${System.currentTimeMillis()}.txt"
                    )
                    try {
                        logFile.writeText(logs)
                        tvStatus.text = "所有编码尝试均失败，日志: ${logFile.absolutePath}"
                    } catch (e: Exception) {
                        tvStatus.text = "生成失败且无法写入日志"
                    }
                }
                tempDir.deleteRecursively()
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
