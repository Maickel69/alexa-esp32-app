package com.example.alexaesp32.data

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsManager(context: Context) {
    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("es", "ES"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
                isReady = true
            }
        }
    }

    fun hablar(texto: String) {
        if (isReady && texto.isNotBlank()) {
            tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "AlexaUtterance")
        }
    }

    fun parar() {
        tts?.stop()
    }

    fun liberar() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
