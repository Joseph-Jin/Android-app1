package com.example.voicenotes.work

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadNoteWorkerTest {

    @Test
    fun returnsRetryWhenProcessorSignalsRetryableFailures() {
        val result = UploadNoteWorker.resultForRetryableFailures(hasRetryableFailures = true)

        assertEquals(ListenableWorker.Result.Retry::class, result::class)
    }

    @Test
    fun returnsSuccessWhenProcessorFinishesWithoutRetryableFailures() {
        val result = UploadNoteWorker.resultForRetryableFailures(hasRetryableFailures = false)

        assertEquals(ListenableWorker.Result.Success::class, result::class)
    }
}
