package com.gildongmu.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Hands-free asking: the driver says a question, the server maps the words to one of the fixed questions.
 * Uses the system speech recognizer (Pleos' own STT SDK is not available yet); when the vehicle has no
 * recognizer, [available] is false and the button explains that instead of failing silently.
 */
class VoiceInput(
    private val activity: Activity,
    private val onHeard: (String) -> Unit,
    private val onProblem: (String) -> Unit,
    private val onListening: () -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    fun available() = SpeechRecognizer.isRecognitionAvailable(activity)

    fun listen() {
        if (!available()) {
            onProblem("이 차량에는 음성 인식이 없어요")
            return
        }
        stop()
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onListening()
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) onProblem("잘 듣지 못했어요") else onHeard(text)
                }
                override fun onError(error: Int) {
                    onProblem(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        "잘 듣지 못했어요" else "음성 인식을 쓸 수 없어요 ($error)")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }
}
