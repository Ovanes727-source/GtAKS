
package com.gametranslator.realtime

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var translateButton: Button
    private lateinit var translationText: TextView
    private lateinit var statusText: TextView
    private lateinit var logoView: ImageView

    private var isTranslating = false
    private val ocrProcessor: OCRProcessor by lazy { OCRProcessor(this.applicationContext) }
    private val translator: Translator by lazy { Translator(this.applicationContext) }
    private val ttsManager: TTSManager by lazy { TTSManager(this.applicationContext) }
    private val cacheManager: CacheManager by lazy { CacheManager(this.applicationContext) }

    private var translationJob: Job? = null

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
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        translateButton = findViewById(R.id.btn_translate)
        translationText = findViewById(R.id.translation_text)
        statusText = findViewById(R.id.status_text)
        logoView = findViewById(R.id.logo)

        translateButton.setOnClickListener {
            if (allPermissionsGranted()) {
                toggleTranslation()
            } else {
                requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
            }
        }
    }

    private fun toggleTranslation() {
        isTranslating = !isTranslating
        if (isTranslating) startTranslation() else stopTranslation()
        updateUI()
    }

    private fun startTranslation() {
        translateButton.setBackgroundResource(R.drawable.btn_translate_on)
        statusText.text = "Перевод: Включён"
        translationJob = lifecycleScope.launch {
            while (isActive && isTranslating) {
                val frameBitmap: Bitmap? = null // TODO: интеграция Remote Play / MediaProjection
                if (frameBitmap != null) {
                    processFrame(frameBitmap)
                }
                delay(500)
            }
        }
    }

    private fun stopTranslation() {
        translateButton.setBackgroundResource(R.drawable.btn_translate_off)
        statusText.text = "Перевод: Выключен"
        ttsManager.stop()
        translationJob?.cancel()
        translationJob = null
    }

    private fun updateUI() {
        translateButton.text = if (isTranslating) "Остановить перевод" else "Начать перевод"
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun processFrame(frame: Bitmap) {
        ocrProcessor.extractText(frame) { recognized ->
            if (recognized.isBlank()) return@extractText

            val cached = cacheManager.getTranslation(recognized)
            if (cached != null) {
                runOnUiThread {
                    translationText.text = cached
                }
                ttsManager.speak(cached)
                return@extractText
            }

            translator.translate(recognized) { translated ->
                if (translated.isNotBlank()) {
                    cacheManager.saveTranslation(recognized, translated)
                }
                runOnUiThread {
                    translationText.text = translated
                }
                ttsManager.speak(translated)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        translationJob?.cancel()
        ocrProcessor.destroy()
        translator.destroy()
        ttsManager.destroy()
        cacheManager.closeDB()
    }
}
