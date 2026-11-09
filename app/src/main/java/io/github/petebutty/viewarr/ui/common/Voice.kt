package io.github.petebutty.viewarr.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Voice search, listened to inside Viewarr with Android's speech recogniser (Google's on most TVs).
 *
 * Google's own voice box (RECOGNIZE_SPEECH) was used before: on the Shield it opens but never listens (its recogniser
 * refuses an app without the microphone). Listening here, with the microphone permission, works on both. Where
 * there's no recogniser at all, the mic opens the keyboard instead, whose own mic works.
 */
@Stable
class VoiceSearch internal constructor(private val context: Context) {
    var listening by mutableStateOf(false)
        internal set
    /** How loud it hears you, 0..1 (the mic button pulses with it). */
    var level by mutableFloatStateOf(0f)
        internal set
    internal var recognizer: SpeechRecognizer? = null
    internal var askPermission: () -> Unit = {}
    internal var onKeyboard: () -> Unit = {}

    internal fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    internal fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    internal fun begin() {
        val r = recognizer ?: return onKeyboard()
        runCatching {
            r.cancel()
            r.startListening(intent())
            listening = true
        }.onFailure { listening = false; onKeyboard() }
    }

    /** The mic button: listens (asking for the microphone the first time), or stops if it's listening. */
    fun toggle() {
        when {
            listening -> stop()
            recognizer == null -> onKeyboard()
            !hasMic() -> askPermission()
            else -> begin()
        }
    }

    fun stop() {
        runCatching { recognizer?.cancel() }
        listening = false
        level = 0f
    }
}

/**
 * [onResult]: the words heard. [onKeyboard]: no recogniser on this box (or it failed to start), so open the keyboard
 * for its mic.
 */
@Composable
fun rememberVoiceSearch(onResult: (String) -> Unit, onKeyboard: () -> Unit): VoiceSearch {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val keyboard by rememberUpdatedState(onKeyboard)
    val voice = remember { VoiceSearch(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.begin()
        else Toast.makeText(context, "Voice search needs the microphone. You can use the keyboard's mic instead.", Toast.LENGTH_LONG).show()
    }
    voice.askPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    voice.onKeyboard = {
        Toast.makeText(context, "Press Up to the keyboard's mic, then OK and speak", Toast.LENGTH_LONG).show()
        keyboard()
    }
    DisposableEffect(Unit) {
        val r = if (SpeechRecognizer.isRecognitionAvailable(context)) runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() else null
        r?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { voice.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                voice.listening = false
                voice.level = 0f
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Press the mic and try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Voice search needs the microphone."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "Voice search couldn't reach Google. Try again."
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> null
                    else -> "Voice search didn't work this time."
                }
                msg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            }
            override fun onResults(results: Bundle?) {
                voice.listening = false
                voice.level = 0f
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (said.isNotEmpty()) result(said)
                else Toast.makeText(context, "Didn't catch that. Press the mic and try again.", Toast.LENGTH_SHORT).show()
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        voice.recognizer = r
        onDispose {
            runCatching { r?.cancel(); r?.destroy() }
            voice.recognizer = null
            voice.listening = false
        }
    }
    return voice
}
