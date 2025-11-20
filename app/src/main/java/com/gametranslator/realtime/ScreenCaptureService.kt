package com.gametranslator.realtime

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private val isCapturing = AtomicBoolean(false)
    private var captureJob: Job? = null
    
    private val ocrProcessor: OCRProcessor by lazy { OCRProcessor(this.applicationContext) }
    private val translator: Translator by lazy { Translator(this.applicationContext) }
    private val cacheManager: CacheManager by lazy { CacheManager(this.applicationContext) }
    
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "screen_capture_channel"
        const val ACTION_TRANSLATION_RESULT = "TRANSLATION_RESULT"
        const val ACTION_CAPTURE_ERROR = "CAPTURE_ERROR"
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            startForeground(NOTIFICATION_ID, createNotification())
            startScreenCapture(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Захват экрана для перевода",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Захват игровых субтитров для перевода"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Game Translator")
            .setContentText("Перевод игровых субтитров активен")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun startScreenCapture(intent: Intent) {
        if (isCapturing.get()) return

        val resultCode = intent.getIntExtra("resultCode", 0)
        val resultData = intent.getParcelableExtra<Intent>("data")
        
        if (resultCode == 0 || resultData == null) {
            sendError("Неверные параметры захвата")
            return
        }

        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            setupVirtualDisplay()
            startCaptureLoop()
            isCapturing.set(true)
        } catch (e: Exception) {
            sendError("Ошибка запуска захвата: ${e.message}")
        }
    }

    fun stopCapture() {
        if (!isCapturing.get()) return
        
        isCapturing.set(false)
        captureJob?.cancel()
        
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        stopForeground(true)
        stopSelf()
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val density = metrics.densityDpi
        val width = metrics.widthPixels / 2 // Уменьшаем разрешение для скорости
        val height = metrics.heightPixels / 2

        imageReader = ImageReader.newInstance(width, height, 0x1, 2) // RGBA_8888
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GameTranslatorCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun startCaptureLoop() {
        captureJob = CoroutineScope(Dispatchers.Default).launch {
            while (isCapturing.get() && isActive) {
                try {
                    processFrame()
                    delay(200) // Увеличиваем частоту до 5 кадров в секунду
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        sendError("Ошибка обработки кадра: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun processFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val bitmap = image.toBitmap()
            val subtitleRegion = extractSubtitleRegion(bitmap)
            
            processSubtitleText(subtitleRegion)
            
            bitmap.recycle()
        } finally {
            image.close()
        }
    }

    private fun extractSubtitleRegion(bitmap: Bitmap): Bitmap {
        // Адаптивное определение области субтитров
        val height = bitmap.height
        val subtitleHeight = height / 4 // Нижняя четверть экрана
        return Bitmap.createBitmap(bitmap, 0, height - subtitleHeight, bitmap.width, subtitleHeight)
    }

    private fun processSubtitleText(bitmap: Bitmap) {
        ocrProcessor.extractText(bitmap) { recognizedText ->
            if (recognizedText.isNotBlank() && isNewText(recognizedText)) {
                // Кэширование и перевод
                val cached = cacheManager.getTranslation(recognizedText)
                if (cached != null) {
                    sendTranslation(recognizedText, cached)
                } else {
                    translator.translate(recognizedText) { translatedText ->
                        if (translatedText.isNotBlank()) {
                            cacheManager.saveTranslation(recognizedText, translatedText)
                            sendTranslation(recognizedText, translatedText)
                        }
                    }
                }
            }
        }
    }

    private val lastProcessedText = StringBuilder()
    private fun isNewText(text: String): Boolean {
        // Простая проверка на новые субтитры
        if (text == lastProcessedText.toString()) return false
        lastProcessedText.clear()
        lastProcessedText.append(text)
        return true
    }

    private fun sendTranslation(original: String, translated: String) {
        val intent = Intent(ACTION_TRANSLATION_RESULT).apply {
            putExtra("original_text", original)
            putExtra("translated_text", translated)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendError(message: String) {
        val intent = Intent(ACTION_CAPTURE_ERROR).apply {
            putExtra("error_message", message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        ocrProcessor.destroy()
        translator.destroy()
        cacheManager.closeDB()
    }
}

// Расширение для конвертации Image в Bitmap
fun Image.toBitmap(): Bitmap {
    val planes = this.planes
    val buffer = planes[0].buffer
    val pixelStride = planes[0].pixelStride
    val rowStride = planes[0].rowStride
    val rowPadding = rowStride - pixelStride * this.width

    val bitmap = Bitmap.createBitmap(
        this.width + rowPadding / pixelStride,
        this.height,
        Bitmap.Config.ARGB_8888
    )
    bitmap.copyPixelsFromBuffer(buffer)
    return bitmap
}
