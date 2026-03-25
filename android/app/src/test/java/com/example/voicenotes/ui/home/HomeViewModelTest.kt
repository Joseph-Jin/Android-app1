package com.example.voicenotes.ui.home

import com.example.voicenotes.data.local.NoteCardDao
import com.example.voicenotes.data.model.NoteCard
import com.example.voicenotes.data.model.SentenceSlice
import com.example.voicenotes.data.local.NoteCardEntity
import com.example.voicenotes.data.model.NoteStatus
import com.example.voicenotes.data.model.TokenPhrase
import com.example.voicenotes.data.repo.NoteCardsRepository
import com.example.voicenotes.data.repo.UploadQueueRepository
import com.example.voicenotes.data.remote.NoteJobSnapshot
import com.example.voicenotes.data.remote.SentenceSliceDto
import com.example.voicenotes.data.remote.TokenPhraseDto
import com.example.voicenotes.work.UploadWorkScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Test
    fun releaseRecording_keepsRecorderReadyForNextNote() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noteCardsRepository = NoteCardsRepository(FakeHomeNoteCardDao())
        val workScheduler = FakeUploadWorkScheduler()
        val uploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = noteCardsRepository,
            uploadWorkScheduler = workScheduler,
        )
        val viewModel = HomeViewModel(
            noteCardsRepository = noteCardsRepository,
            uploadQueueRepository = uploadQueueRepository,
            dispatcher = dispatcher,
        )

        viewModel.onRecordPressed()
        viewModel.onRecordReleased("/tmp/a.m4a")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.canRecordNext)
        assertFalse(state.isRecording)
        assertEquals(1, state.cards.size)
        assertEquals(NoteStatus.PROCESSING, state.cards.first().status)
        assertEquals(1, workScheduler.enqueueCalls)
    }

    @Test
    fun quietRecordingSamples_showQuietHintAndKeepMeterHistory() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noteCardsRepository = NoteCardsRepository(FakeHomeNoteCardDao())
        val viewModel = HomeViewModel(
            noteCardsRepository = noteCardsRepository,
            uploadQueueRepository = UploadQueueRepository(
                noteCardsRepository = noteCardsRepository,
                uploadWorkScheduler = FakeUploadWorkScheduler(),
            ),
            dispatcher = dispatcher,
        )

        viewModel.onRecordPressed()
        repeat(6) {
            viewModel.onRecordingAmplitudeSample(0)
        }

        val state = viewModel.uiState.value
        assertTrue(state.isRecording)
        assertEquals(RECORDING_METER_BAR_COUNT, state.recordingMeterLevels.size)
        assertTrue(state.showRecordingQuietHint)
    }

    @Test
    fun louderSamples_hideQuietHintAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noteCardsRepository = NoteCardsRepository(FakeHomeNoteCardDao())
        val viewModel = HomeViewModel(
            noteCardsRepository = noteCardsRepository,
            uploadQueueRepository = UploadQueueRepository(
                noteCardsRepository = noteCardsRepository,
                uploadWorkScheduler = FakeUploadWorkScheduler(),
            ),
            dispatcher = dispatcher,
        )

        viewModel.onRecordPressed()
        repeat(6) {
            viewModel.onRecordingAmplitudeSample(0)
        }
        assertTrue(viewModel.uiState.value.showRecordingQuietHint)

        repeat(3) {
            viewModel.onRecordingAmplitudeSample(18_000)
        }

        assertFalse(viewModel.uiState.value.showRecordingQuietHint)
    }

    @Test
    fun oneClearlyLoudSample_hidesQuietHintImmediately() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noteCardsRepository = NoteCardsRepository(FakeHomeNoteCardDao())
        val viewModel = HomeViewModel(
            noteCardsRepository = noteCardsRepository,
            uploadQueueRepository = UploadQueueRepository(
                noteCardsRepository = noteCardsRepository,
                uploadWorkScheduler = FakeUploadWorkScheduler(),
            ),
            dispatcher = dispatcher,
        )

        viewModel.onRecordPressed()
        repeat(6) {
            viewModel.onRecordingAmplitudeSample(0)
        }
        assertTrue(viewModel.uiState.value.showRecordingQuietHint)

        viewModel.onRecordingAmplitudeSample(24_000)

        assertFalse(viewModel.uiState.value.showRecordingQuietHint)
    }

    @Test
    fun recordingMeterPattern_showsNaturalMotionForSameAmplitude() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noteCardsRepository = NoteCardsRepository(FakeHomeNoteCardDao())
        val viewModel = HomeViewModel(
            noteCardsRepository = noteCardsRepository,
            uploadQueueRepository = UploadQueueRepository(
                noteCardsRepository = noteCardsRepository,
                uploadWorkScheduler = FakeUploadWorkScheduler(),
            ),
            dispatcher = dispatcher,
        )

        viewModel.onRecordPressed()
        val centerSeries = mutableListOf<Float>()
        repeat(3) {
            viewModel.onRecordingAmplitudeSample(18_000)
        }
        repeat(8) {
            viewModel.onRecordingAmplitudeSample(18_000)
            centerSeries += viewModel.uiState.value.recordingMeterLevels[RECORDING_METER_BAR_COUNT / 2]
        }
        assertTrue(centerSeries.zipWithNext().any { (first, second) -> second < first })
    }

    @Test
    fun recordingMeter_followsCurrentAmplitudeInsteadOfKeepingOldPeakForever() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noteCardsRepository = NoteCardsRepository(FakeHomeNoteCardDao())
        val viewModel = HomeViewModel(
            noteCardsRepository = noteCardsRepository,
            uploadQueueRepository = UploadQueueRepository(
                noteCardsRepository = noteCardsRepository,
                uploadWorkScheduler = FakeUploadWorkScheduler(),
            ),
            dispatcher = dispatcher,
        )

        viewModel.onRecordPressed()
        repeat(4) {
            viewModel.onRecordingAmplitudeSample(20_000)
        }
        val louderPeak = viewModel.uiState.value.recordingMeterLevels.maxOrNull() ?: 0f

        repeat(4) {
            viewModel.onRecordingAmplitudeSample(300)
        }

        val quieterPeak = viewModel.uiState.value.recordingMeterLevels.maxOrNull() ?: 0f
        assertTrue(louderPeak > 0.5f)
        assertTrue(quieterPeak < louderPeak)
    }

    @Test
    fun amplitudeSamples_resetAfterCancel() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noteCardsRepository = NoteCardsRepository(FakeHomeNoteCardDao())
        val viewModel = HomeViewModel(
            noteCardsRepository = noteCardsRepository,
            uploadQueueRepository = UploadQueueRepository(
                noteCardsRepository = noteCardsRepository,
                uploadWorkScheduler = FakeUploadWorkScheduler(),
            ),
            dispatcher = dispatcher,
        )

        viewModel.onRecordPressed()
        viewModel.onRecordingAmplitudeSample(12_000)
        viewModel.onRecordCanceled("没有检测到明显声音，请检查模拟器麦克风设置或改用真机")

        val state = viewModel.uiState.value
        assertFalse(state.isRecording)
        assertFalse(state.showRecordingQuietHint)
        assertEquals(List(RECORDING_METER_BAR_COUNT) { 0f }, state.recordingMeterLevels)
    }

    @Test
    fun amplitudeToMeterLevel_returnsNormalizedValues() {
        assertEquals(0f, amplitudeToMeterLevel(0))
        assertTrue(amplitudeToMeterLevel(4_000) > 0f)
        assertEquals(1f, amplitudeToMeterLevel(32_767))
    }

    @Test
    fun buildCorrectedCardText_usesLatestSentenceReplacementForCopying() {
        val card = NoteCard(
            id = "card-1",
            createdAtEpochMillis = 1L,
            status = NoteStatus.COMPLETED,
            audioLocalPath = "",
            cleanText = "alpha beta. gamma delta.",
            sentences = listOf(
                SentenceSlice(
                    id = "sentence-1",
                    text = "alpha beta.",
                ),
                SentenceSlice(
                    id = "sentence-2",
                    text = "gamma delta.",
                ),
            ),
            copyText = "alpha beta. gamma delta.",
        )

        val correctedText = buildCorrectedCardText(
            card = card,
            sentenceOverrides = mapOf("sentence-1" to "alpha Vibe Coding."),
        )

        assertEquals("alpha Vibe Coding. gamma delta.", correctedText)
    }

    @Test
    fun clearAllRecords_removesCardsAndAudioFiles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noteCardsRepository = NoteCardsRepository(FakeHomeNoteCardDao())
        val uploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = noteCardsRepository,
            uploadWorkScheduler = FakeUploadWorkScheduler(),
        )
        val tempFile = File.createTempFile("voice-note-test", ".m4a")
        val viewModel = HomeViewModel(
            noteCardsRepository = noteCardsRepository,
            uploadQueueRepository = uploadQueueRepository,
            dispatcher = dispatcher,
        )

        noteCardsRepository.createPendingCard(tempFile.absolutePath)

        viewModel.onClearAllRecords()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.cards.isEmpty())
        assertFalse(tempFile.exists())
        assertEquals("已清空记录", viewModel.uiState.value.transientMessage)
    }

    @Test
    fun onCardEdited_persistsUpdatedCopyTextAndEditedTokens() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noteCardsRepository = NoteCardsRepository(FakeHomeNoteCardDao())
        val uploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = noteCardsRepository,
            uploadWorkScheduler = FakeUploadWorkScheduler(),
        )
        val viewModel = HomeViewModel(
            noteCardsRepository = noteCardsRepository,
            uploadQueueRepository = uploadQueueRepository,
            dispatcher = dispatcher,
        )
        val card = noteCardsRepository.createPendingCard("/tmp/a.m4a")
        noteCardsRepository.applyCompletedResult(
            card.id,
            NoteJobSnapshot(
                jobId = "job-1",
                status = NoteStatus.COMPLETED.value,
                cleanText = "今天我们主要聊 Web coding。",
                sentences = listOf(
                    SentenceSliceDto(
                        id = "sentence-1",
                        text = "今天我们主要聊 Web coding。",
                        tokens = listOf(
                            TokenPhraseDto(
                                id = "token-1",
                                text = "Web coding",
                                startIndex = 8,
                                endIndex = 18,
                                isAsrSuspicious = true,
                            ),
                        ),
                    )
                ),
            ),
        )
        advanceUntilIdle()

        viewModel.onCardEdited(
            cardId = card.id,
            sentences = listOf(
                SentenceSlice(
                    id = "sentence-1",
                    text = "今天我们主要聊 Vibe Coding。",
                    tokens = listOf(
                        TokenPhrase(
                            text = "Vibe Coding",
                            startIndex = 8,
                            endIndex = 19,
                            isEdited = true,
                        ),
                    ),
                )
            ),
            copyText = "今天我们主要聊 Vibe Coding。",
        )
        advanceUntilIdle()

        val updatedCard = viewModel.uiState.value.cards.first()
        assertEquals("今天我们主要聊 Vibe Coding。", updatedCard.copyText)
        assertEquals("Vibe Coding", updatedCard.sentences.first().tokens.first().text)
        assertTrue(updatedCard.sentences.first().tokens.first().isEdited)
    }

    @Test
    fun retryFailedCard_showsMessageWhenAudioIsMissing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noteCardsRepository = NoteCardsRepository(FakeHomeNoteCardDao())
        val uploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = noteCardsRepository,
            uploadWorkScheduler = FakeUploadWorkScheduler(),
        )
        val viewModel = HomeViewModel(
            noteCardsRepository = noteCardsRepository,
            uploadQueueRepository = uploadQueueRepository,
            dispatcher = dispatcher,
        )
        val card = noteCardsRepository.createPendingCard("/tmp/definitely-missing-retry.m4a")
        noteCardsRepository.applyFailedResult(
            cardId = card.id,
            errorMessage = "network down",
        )
        advanceUntilIdle()

        viewModel.onRetryFailedCard(card.id)
        advanceUntilIdle()

        assertEquals("本地录音已丢失，无法重试", viewModel.uiState.value.transientMessage)
    }
}

private class FakeHomeNoteCardDao : NoteCardDao {
    private val cards = MutableStateFlow<List<NoteCardEntity>>(emptyList())

    override fun observeCards(): Flow<List<NoteCardEntity>> = cards

    override suspend fun upsert(card: NoteCardEntity) {
        val updated = cards.value.toMutableList()
        val index = updated.indexOfFirst { it.id == card.id }
        if (index >= 0) {
            updated[index] = card
        } else {
            updated += card
        }
        cards.value = updated.sortedByDescending { it.createdAtEpochMillis }
    }

    override suspend fun getById(id: String): NoteCardEntity? {
        return cards.value.firstOrNull { it.id == id }
    }

    override suspend fun getNextProcessingCard(status: String): NoteCardEntity? {
        return cards.value.firstOrNull { it.status == status && it.remoteJobId == null }
    }

    override suspend fun deleteAll() {
        cards.value = emptyList()
    }
}

private class FakeUploadWorkScheduler : UploadWorkScheduler {
    var enqueueCalls: Int = 0
        private set

    override fun enqueueUploadQueue(replaceExisting: Boolean) {
        enqueueCalls += 1
    }

    override suspend fun hasRunningUploadQueueWork(): Boolean = false
}
