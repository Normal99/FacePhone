package com.family.grandmaphone.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Decides when someone has finished speaking, so she never has to press stop. */
class SilenceDetector(
    private val minSpeechMillis: Int = 200,
    private val endSilenceMillis: Int = 1200,
    private val noSpeechTimeoutMillis: Int = 6000,
    private val maxMillis: Int = 10_000,
    private val minLevel: Double = 45.0,
) {
    enum class State { WAITING, SPEAKING, FINISHED, NO_SPEECH }

    private var elapsed = 0
    private var speech = 0
    private var silence = 0
    private var noiseFloor = 0.0

    fun add(level: Double, millis: Int): State {
        elapsed += millis
        val speaking = level > maxOf(minLevel, noiseFloor * 3)
        // Follows the room's background noise: straight down when it goes quiet, slowly up otherwise,
        // so she can start talking the moment the microphone opens.
        noiseFloor = if (level < noiseFloor) level else noiseFloor * 0.98 + level * 0.02
        if (speaking) {
            speech += millis
            silence = 0
        } else {
            silence += millis
        }
        val heardSpeech = speech >= minSpeechMillis
        return when {
            heardSpeech && (silence >= endSilenceMillis || elapsed >= maxMillis) -> State.FINISHED
            !heardSpeech && elapsed >= noSpeechTimeoutMillis -> State.NO_SPEECH
            heardSpeech -> State.SPEAKING
            else -> State.WAITING
        }
    }
}

object Wav {
    private const val TARGET_PEAK = 23_000.0

    /** Wraps 16-bit mono audio in the small header that makes it a .wav file. */
    fun wrap(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }

    /** This phone's mic is very quiet, so turns recordings up until the voice is clear. */
    fun louder(pcm: ByteArray, maxGain: Double = 30.0): ByteArray {
        val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peak = 0
        for (i in 0 until samples.limit()) peak = maxOf(peak, abs(samples.get(i).toInt()))
        if (peak == 0) return pcm
        val gain = (TARGET_PEAK / peak).coerceIn(1.0, maxGain)
        val result = ByteBuffer.allocate(pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples.limit()) result.putShort((samples.get(i) * gain).toInt().toShort())
        return result.array()
    }

    fun level(samples: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / count)
    }
}

object SpeechCapture {
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_MILLIS = 100

    /** Records until she stops talking. Returns a .wav, or null if nothing was said or the mic failed. */
    @SuppressLint("MissingPermission") // Without the permission, startRecording fails and this returns null.
    suspend fun record(): ByteArray? = withContext(Dispatchers.IO) {
        val chunk = SAMPLE_RATE * CHUNK_MILLIS / 1000
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return@withContext null
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, chunk * 8),
            )
        } catch (_: Exception) {
            return@withContext null
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext null
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext null
            val detector = SilenceDetector()
            val samples = ShortArray(chunk)
            val pcm = ByteArrayOutputStream()
            val bytes = ByteBuffer.allocate(chunk * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (currentCoroutineContext().isActive) {
                val count = recorder.read(samples, 0, chunk)
                if (count <= 0) return@withContext null
                bytes.clear()
                for (i in 0 until count) bytes.putShort(samples[i])
                pcm.write(bytes.array(), 0, count * 2)
                when (detector.add(Wav.level(samples, count), CHUNK_MILLIS)) {
                    SilenceDetector.State.FINISHED -> return@withContext Wav.wrap(Wav.louder(pcm.toByteArray()), SAMPLE_RATE)
                    SilenceDetector.State.NO_SPEECH -> return@withContext null
                    else -> Unit
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
            }
            recorder.release()
        }
    }
}
