package com.magames.tecladoia.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.magames.tecladoia.Net
import com.magames.tecladoia.Prefs
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import kotlin.concurrent.thread

/**
 * Real-time voice typing.
 *  - Online: Android SpeechRecognizer (free, system/Google service) with partial results, pt-BR.
 *  - Offline: Vosk small pt model, fully on device.
 * Online is chosen automatically when the network is validated, unless "offline only" is on.
 * Both modes keep listening (continuous dictation) until [stop] is called.
 */
class VoiceInput(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onVoiceStatus(listening: Boolean, mode: Mode?, message: String)
        fun onVoicePartial(text: String)
        fun onVoiceFinal(text: String)
        fun onVoiceError(message: String, openSettings: Boolean = false)
    }

    enum class Mode { ONLINE, OFFLINE }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)
    private var recognizer: SpeechRecognizer? = null
    private var vosk: SpeechService? = null
    private var voskRec: Recognizer? = null
    var active = false
        private set
    var mode: Mode? = null
        private set

    fun hasMicPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun toggle() { if (active) stop() else start() }

    fun start() {
        if (active) return
        if (!hasMicPermission()) {
            listener.onVoiceError("Permita o microfone no app Teclado IA", openSettings = true)
            return
        }
        val online = !prefs.offlineOnly && Net.isOnline(context) &&
            SpeechRecognizer.isRecognitionAvailable(context)
        when {
            online -> startOnline()
            VoskModelManager.isInstalled(context) -> startOffline()
            VoskModelManager.ensureInstalledFromAssets(context) ->
                listener.onVoiceError("Preparando a voz offline (só na primeira vez)… tente de novo em alguns segundos.", openSettings = false)
            prefs.offlineOnly || !Net.isOnline(context) ->
                listener.onVoiceError("Sem internet: baixe o modelo de voz offline no app Teclado IA", openSettings = true)
            else -> listener.onVoiceError("Reconhecimento de voz do sistema indisponível. Baixe o modelo offline.", openSettings = true)
        }
    }

    // ---------------- Online (SpeechRecognizer) ----------------

    private fun startOnline() {
        active = true
        mode = Mode.ONLINE
        listener.onVoiceStatus(true, Mode.ONLINE, "Ouvindo… (online)")
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listener.onVoiceStatus(true, Mode.ONLINE, "Fale agora… (online)")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (p.isNotBlank()) listener.onVoicePartial(p)
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (t.isNotBlank()) listener.onVoiceFinal(t)
                // continuous dictation: listen again until the user taps the mic
                if (active && mode == Mode.ONLINE) main.postDelayed({ if (active) listenOnline() }, 150)
            }
            override fun onError(error: Int) {
                Log.w(TAG, "SpeechRecognizer error $error")
                if (!active || mode != Mode.ONLINE) return
                when (error) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> {
                        releaseOnline()
                        if (VoskModelManager.isInstalled(context)) {
                            active = false
                            startOffline()
                        } else {
                            finish("Falha de rede. Baixe o modelo offline para usar sem internet.")
                        }
                    }
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        finish("")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        finish("Permita o microfone no app Teclado IA")
                    else -> finish("Erro no reconhecimento ($error)")
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listenOnline()
    }

    private fun listenOnline() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        try {
            recognizer?.startListening(i)
        } catch (e: Throwable) {
            finish("Erro ao iniciar voz: ${e.message}")
        }
    }

    private fun releaseOnline() {
        try { recognizer?.cancel() } catch (_: Throwable) {}
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }

    // ---------------- Offline (Vosk) ----------------

    private fun startOffline() {
        active = true
        mode = Mode.OFFLINE
        listener.onVoiceStatus(true, Mode.OFFLINE, "Carregando modelo offline…")
        thread(name = "vosk-load") {
            val model = VoskModelManager.loadModel(context)
            main.post {
                if (!active) return@post
                if (model == null) { finish("Modelo offline não encontrado. Baixe no app."); return@post }
                try {
                    val rec = Recognizer(model, 16000.0f)
                    voskRec = rec
                    val svc = SpeechService(rec, 16000.0f)
                    vosk = svc
                    svc.startListening(voskListener)
                    listener.onVoiceStatus(true, Mode.OFFLINE, "Fale agora… (offline)")
                } catch (e: Throwable) {
                    Log.e(TAG, "Vosk start failed", e)
                    finish("Erro no microfone: ${e.message}")
                }
            }
        }
    }

    private val voskListener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val p = field(hypothesis, "partial")
            if (p.isNotBlank()) listener.onVoicePartial(p)
        }
        override fun onResult(hypothesis: String?) {
            val t = field(hypothesis, "text")
            if (t.isNotBlank()) listener.onVoiceFinal(t)
        }
        override fun onFinalResult(hypothesis: String?) {
            val t = field(hypothesis, "text")
            if (t.isNotBlank()) listener.onVoiceFinal(t)
        }
        override fun onError(exception: Exception?) { finish("Erro offline: ${exception?.message}") }
        override fun onTimeout() { finish("") }
    }

    private fun field(json: String?, key: String): String =
        try { JSONObject(json ?: "{}").optString(key) } catch (_: Throwable) { "" }

    private fun releaseOffline() {
        try { vosk?.stop() } catch (_: Throwable) {}
        try { vosk?.shutdown() } catch (_: Throwable) {}
        vosk = null
        try { voskRec?.close() } catch (_: Throwable) {}
        voskRec = null
    }

    // ---------------- common ----------------

    fun stop() {
        if (!active) return
        if (mode == Mode.ONLINE) {
            try { recognizer?.stopListening() } catch (_: Throwable) {}
            // let the final result arrive, then release
            active = false
            main.postDelayed({ releaseOnline() }, 1200)
        } else {
            active = false
            // stop() flushes the final result through onFinalResult
            try { vosk?.stop() } catch (_: Throwable) {}
            main.postDelayed({ releaseOffline() }, 300)
        }
        listener.onVoiceStatus(false, mode, "")
    }

    private fun finish(message: String) {
        val wasMode = mode
        active = false
        releaseOnline()
        releaseOffline()
        listener.onVoiceStatus(false, wasMode, "")
        if (message.isNotEmpty()) listener.onVoiceError(message)
    }

    fun destroy() {
        active = false
        releaseOnline()
        releaseOffline()
    }

    companion object { private const val TAG = "VoiceInput" }
}
