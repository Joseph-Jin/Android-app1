package com.example.voicenotes.audio

import android.content.Context
import android.media.MediaRecorder
import java.io.File

class HoldToRecordController(
    context: Context,
    private val outputDirectory: File = File(context.filesDir, "recordings").apply { mkdirs() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    internal companion object {
        const val PREFERRED_AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
        const val PREFERRED_SAMPLE_RATE_HZ = 16_000
        const val PREFERRED_ENCODING_BIT_RATE = 64_000
    }

    private var recorder: MediaRecorder? = null
    private var activeAudioFile: File? = null

    @Suppress("DEPRECATION")
    fun startRecording(): Boolean {
        if (recorder != null) {
            return false
        }

        val audioFile = File.createTempFile(
            "voice_note_${clock()}_",
            ".m4a",
            outputDirectory,
        )
        val mediaRecorder = MediaRecorder()

        return try {
            mediaRecorder.setAudioSource(PREFERRED_AUDIO_SOURCE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(PREFERRED_SAMPLE_RATE_HZ)
            mediaRecorder.setAudioEncodingBitRate(PREFERRED_ENCODING_BIT_RATE)
            mediaRecorder.setOutputFile(audioFile.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            activeAudioFile = audioFile
            true
        } catch (throwable: Throwable) {
            audioFile.delete()
            mediaRecorder.release()
            false
        }
    }

    fun stopRecording(): StopRecordingResult {
        val mediaRecorder = recorder ?: return Failed
        val audioFile = activeAudioFile
        recorder = null
        activeAudioFile = null

        return try {
            mediaRecorder.stop()
            if (audioFile == null) {
                Failed
            } else {
                Success(audioFile.absolutePath)
            }
        } catch (_: RuntimeException) {
            audioFile?.delete()
            Failed
        } finally {
            mediaRecorder.release()
        }
    }

    fun cancelRecording() {
        val mediaRecorder = recorder ?: return
        val audioFile = activeAudioFile
        recorder = null
        activeAudioFile = null

        try {
            mediaRecorder.stop()
        } catch (_: RuntimeException) {
            // Ignore: cancellation should leave no card behind.
        } finally {
            mediaRecorder.release()
        }
        audioFile?.delete()
    }

    fun release() {
        cancelRecording()
    }

    fun sampleAmplitude(): Int = recorder?.maxAmplitude ?: 0

    fun isRecording(): Boolean = recorder != null

    sealed interface StopRecordingResult
    data class Success(val audioPath: String) : StopRecordingResult
    data object Failed : StopRecordingResult
}
