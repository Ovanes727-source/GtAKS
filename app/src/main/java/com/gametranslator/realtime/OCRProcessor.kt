
package com.gametranslator.realtime

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OCRProcessor(context: Context) {

    private val appContext = context.applicationContext
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun extractText(frame: Bitmap, onResult: (String) -> Unit) {
        try {
            val image = InputImage.fromBitmap(frame, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text ?: ""
                    onResult(text)
                }
                .addOnFailureListener { e ->
                    onResult("")
                }
        } catch (e: Exception) {
            onResult("")
        }
    }

    fun destroy() {
        try {
            textRecognizer.close()
        } catch (_: Exception) {
        }
    }
}
