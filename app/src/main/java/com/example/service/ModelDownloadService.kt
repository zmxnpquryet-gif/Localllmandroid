package com.example.service

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.engine.DownloadStatus
import com.example.engine.ModelDownloader
import com.example.model.LlmModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground Service for robust background model downloads.
 * Displays progress notifications with real-time speed (MB/s) and cancel action.
 * Holds a partial wake-lock so screen-off or background states do not terminate downloads.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val TAG = "ModelDownloadService"
        const val CHANNEL_ID = "model_download_channel"
        const val NOTIFICATION_ID = 9001

        const val ACTION_START_DOWNLOAD = "com.example.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.action.CANCEL_DOWNLOAD"
        const val EXTRA_MODEL_ID = "extra_model_id"

        // Active download status shared with ViewModels / UI
        private val _currentDownloadStatus = MutableStateFlow<DownloadStatus?>(null)
        val currentDownloadStatus: StateFlow<DownloadStatus?> = _currentDownloadStatus.asStateFlow()

        // Active target model reference
        var activeDownloadingModel: LlmModel? = null
            private set

        fun startDownload(context: Context, model: LlmModel) {
            activeDownloadingModel = model
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_MODEL_ID, model.id)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start ModelDownloadService: ${e.message}", e)
            }
        }

        fun cancelDownload(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            try {
                context.startService(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to send cancelDownload intent: ${e.message}", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private val modelDownloader = ModelDownloader()
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null
    private var lastNotificationUpdateTime = 0L

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = activeDownloadingModel
        val initialNotification = buildNotification(
            title = if (model != null) "다운로드 시작: ${model.name}" else "다운로드 서비스",
            content = if (model != null) "${model.name} 다운로드를 준비하고 있습니다..." else "서비스 준비 중...",
            progress = 0,
            isIndeterminate = true
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Initial startForeground failed: ${e.message}", e)
        }

        val action = intent?.action
        when (action) {
            ACTION_START_DOWNLOAD -> {
                if (model != null) {
                    startForegroundDownload(model)
                } else {
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Throwable) {}
                    stopSelf()
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                handleCancelDownload(modelId)
            }
            else -> {
                if (model == null && downloadJob == null) {
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Throwable) {}
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundDownload(model: LlmModel) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            try {
                val modelsDir = File(filesDir, "models").apply { if (!exists()) mkdirs() }

                modelDownloader.downloadUnifiedBundle(model, modelsDir).collect { status ->
                    _currentDownloadStatus.value = status

                    val now = System.currentTimeMillis()
                    if (now - lastNotificationUpdateTime >= 800L || status.isCompleted || status.errorMessage != null) {
                        lastNotificationUpdateTime = now
                        try {
                            updateNotification(model, status)
                        } catch (e: Throwable) {
                            Log.w(TAG, "Notification update failed: ${e.message}")
                        }
                    }

                    if (status.isCompleted) {
                        Log.i(TAG, "Download completed for ${model.name}")
                        releaseWakeLock()
                        try {
                            stopForeground(STOP_FOREGROUND_DETACH)
                        } catch (_: Throwable) {}
                        stopSelf()
                    } else if (status.errorMessage != null) {
                        Log.e(TAG, "Download failed: ${status.errorMessage}")
                        releaseWakeLock()
                        try {
                            stopForeground(STOP_FOREGROUND_DETACH)
                        } catch (_: Throwable) {}
                        stopSelf()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error during download stream: ${e.message}", e)
                _currentDownloadStatus.value = DownloadStatus(
                    modelId = model.id,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    speedText = "오류",
                    errorMessage = e.localizedMessage ?: "다운로드 중 오류 발생",
                    isCompleted = false
                )
                releaseWakeLock()
                try {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } catch (_: Throwable) {}
                stopSelf()
            }
        }
    }

    private fun handleCancelDownload(modelId: String?) {
        Log.i(TAG, "Canceling download for model: $modelId")
        downloadJob?.cancel()
        downloadJob = null
        activeDownloadingModel = null
        _currentDownloadStatus.value = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(model: LlmModel, status: DownloadStatus) {
        val notifManager = notificationManager ?: return

        val notification = if (status.isCompleted) {
            buildNotification(
                title = "다운로드 완료",
                content = "${model.name} 파일 다운로드가 완료되었습니다.",
                progress = 100,
                isIndeterminate = false,
                isOngoing = false
            )
        } else if (status.errorMessage != null) {
            buildNotification(
                title = "다운로드 오류",
                content = status.errorMessage,
                progress = 0,
                isIndeterminate = false,
                isOngoing = false
            )
        } else {
            val percent = (status.progress * 100f).toInt()
            val etaText = if (status.etaSeconds > 0) " • 남은시간: ${status.etaSeconds}초" else ""
            val bodyText = "${status.speedText}$etaText (${percent}%)"

            buildNotification(
                title = "모델 다운로드 중: ${model.name}",
                content = bodyText,
                progress = percent,
                isIndeterminate = false,
                isOngoing = true,
                modelId = model.id
            )
        }

        notifManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        title: String,
        content: String,
        progress: Int,
        isIndeterminate: Boolean,
        isOngoing: Boolean = true,
        modelId: String? = null
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(com.example.R.mipmap.ic_launcher)
            .setContentIntent(pendingOpenApp)
            .setOngoing(isOngoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress > 0 || isIndeterminate) {
            builder.setProgress(100, progress, isIndeterminate)
        }

        // Add Cancel Action if ongoing and modelId is provided
        if (isOngoing && modelId != null) {
            val cancelIntent = Intent(this, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            val pendingCancel = PendingIntent.getService(
                this,
                1,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_delete,
                "취소",
                pendingCancel
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "모델 백그라운드 다운로드",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AI 모델 가중치 파일 다운로드 진행 상황 및 알림"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalLLM::DownloadWakeLock").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // 4 hours maximum
            }
            Log.d(TAG, "Acquired partial wake lock for background download")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Released partial wake lock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
    }
}
