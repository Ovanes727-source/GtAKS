package com.gametranslator.realtime

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var translateButton: Button
    private lateinit var translationText: TextView
    private lateinit var statusText: TextView
    
    private var isTranslating = false
    private var translationJob: Job? = null
    private var captureService: ScreenCaptureService? = null
    private var isServiceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ScreenCaptureService.LocalBinder
            captureService = binder.getService()
            isServiceBound = true
            updateUI()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isServiceBound = false
            captureService = null
        }
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val granted = REQUIRED_PERMISSIONS.all { perm ->
                perms[perm] == true
            }
            if (!granted) {
                Toast.makeText(this, "Нужны все разрешения для работы приложения", Toast.LENGTH_LONG).show()
            } else if (isTranslating) {
                startTranslation()
            }
        }

    private val translationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScreenCaptureService.ACTION_TRANSLATION_RESULT -> {
                    val translatedText = intent.getStringExtra("translated_text")
                    val originalText = intent.getStringExtra("original_text")
                    
                    translatedText?.let { text ->
                        runOnUiThread {
                            translationText.text = text
                            // Автоматическое озвучивание перевода
                            ttsManager.speak(text)
                        }
                    }
                }
                ScreenCaptureService.ACTION_CAPTURE_ERROR -> {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Ошибка захвата экрана", Toast.LENGTH_SHORT).show()
                        stopTranslation()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        translateButton = findViewById(R.id.btn_translate)
        translationText = findViewById(R.id.translation_text)
        statusText = findViewById(R.id.status_text)

        // Восстановление состояния при повороте экрана
        if (savedInstanceState != null) {
            isTranslating = savedInstanceState.getBoolean("isTranslating", false)
        }

        translateButton.setOnClickListener {
            if (isTranslating) {
                stopTranslation()
            } else {
                if (allPermissionsGranted()) {
                    startTranslation()
                } else {
                    requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
                }
            }
        }

        // Регистрация приемника трансляций
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_TRANSLATION_RESULT)
            addAction(ScreenCaptureService.ACTION_CAPTURE_ERROR)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(translationReceiver, filter)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isTranslating", isTranslating)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        // Не останавливаем перевод при сворачивании, только обновляем UI
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Останавливаем только при полном закрытии приложения
        stopTranslation()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(translationReceiver)
        try {
            if (isServiceBound) {
                unbindService(connection)
                isServiceBound = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startTranslation() {
        if (isTranslating) return
        
        isTranslating = true
        
        val intent = Intent(this, ScreenCaptureService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        // Запускаем сервис в foreground для стабильной работы
        ContextCompat.startForegroundService(this, intent)
        
        updateUI()
        Toast.makeText(this, "Перевод запущен", Toast.LENGTH_SHORT).show()
    }

    private fun stopTranslation() {
        if (!isTranslating) return
        
        isTranslating = false
        
        captureService?.stopCapture()
        try {
            if (isServiceBound) {
                unbindService(connection)
                isServiceBound = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val intent = Intent(this, ScreenCaptureService::class.java)
        stopService(intent)
        
        updateUI()
        Toast.makeText(this, "Перевод остановлен", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        runOnUiThread {
            if (isTranslating) {
                translateButton.text = "Остановить перевод"
                translateButton.setBackgroundResource(R.drawable.btn_translate_on)
                statusText.text = "Перевод: Активен"
            } else {
                translateButton.text = "Начать перевод" 
                translateButton.setBackgroundResource(R.drawable.btn_translate_off)
                statusText.text = "Перевод: Выключен"
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
