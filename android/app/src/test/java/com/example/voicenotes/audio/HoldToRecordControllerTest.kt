package com.example.voicenotes.audio

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class HoldToRecordControllerTest {

    @Test
    fun usesStableRecordingSettings() {
        assertEquals(16_000, HoldToRecordController.PREFERRED_SAMPLE_RATE_HZ)
        assertEquals(64_000, HoldToRecordController.PREFERRED_ENCODING_BIT_RATE)
        assertEquals(
            MediaRecorder.AudioSource.MIC,
            HoldToRecordController.PREFERRED_AUDIO_SOURCE,
        )
    }
}
