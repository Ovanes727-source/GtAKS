package com.gametranslator.realtime

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val utteranceQueue = ConcurrentLinkedQueue<String>()
    private var isSpeaking = false

    init {
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ru", "RU"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            
            // Настройка для быстрой работы
            tts?.setSpeechRate(1.1f) // Немного ускоряем речь
            tts?.setPitch(1.0f)
            
            // Обработчик прогресса для очереди
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    processNextInQueue()
                }

                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    processNextInQueue()
                }
            })
            
            isInitialized = true
            processNextInQueue() // Начинаем обработку очереди если есть элементы
        } else {
            isInitialized = false
        }
    }

    fun speak(text: String) {
        if (!isInitialized || text.isBlank()) return
        
        // Очищаем очередь если новый текст важнее (короткие субтитры)
        if (text.length < 50) {
            utteranceQueue.clear()
            stop()
        }
        
        utteranceQueue.offer(text)
        
        if (!isSpeaking) {
            processNextInQueue()
        }
    }

    private fun processNextInQueue() {
        if (!isInitialized || isSpeaking) return
        
        val nextText = utteranceQueue.poll()
        if (nextText != null) {
            val id = System.currentTimeMillis().toString()
            tts?.speak(nextText, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    fun stop() {
        try {
            utteranceQueue.clear()
            tts?.stop()
            isSpeaking = false
        } catch (_: Exception) {
        }
    }

    fun destroy() {
        try {
            stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (_: Exception) {
        }
    }
}
