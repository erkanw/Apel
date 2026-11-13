package io.github.erkanw.vault.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

// Dictation into the message box with the phone's own speech recognizer (PLAN §6.4). The words land in the box to be
// read, changed and sent; they are never sent by themselves.

/** What happened to one dictation, as the recognizer reports it. */
interface DictationListener {
    /** How loud it is now, 0 to 1, for the bars. */
    fun onLevel(level: Float)

    /** The words so far (they may still change). */
    fun onPartial(text: String)

    /** The words, once the recognizer is done. Blank when nothing was heard. */
    fun onDone(text: String)

    fun onFailed(message: String)
}

/** One recognizer for the chat. Call everything on the main thread, and [release] when done with it. */
class Dictation(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    /** False when the phone has no speech recognizer: the mic is then hidden. */
    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(listener: DictationListener) {
        release()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(levelOfRms(rmsdB))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) listener.onDone("")
                else listener.onFailed(dictationFailure(error))
            }
            override fun onResults(results: Bundle?) = listener.onDone(firstResult(results))
            override fun onPartialResults(partialResults: Bundle?) = listener.onPartial(firstResult(partialResults))
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        )
    }

    /** Stops listening; the words heard so far still arrive in [DictationListener.onDone]. */
    fun finish() {
        recognizer?.stopListening()
    }

    /** Throws the dictation away. */
    fun cancel() {
        recognizer?.cancel()
        release()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}

/** The recognizer's loudness (about -2 to 10 dB) as 0 to 1 for the bars. */
fun levelOfRms(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

/** What a recognizer error means, in a line, with the one thing to do. */
fun dictationFailure(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Dictation needs the microphone. Allow it in the phone’s settings."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
        "The phone’s speech recognizer needs a connection for this language. Try again online."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Try again in a moment."
    SpeechRecognizer.ERROR_AUDIO -> "The microphone didn’t record. Try again."
    else -> "Dictation stopped. Try again."
}

/** 7 seconds as "0:07", 83 as "1:23". */
fun elapsedLabel(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** Dictated text joined to what is already typed: a space between, nothing extra on an empty box. */
fun appendTranscript(draft: String, transcript: String): String {
    val text = transcript.trim()
    if (text.isEmpty()) return draft
    return if (draft.isBlank()) text else draft.trimEnd() + " " + text
}
