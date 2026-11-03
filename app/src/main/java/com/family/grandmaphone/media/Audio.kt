package com.family.grandmaphone.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import com.family.grandmaphone.data.Repo
import java.io.File
import java.util.Locale

/** Plays one clip or spoken sentence at a time. */
object Speaker {
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    fun play(file: File, onDone: () -> Unit = {}) {
        stop()
        if (!file.exists()) {
            onDone()
            return
        }
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                setDataSource(file.path)
                setOnCompletionListener { onDone() }
                setOnErrorListener { _, _, _ -> onDone(); true }
                prepare()
                start()
            }
        } catch (_: Exception) {
            onDone()
        }
    }

    fun say(context: Context, text: String) {
        stop()
        val engine = tts
        if (engine != null && ttsReady) {
            applyLanguage(engine)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say")
            return
        }
        pendingSpeech = text
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                val waiting = pendingSpeech
                pendingSpeech = null
                if (ttsReady && waiting != null) {
                    tts?.let {
                        applyLanguage(it)
                        it.speak(waiting, TextToSpeech.QUEUE_FLUSH, null, "say")
                    }
                }
            }
        }
    }

    private fun applyLanguage(engine: TextToSpeech) {
        val tag = Repo.settings.value.speechLanguage
        engine.language = if (tag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(tag)
    }

    /** Reports whether a voice exists for a language, and if so says the text in it. */
    fun tryVoice(context: Context, languageTag: String, text: String, onResult: (Boolean) -> Unit) {
        val locale = if (languageTag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(languageTag)
        var checker: TextToSpeech? = null
        checker = TextToSpeech(context.applicationContext) { status ->
            val engine = checker
            val ready = status == TextToSpeech.SUCCESS && engine != null &&
                engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
            if (ready) {
                engine?.language = locale
                engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "try")
            }
            onResult(ready)
        }
    }

    fun stop() {
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
        tts?.stop()
    }
}

enum class VoiceFormat(val extension: String, val mimeType: String) {
    /** Very small, for MMS's strict size limits. */
    AMR("amr", "audio/amr"),

    /** Better quality, for clips stored on the phone and for WhatsApp. */
    AAC("m4a", "audio/mp4"),
}

class VoiceRecorder(private val file: File, private val format: VoiceFormat) {
    private var recorder: MediaRecorder? = null

    /** Returns false if recording can't start, for example when the microphone isn't allowed. */
    fun start(): Boolean {
        return try {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                when (format) {
                    VoiceFormat.AMR -> {
                        setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    }
                    VoiceFormat.AAC -> {
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioEncodingBitRate(64_000)
                        setAudioSamplingRate(44_100)
                    }
                }
                setOutputFile(file.path)
                prepare()
                start()
            }
            true
        } catch (_: Exception) {
            cleanUp()
            false
        }
    }

    /** Returns true if a usable recording was made. Stopping almost immediately doesn't produce one. */
    fun stop(): Boolean {
        val ok = try {
            recorder?.stop()
            recorder != null && file.length() > 0
        } catch (_: RuntimeException) {
            false
        }
        cleanUp()
        return ok
    }

    private fun cleanUp() {
        recorder?.release()
        recorder = null
    }
}

/** A short beep so she knows when to start talking. */
fun beep() {
    try {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
    } catch (_: RuntimeException) {
        // A missing beep shouldn't stop the recording.
    }
}
