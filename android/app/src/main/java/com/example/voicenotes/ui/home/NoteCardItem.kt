package com.example.voicenotes.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.voicenotes.data.model.NoteCard
import com.example.voicenotes.data.model.NoteStatus
import com.example.voicenotes.data.model.SentenceSlice
import com.example.voicenotes.data.model.TokenPhrase
import com.example.voicenotes.util.copyTextToClipboard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class FailedCardPresentation(
    val message: String,
    val canRetry: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun NoteCardItem(
    card: NoteCard,
    modifier: Modifier = Modifier,
    onCardEdited: ((String, List<SentenceSlice>, String) -> Unit)? = null,
    onRetryClick: ((String) -> Unit)? = null,
    onCopyClick: (() -> Unit)? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    val sentenceStates = remember(
        card.id,
        card.copyText,
        card.cleanText,
        card.rawTranscript,
        card.sentences,
    ) {
        mutableStateMapOf<String, ReplacementSentenceState>().apply {
            card.sentences.forEach { sentence ->
                put(sentence.id, createReplacementSentenceState(sentence))
            }
        }
    }
    var activeSelection by remember(
        card.id,
        card.copyText,
        card.cleanText,
        card.rawTranscript,
        card.sentences,
    ) {
        mutableStateOf<ReplacementSelection?>(null)
    }
    val currentCardText = when (card.status) {
        NoteStatus.COMPLETED -> {
            if (card.sentences.isNotEmpty()) {
                buildCorrectedCardText(
                    card = card,
                    sentenceOverrides = card.sentences.associate { sentence ->
                        sentence.id to (sentenceStates[sentence.id]?.text ?: sentence.text)
                    },
                )
            } else {
                card.copyText ?: card.cleanText ?: card.rawTranscript.orEmpty()
            }
        }

        NoteStatus.PROCESSING, NoteStatus.FAILED -> ""
    }
    val containerColor = when (card.status) {
        NoteStatus.PROCESSING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        NoteStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        NoteStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    }
    val contentColor = MaterialTheme.colorScheme.onSurface
    val failedPresentation = if (card.status == NoteStatus.FAILED) {
        failedCardPresentation(card)
    } else {
        null
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(statusLabel(card.status)) },
                    enabled = false,
                )
                Text(
                    text = formatTimestamp(card.createdAtEpochMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.75f),
                )
            }

            when (card.status) {
                NoteStatus.PROCESSING -> ProcessingContent()
                NoteStatus.COMPLETED -> CompletedContent(
                    card = card,
                    sentenceStates = card.sentences.mapNotNull { sentenceStates[it.id] },
                    fallbackText = currentCardText,
                    onTokenClick = { sentenceId, token ->
                        activeSelection = ReplacementSelection(
                            sentenceId = sentenceId,
                            token = token,
                        )
                    },
                )
                NoteStatus.FAILED -> FailedContent(failedPresentation ?: failedCardPresentation(card))
            }

            when (card.status) {
                NoteStatus.COMPLETED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyTextToClipboard(
                                    clipboardManager = clipboardManager,
                                    text = currentCardText,
                                )
                            },
                            enabled = currentCardText.isNotBlank(),
                        ) {
                            Text("复制")
                        }
                    }
                }

                NoteStatus.FAILED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (failedPresentation?.canRetry == true) {
                            OutlinedButton(
                                onClick = { onRetryClick?.invoke(card.id) },
                                enabled = onRetryClick != null,
                            ) {
                                Text("重试")
                            }
                        }
                    }
                }

                NoteStatus.PROCESSING -> Unit
            }
        }
    }

    activeSelection?.let { selection ->
        TokenReplacementSheet(
            selection = selection,
            onDismiss = { activeSelection = null },
            onReplace = { replacement ->
                val currentSentenceState = sentenceStates[selection.sentenceId] ?: return@TokenReplacementSheet
                val updatedSentenceState = applyTokenReplacement(
                    sentenceState = currentSentenceState,
                    target = selection.token,
                    replacement = replacement,
                )
                sentenceStates[selection.sentenceId] = updatedSentenceState
                val updatedSentences = card.sentences.map { sentence ->
                    sentenceStates[sentence.id]?.toSentenceSlice(sentence) ?: sentence
                }
                val updatedCopyText = buildCorrectedCardText(
                    card = card,
                    sentenceOverrides = updatedSentences.associate { sentence ->
                        sentence.id to sentence.text
                    },
                )
                onCardEdited?.invoke(card.id, updatedSentences, updatedCopyText)
                activeSelection = null
            },
        )
    }
}

@Composable
private fun ProcessingContent() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(
            modifier = Modifier.padding(top = 2.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = "处理中，正在上传并整理语音",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CompletedContent(
    card: NoteCard,
    sentenceStates: List<ReplacementSentenceState>,
    fallbackText: String,
    onTokenClick: (sentenceId: String, token: ReplacementSheetToken) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sentenceStates.isEmpty()) {
            Text(
                text = fallbackText.ifBlank { card.copyText ?: card.cleanText ?: card.rawTranscript ?: "已完成" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            sentenceStates.forEach { sentenceState ->
                ReplacementSentenceText(
                    sentenceState = sentenceState,
                    onTokenClick = { token -> onTokenClick(sentenceState.sentenceId, token) },
                )
            }
        }
    }
}

@Composable
private fun FailedContent(presentation: FailedCardPresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = presentation.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun statusLabel(status: NoteStatus): String {
    return when (status) {
        NoteStatus.PROCESSING -> "处理中"
        NoteStatus.COMPLETED -> "已完成"
        NoteStatus.FAILED -> "失败"
    }
}

private fun formatTimestamp(createdAtEpochMillis: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(createdAtEpochMillis))
}

internal fun failedCardPresentation(card: NoteCard): FailedCardPresentation {
    val canRetry = card.status == NoteStatus.FAILED && card.audioLocalPath.isNotBlank()
    val message = when {
        !card.errorMessage.isNullOrBlank() -> card.errorMessage.toUserFriendlyFailureMessage()
        canRetry -> "上传失败，可重试"
        else -> "处理失败，无法重试"
    }
    return FailedCardPresentation(
        message = message,
        canRetry = canRetry,
    )
}

private fun String.toUserFriendlyFailureMessage(): String {
    return when {
        contains("Volcengine ASR query timed out", ignoreCase = true) ->
            "语音识别超时，请重试"
        contains("ASR provider failed to transcribe audio", ignoreCase = true) ->
            "语音识别失败，请重试"
        contains("Too Many Requests", ignoreCase = true) ->
            "请求过于频繁，请稍后重试"
        else -> this
    }
}

private fun ReplacementSentenceState.toSentenceSlice(original: SentenceSlice): SentenceSlice {
    return original.copy(
        text = text,
        tokens = tokens.map { token ->
            TokenPhrase(
                text = token.text,
                startIndex = token.startIndex,
                endIndex = token.endIndex,
                isAsrSuspicious = token.isSuspicious,
                isEdited = token.isEdited,
                suggestions = token.suggestions,
            )
        },
    )
}

@Composable
private fun ReplacementSentenceText(
    sentenceState: ReplacementSentenceState,
    onTokenClick: (ReplacementSheetToken) -> Unit,
) {
    val editableColor = MaterialTheme.colorScheme.primary
    val editedColor = MaterialTheme.colorScheme.primary
    val highlightColor = MaterialTheme.colorScheme.tertiary
    val annotatedText = buildAnnotatedString {
        append(sentenceState.text)
        sentenceState.tokens.forEach { token ->
            addStyle(
                style = SpanStyle(
                    color = if (token.isEdited) editedColor else editableColor.copy(alpha = 0.88f),
                    textDecoration = TextDecoration.Underline,
                ),
                start = token.startIndex,
                end = token.endIndex,
            )
        }
        sentenceState.tokens.filter(ReplacementSheetToken::isEdited).forEach { token ->
            addStyle(
                style = SpanStyle(fontWeight = FontWeight.Bold),
                start = token.startIndex,
                end = token.endIndex,
            )
        }
        sentenceState.tokens.filter(ReplacementSheetToken::isSuspicious).forEach { token ->
            addStyle(
                style = SpanStyle(
                    color = highlightColor,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.SemiBold,
                ),
                start = token.startIndex,
                end = token.endIndex,
            )
        }
    }

    ClickableText(
        text = annotatedText,
        style = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        onClick = { offset ->
            sentenceState.tokens.firstOrNull { token ->
                token.isClickable && offset in token.startIndex until token.endIndex
            }?.let(onTokenClick)
        },
    )
}
