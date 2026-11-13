package io.coralnut.pleosprobe

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitialize
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitialize
import java.util.Locale

/** Real Pleos speech calls only. A sample sends PCM to STT, never a fabricated transcript. */
class VoiceRecommendation(private val context: Context, private val sample: String?, private val onAccepted: () -> Unit, private val onMissed: () -> Unit, private val log: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context, SttMode.ON_DEVICE)
    private var ttsReady = false
    private var sttReady = false
    @Volatile private var closed = false
    private var pending: String? = null
    private var state = "IDLE"
    private var peak = 0.0
    @Volatile private var turn = 0
    private var clarifyCount = 0
    private val timeout = Runnable { abort("TIMEOUT state=$state") }
    private fun abort(reason: String) {
        if (closed) return
        val missed = state == "LISTENING" || state == "DECIDING"
        log("VOICE_ABORT $reason")
        state = "IDLE"; pending = null; turn++
        main.removeCallbacks(timeout)
        runCatching { stt.stop() }; runCatching { tts.stop() }
        if (missed) onMissed()
    }
    private fun deadline(ms: Long) { main.removeCallbacks(timeout); main.postDelayed(timeout, ms) }
    private val ttsEvents = object : EventListener {
        override fun onReady() { log("TTS_READY_EVENT") }
        override fun onStart() { log("TTS_START state=$state") }
        override fun onUpdatedRms(rms: Double) { peak = maxOf(peak, rms) }
        override fun onDone() { main.post {
            log("TTS_DONE state=$state peakRms=$peak")
            main.removeCallbacks(timeout)
            if (state == "ASKING") {
                // Wait until question playback ends; never listen to our own prompt.
                state = "LISTENING"
                val myTurn = turn
                deadline(45000)
                main.postDelayed({ if (!closed && myTurn == turn && state == "LISTENING") listen(myTurn) }, 400)
            } else if (state == "RECOMMENDING") {
                state = "IDLE"; log("FLOW_COMPLETE recommendation spoken")
            }
        } }
        override fun onStop() { log("TTS_STOP") }
        override fun onError(errorType: TtsError) { main.post { abort("TTS_ERROR $errorType") } }
    }
    private val sttEvents = object : ResultListener {
        override fun onReady() { main.post {
            log("STT_READY_EVENT")
            if (sample == null && state == "LISTENING") {
                val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60)
                tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
                main.postDelayed({ tone.release() }, 300)
                log("MIC_READY_BEEP; respond after beep")
            }
        } }
        override fun onStartedRecognition() { log("STT_STARTED source=${sample ?: "MICROPHONE"}") }
        override fun onUpdated(stt: String, completed: Boolean) { main.post {
            log("STT_RESULT text=$stt completed=$completed source=${sample ?: "MICROPHONE"}")
            if (!completed || state != "LISTENING" || closed) return@post
            state = "DECIDING"; main.removeCallbacks(timeout)
            runCatching { this@VoiceRecommendation.stt.stop() }
            val normalized = stt.lowercase(Locale.KOREA).replace(Regex("[\\s\\p{Punct}]"), "")
            when (normalized) {
                "응", "어", "네", "예", "좋아", "추천해줘", "알려줘", "응추천해줘", "네추천해줘" -> {
                    log("CONSENT_ACCEPTED transcript=$stt")
                    onAccepted()
                    state = "RECOMMENDING"
                    speak("목적지 주변 무닌 장소를 화면에 보여드릴게요. 마음에 드는 장소를 골라 주세요.")
                }
                "아니", "아니요", "괜찮아", "아니괜찮아", "됐어", "다음에", "싫어" -> {
                    state = "IDLE"; log("NO_RECOMMENDATION explicit decline: $stt")
                }
                else -> {
                    log("NO_RECOMMENDATION consent not explicit: $stt")
                    if (clarifyCount++ < 1) {
                        state = "ASKING"
                        speak("잘 듣지 못했어요. 추천을 원하시면 준비 소리 뒤에 응 추천해 줘라고 말씀해 주세요.")
                    } else { state = "IDLE"; onMissed() }
                }
            }
        } }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) { if (rms > 20) log("STT_INPUT_RMS $rms") }
        override fun onEndedRecognition() { log("STT_ENDED") }
        override fun onError(errorType: SttError) { main.post { if (state == "LISTENING") abort("STT_ERROR $errorType") else log("STT_ERROR after completion $errorType") } }
    }
    init {
        tts.initialize(object : TtsInitialize {
            override fun onSuccess() { main.post {
                tts.addEventListener(ttsEvents); tts.setLocale(Locale.KOREA); ttsReady = true
                log("TTS_INITIALIZED mode=ON_DEVICE"); maybeAsk()
            } }
            override fun onFailure(errorType: TtsError) { main.post { abort("TTS_INITIALIZE_FAILED $errorType") } }
        })
        stt.initialize(object : SttInitialize {
            override fun onSuccess() { main.post {
                stt.addListener(sttEvents); stt.setLocale(Locale.KOREA); sttReady = true
                log("STT_INITIALIZED mode=ON_DEVICE"); maybeAsk()
            } }
            override fun onFailure(errorType: SttError) { main.post { abort("STT_INITIALIZE_FAILED $errorType") } }
        })
    }
    fun destination(name: String) {
        if (closed) return
        cancel()
        pending = name
        clarifyCount = 0
        log("VOICE_DESTINATION name=$name input=${sample ?: "MICROPHONE"}")
        deadline(30000); maybeAsk()
    }
    private fun maybeAsk() {
        if (closed || !ttsReady || !sttReady || pending == null || state != "IDLE") return
        val name = pending!!; pending = null; state = "ASKING"
        speak("$name 주변 무닌 추천을 안내해 드릴까요? 원하시면 응이라고 말씀해 주세요.")
    }
    private fun speak(text: String) {
        peak = 0.0
        log("TTS_REQUEST state=$state text=$text visible=${MainActivity.visible}")
        deadline(30000)
        runCatching { tts.speak(text) }.onFailure { abort("TTS_EXCEPTION ${it.message}") }
    }
    private fun listen(myTurn: Int) {
        try {
            if (sample == null) { log("STT_REQUEST MICROPHONE"); stt.request() }
            else {
                val pcm = if (sample == "silence") ByteArray(64000) else context.assets.open(sample).use { it.readBytes() }
                log("STT_SEND_AUDIO sample=$sample bytes=${pcm.size} format=16000Hz_PCM16LE_MONO")
                Thread {
                    try {
                        pcm.asList().chunked(3200).forEach { chunk ->
                            if (closed || myTurn != turn) return@Thread
                            stt.sendAudio(chunk.toByteArray()); Thread.sleep(100)
                        }
                        if (!closed && myTurn == turn) stt.completeAudioSend()
                    } catch (e: Exception) { main.post { if (myTurn == turn) abort("STT_SAMPLE_EXCEPTION ${e.message}") } }
                }.start()
            }
        } catch (e: Exception) { abort("STT_REQUEST_EXCEPTION ${e.message}") }
    }
    fun cancel() {
        turn++; pending = null; state = "IDLE"; main.removeCallbacks(timeout)
        if (sttReady) runCatching { stt.stop() }
        if (ttsReady) runCatching { tts.stop() }
    }
    fun release() {
        cancel(); closed = true; main.removeCallbacksAndMessages(null)
        if (ttsReady) tts.removeEventListener(ttsEvents)
        if (sttReady) stt.removeListener(sttEvents)
        tts.release(); stt.release()
    }
}
