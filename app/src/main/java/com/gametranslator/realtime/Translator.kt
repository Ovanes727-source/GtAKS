
package com.gametranslator.realtime

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class Translator(context: Context) {

    private val appContext = context.applicationContext
    private var translator: Translator? = null
    private var modelReady = false

    init {
        initializeTranslator()
    }

    private fun initializeTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()

        translator = Translation.getClient(options)

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        translator?.downloadModelIfNeeded(conditions)
            ?.addOnSuccessListener {
                modelReady = true
            }
            ?.addOnFailureListener {
                modelReady = false
            }
    }

    fun translate(text: String, onResult: (String) -> Unit) {
        if (text.isBlank()) {
            onResult("")
            return
        }

        if (translator == null) {
            onResult("Ошибка перевода: инициализация не завершена")
            return
        }

        translator?.translate(text)
            ?.addOnSuccessListener { translatedText ->
                onResult(translatedText)
            }
            ?.addOnFailureListener { exception ->
                onResult("Ошибка перевода: ${'$'}{exception.message ?: "неизвестная ошибка"}")
            }
    }

    fun destroy() {
        try {
            translator?.close()
            translator = null
        } catch (_: Exception) {
        }
    }
}
