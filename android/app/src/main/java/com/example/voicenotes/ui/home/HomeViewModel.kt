package com.example.voicenotes.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.voicenotes.data.model.NoteCard
import com.example.voicenotes.data.model.SentenceSlice
import com.example.voicenotes.data.repo.NoteCardsRepository
import com.example.voicenotes.data.repo.RetryFailedCardResult
import com.example.voicenotes.data.repo.UploadQueueRepository
import com.example.voicenotes.work.UploadWorkScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlin.math.sqrt
import kotlin.math.sin

private const val RECORDING_ACTIVITY_WINDOW_SIZE = 6
data class HomeUiState(
    val cards: List<NoteCard> = emptyList(),
    val isRecording: Boolean = false,
    val canRecordNext: Boolean = true,
    val transientMessage: String? = null,
    val recordingMeterLevels: List<Float> = List(RECORDING_METER_BAR_COUNT) { 0f },
    val recordingDisplayLevel: Float = 0f,
    val recordingActivityLevels: List<Float> = emptyList(),
    val showRecordingQuietHint: Boolean = false,
    val recordingSampleCount: Int = 0,
)

internal const val RECORDING_METER_BAR_COUNT = 12
private const val MAX_AUDIO_AMPLITUDE = 32_767f
private const val QUIET_HINT_MIN_SAMPLE_COUNT = 6
private const val QUIET_HINT_LEVEL_THRESHOLD = 0.09f
private const val QUIET_HINT_RECOVERY_LEVEL_THRESHOLD = 0.16f
private const val RECORDING_LEVEL_RISE_FACTOR = 0.72f
private const val RECORDING_LEVEL_FALL_FACTOR = 0.38f
private const val RECORDING_METER_PHASE_STEP = 0.68f
private const val RECORDING_METER_BAR_PHASE_OFFSET = 0.82f
private const val RECORDING_METER_MIN_WAVE_WEIGHT = 0.52f
private const val RECORDING_METER_WAVE_RANGE = 0.48f
private const val RECORDING_METER_BAR_SMOOTHING_FACTOR = 0.62f

class HomeViewModel(
    private val noteCardsRepository: NoteCardsRepository,
    private val uploadQueueRepository: UploadQueueRepository = UploadQueueRepository(
        noteCardsRepository = noteCardsRepository,
        uploadWorkScheduler = NoOpUploadWorkScheduler,
    ),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState = MutableStateFlow(HomeUiState())

    val uiState: StateFlow<HomeUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            noteCardsRepository.observeCards().collect { cards ->
                mutableUiState.update { current ->
                    current.copy(
                        cards = cards.sortedByDescending { card -> card.createdAtEpochMillis },
                    )
                }
            }
        }
    }

    fun onRecordPressed() {
        mutableUiState.update { current ->
            if (!current.canRecordNext) {
                current
            } else {
                current.copy(
                    isRecording = true,
                    canRecordNext = false,
                    transientMessage = null,
                    recordingMeterLevels = List(RECORDING_METER_BAR_COUNT) { 0f },
                    recordingDisplayLevel = 0f,
                    recordingActivityLevels = emptyList(),
                    showRecordingQuietHint = false,
                    recordingSampleCount = 0,
                )
            }
        }
    }

    fun onRecordingAmplitudeSample(amplitude: Int) {
        mutableUiState.update { current ->
            if (!current.isRecording) {
                current
            } else {
                val nextRawLevel = amplitudeToMeterLevel(amplitude)
                val nextDisplayLevel = smoothRecordingLevel(
                    previousLevel = current.recordingDisplayLevel,
                    nextLevel = nextRawLevel,
                )
                val nextActivityLevels = appendRecordingActivityLevel(
                    current.recordingActivityLevels,
                    nextRawLevel,
                )
                val nextSampleCount = current.recordingSampleCount + 1
                val targetMeterLevels = buildRealtimeMeterLevels(
                    level = nextDisplayLevel,
                    sampleCount = nextSampleCount,
                )
                current.copy(
                    recordingMeterLevels = smoothRecordingMeterLevels(
                        previousLevels = current.recordingMeterLevels,
                        targetLevels = targetMeterLevels,
                    ),
                    recordingDisplayLevel = nextDisplayLevel,
                    recordingActivityLevels = nextActivityLevels,
                    showRecordingQuietHint = shouldShowQuietHint(
                        levels = nextActivityLevels,
                        sampleCount = nextSampleCount,
                        currentLevel = nextRawLevel,
                    ),
                    recordingSampleCount = nextSampleCount,
                )
            }
        }
    }

    fun onRecordReleased(audioPath: String) {
        mutableUiState.update { current ->
            current.copy(
                isRecording = false,
                canRecordNext = true,
                recordingMeterLevels = List(RECORDING_METER_BAR_COUNT) { 0f },
                recordingDisplayLevel = 0f,
                recordingActivityLevels = emptyList(),
                showRecordingQuietHint = false,
                recordingSampleCount = 0,
            )
        }

        if (audioPath.isBlank()) {
            return
        }

        scope.launch {
            try {
                uploadQueueRepository.createPendingCardAndEnqueue(audioPath)
            } catch (error: Throwable) {
                mutableUiState.update { current ->
                    current.copy(
                        transientMessage = error.message ?: "无法创建语音笔记",
                    )
                }
            }
        }
    }

    fun onRecordCanceled(message: String? = null) {
        mutableUiState.update { current ->
            current.copy(
                isRecording = false,
                canRecordNext = true,
                transientMessage = message,
                recordingMeterLevels = List(RECORDING_METER_BAR_COUNT) { 0f },
                recordingDisplayLevel = 0f,
                recordingActivityLevels = emptyList(),
                showRecordingQuietHint = false,
                recordingSampleCount = 0,
            )
        }
    }

    fun onClearAllRecords() {
        scope.launch {
            try {
                noteCardsRepository.clearAllCards()
                mutableUiState.update { current ->
                    current.copy(transientMessage = "已清空记录")
                }
            } catch (error: Throwable) {
                mutableUiState.update { current ->
                    current.copy(
                        transientMessage = error.message ?: "清空失败",
                    )
                }
            }
        }
    }

    fun onCardEdited(
        cardId: String,
        sentences: List<SentenceSlice>,
        copyText: String,
    ) {
        scope.launch {
            try {
                noteCardsRepository.persistCardEdits(
                    cardId = cardId,
                    sentences = sentences,
                    copyText = copyText,
                )
            } catch (error: Throwable) {
                mutableUiState.update { current ->
                    current.copy(
                        transientMessage = error.message ?: "保存修改失败",
                    )
                }
            }
        }
    }

    fun onRetryFailedCard(cardId: String) {
        scope.launch {
            when (uploadQueueRepository.retryFailedCardAndEnqueue(cardId)) {
                RetryFailedCardResult.Enqueued -> {
                    mutableUiState.update { current ->
                        current.copy(transientMessage = null)
                    }
                }

                RetryFailedCardResult.MissingAudio -> {
                    mutableUiState.update { current ->
                        current.copy(transientMessage = "本地录音已丢失，无法重试")
                    }
                }

                RetryFailedCardResult.CardNotFound -> {
                    mutableUiState.update { current ->
                        current.copy(transientMessage = "未找到要重试的卡片")
                    }
                }
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    class Factory(
        private val noteCardsRepository: NoteCardsRepository,
        private val uploadQueueRepository: UploadQueueRepository = UploadQueueRepository(
            noteCardsRepository = noteCardsRepository,
            uploadWorkScheduler = NoOpUploadWorkScheduler,
        ),
        private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(
                noteCardsRepository = noteCardsRepository,
                uploadQueueRepository = uploadQueueRepository,
                dispatcher = dispatcher,
            ) as T
        }
    }
}

internal fun amplitudeToMeterLevel(amplitude: Int): Float {
    val clamped = amplitude.coerceAtLeast(0).coerceAtMost(MAX_AUDIO_AMPLITUDE.toInt())
    if (clamped == 0) {
        return 0f
    }
    return sqrt(clamped / MAX_AUDIO_AMPLITUDE).coerceIn(0f, 1f)
}

internal fun smoothRecordingLevel(
    previousLevel: Float,
    nextLevel: Float,
): Float {
    val clampedPrevious = previousLevel.coerceIn(0f, 1f)
    val clampedNext = nextLevel.coerceIn(0f, 1f)
    val factor = if (clampedNext >= clampedPrevious) {
        RECORDING_LEVEL_RISE_FACTOR
    } else {
        RECORDING_LEVEL_FALL_FACTOR
    }
    return clampedPrevious + ((clampedNext - clampedPrevious) * factor)
}

internal fun buildRealtimeMeterLevels(level: Float): List<Float> {
    return buildRealtimeMeterLevels(level, sampleCount = 0)
}

internal fun buildRealtimeMeterLevels(
    level: Float,
    sampleCount: Int,
): List<Float> {
    val clampedLevel = level.coerceIn(0f, 1f)
    if (clampedLevel == 0f) {
        return List(RECORDING_METER_BAR_COUNT) { 0f }
    }

    val phase = sampleCount * RECORDING_METER_PHASE_STEP
    return List(RECORDING_METER_BAR_COUNT) { index ->
        val wave = ((sin(phase + (index * RECORDING_METER_BAR_PHASE_OFFSET)) + 1f) / 2f)
        val weight = RECORDING_METER_MIN_WAVE_WEIGHT + (wave * RECORDING_METER_WAVE_RANGE)
        (clampedLevel * weight).coerceIn(0f, 1f)
    }
}

internal fun smoothRecordingMeterLevels(
    previousLevels: List<Float>,
    targetLevels: List<Float>,
): List<Float> {
    if (previousLevels.size != targetLevels.size) {
        return targetLevels.map { it.coerceIn(0f, 1f) }
    }

    return previousLevels.zip(targetLevels) { previous, target ->
        val clampedPrevious = previous.coerceIn(0f, 1f)
        val clampedTarget = target.coerceIn(0f, 1f)
        clampedPrevious + ((clampedTarget - clampedPrevious) * RECORDING_METER_BAR_SMOOTHING_FACTOR)
    }
}

internal fun appendRecordingActivityLevel(
    levels: List<Float>,
    nextLevel: Float,
): List<Float> {
    return (levels + nextLevel.coerceIn(0f, 1f))
        .takeLast(RECORDING_ACTIVITY_WINDOW_SIZE)
}

internal fun shouldShowQuietHint(
    levels: List<Float>,
    sampleCount: Int,
    currentLevel: Float = 0f,
): Boolean {
    if (currentLevel >= QUIET_HINT_RECOVERY_LEVEL_THRESHOLD) {
        return false
    }
    return sampleCount >= QUIET_HINT_MIN_SAMPLE_COUNT &&
        (levels.maxOrNull() ?: 0f) < QUIET_HINT_LEVEL_THRESHOLD
}

private object NoOpUploadWorkScheduler : UploadWorkScheduler {
    override fun enqueueUploadQueue(replaceExisting: Boolean) = Unit

    override suspend fun hasRunningUploadQueueWork(): Boolean = false
}

internal fun buildCorrectedCardText(
    card: NoteCard,
    sentenceOverrides: Map<String, String> = emptyMap(),
): String {
    val baseText = card.copyText ?: card.cleanText ?: card.rawTranscript
    if (sentenceOverrides.isEmpty()) {
        return baseText ?: card.sentences.joinToString(separator = "\n") { it.text }
    }
    if (card.sentences.isEmpty() || baseText.isNullOrBlank()) {
        return card.sentences.joinToString(separator = "\n") { sentence ->
            sentenceOverrides[sentence.id] ?: sentence.text
        }.ifBlank { baseText.orEmpty() }
    }

    val builder = StringBuilder()
    var cursor = 0
    for (sentence in card.sentences) {
        val matchIndex = baseText.indexOf(sentence.text, startIndex = cursor)
        if (matchIndex < 0) {
            return card.sentences.joinToString(separator = "\n") { currentSentence ->
                sentenceOverrides[currentSentence.id] ?: currentSentence.text
            }
        }
        builder.append(baseText.substring(cursor, matchIndex))
        builder.append(sentenceOverrides[sentence.id] ?: sentence.text)
        cursor = matchIndex + sentence.text.length
    }
    builder.append(baseText.substring(cursor))
    return builder.toString()
}
