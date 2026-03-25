package com.example.voicenotes.ui.home

import com.example.voicenotes.data.model.NoteCard
import com.example.voicenotes.data.model.NoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteCardFailurePresentationTest {

    @Test
    fun failedCardPresentation_showsRetryWhenAudioStillExists() {
        val presentation = failedCardPresentation(
            NoteCard(
                id = "card-1",
                createdAtEpochMillis = 1L,
                status = NoteStatus.FAILED,
                audioLocalPath = "/tmp/a.m4a",
                errorMessage = "network down",
            )
        )

        assertTrue(presentation.canRetry)
        assertEquals("network down", presentation.message)
    }

    @Test
    fun failedCardPresentation_hidesRetryWhenAudioIsMissing() {
        val presentation = failedCardPresentation(
            NoteCard(
                id = "card-1",
                createdAtEpochMillis = 1L,
                status = NoteStatus.FAILED,
                audioLocalPath = "",
                errorMessage = "本地录音已丢失，无法重试",
            )
        )

        assertFalse(presentation.canRetry)
        assertEquals("本地录音已丢失，无法重试", presentation.message)
    }

    @Test
    fun failedCardPresentation_usesRetryableFallbackCopyForGenericFailures() {
        val presentation = failedCardPresentation(
            NoteCard(
                id = "card-1",
                createdAtEpochMillis = 1L,
                status = NoteStatus.FAILED,
                audioLocalPath = "/tmp/a.m4a",
            )
        )

        assertTrue(presentation.canRetry)
        assertEquals("上传失败，可重试", presentation.message)
    }

    @Test
    fun failedCardPresentation_translatesProviderTimeoutIntoChinese() {
        val presentation = failedCardPresentation(
            NoteCard(
                id = "card-1",
                createdAtEpochMillis = 1L,
                status = NoteStatus.FAILED,
                audioLocalPath = "/tmp/a.m4a",
                errorMessage = "ASR provider failed to transcribe audio: Volcengine ASR query timed out",
            )
        )

        assertTrue(presentation.canRetry)
        assertEquals("语音识别超时，请重试", presentation.message)
    }
}
