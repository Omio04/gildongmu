package com.drivepet.app

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * The pet's voice. Uses the Android system text-to-speech, not the Pleos TextToSpeech SDK
 * (that one was "Coming Soon" in the Playground when this was written). Keep it behind this class so it can be swapped.
 * While driving the pet only gets one short line at a time: [minGapMs] spaces them out.
 */
class PetVoice(context: Context, private val minGapMs: Long = 7_000L) {
    private var ready = false
    private var lastAt = Long.MIN_VALUE / 2
    private lateinit var tts: TextToSpeech

    init {
        // the callback comes back asynchronously, after `tts` is assigned
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts.setLanguage(Locale.KOREAN)
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    /** a reaction line while driving: dropped if the last one was too recent */
    fun sayShort(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < minGapMs) return
        lastAt = now
        speak(text)
    }

    fun speak(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pet")
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}
