package com.home.tvremote.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wraps Android's speech recognizer. One recognizer is created and reused: destroying and
 * re-creating it on every tap is what made voice randomly report "busy"/"not available".
 */
class VoiceInput(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    /** All the recognizer's guesses, best first. */
    private val onResult: (List<String>) -> Unit,
    private val onError: (String) -> Unit,
    private val onEnd: () -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var retried = false
    private var active = false
    /** Words to steer recognition toward (app names, commands). */
    var hints: List<String> = emptyList()

    val available get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        retried = false
        listen()
    }

    private fun listen() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
            it.setRecognitionListener(listener)
        }
        r.cancel() // end any previous session cleanly before starting a new one
        active = true
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (Build.VERSION.SDK_INT >= 33 && hints.isNotEmpty()) {
                putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints.take(100)))
            }
        })
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(b: Bundle) { b.all().firstOrNull()?.let(onPartial) }
        override fun onResults(b: Bundle) {
            active = false
            val all = b.all()
            if (all.isEmpty()) onError("Didn't catch that — try again") else onResult(all)
            onEnd()
        }
        override fun onError(code: Int) {
            if (!active) return
            // The recognizer service is sometimes still winding down from the last request;
            // a short wait and one retry fixes it instead of showing an error.
            if (!retried && code in RETRYABLE) {
                retried = true
                if (code == SpeechRecognizer.ERROR_CLIENT || code == ERROR_SERVER_DISCONNECTED) { recognizer?.destroy(); recognizer = null }
                main.postDelayed({ if (active) listen() }, 350)
                return
            }
            active = false
            onError(message(code))
            onEnd()
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rms: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    fun stop() { active = false; recognizer?.cancel() }

    fun release() { active = false; recognizer?.destroy(); recognizer = null }

    private fun Bundle.all() = getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }.orEmpty()

    companion object {
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_TOO_MANY_REQUESTS = 10
        private val RETRYABLE = setOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT, ERROR_SERVER_DISCONNECTED, ERROR_TOO_MANY_REQUESTS)

        fun message(code: Int) = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — try again"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs an internet connection"
            SpeechRecognizer.ERROR_AUDIO -> "The microphone is busy (another app may be using it)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> "Speech service is busy — tap the mic again"
            else -> "Voice hiccup (code $code) — tap the mic again"
        }
    }
}
