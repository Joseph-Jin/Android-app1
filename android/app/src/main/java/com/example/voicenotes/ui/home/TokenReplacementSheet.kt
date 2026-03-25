package com.example.voicenotes.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.voicenotes.data.model.ReplacementSuggestion
import com.example.voicenotes.data.model.SentenceSlice
import com.example.voicenotes.data.model.TokenPhrase

internal data class ReplacementSheetToken(
    val text: String,
    val startIndex: Int,
    val endIndex: Int,
    val isClickable: Boolean = true,
    val isSuspicious: Boolean = false,
    val isEdited: Boolean = false,
    val suggestions: List<ReplacementSuggestion> = emptyList(),
)

internal data class ReplacementSentenceState(
    val sentenceId: String,
    val text: String,
    val tokens: List<ReplacementSheetToken>,
)

internal data class ReplacementSelection(
    val sentenceId: String,
    val token: ReplacementSheetToken,
)

private val fallbackTokenRegex = Regex("[^\\s\\p{Punct}，。！？；：、“”‘’（）【】《》…]+")
private const val suggestionPageSize = 4

internal fun createReplacementSentenceState(sentence: SentenceSlice): ReplacementSentenceState {
    return ReplacementSentenceState(
        sentenceId = sentence.id,
        text = sentence.text,
        tokens = buildReplacementSheetTokens(sentence),
    )
}

internal fun buildReplacementSheetTokens(sentence: SentenceSlice): List<ReplacementSheetToken> {
    val text = sentence.text
    val protectedTokens = sentence.tokens
        .sortedBy(TokenPhrase::startIndex)
        .fold(mutableListOf<TokenPhrase>()) { acc, token ->
            val normalizedStart = token.startIndex.coerceIn(0, text.length)
            val normalizedEnd = token.endIndex.coerceIn(normalizedStart, text.length)
            if (normalizedEnd <= normalizedStart) {
                return@fold acc
            }
            if (acc.lastOrNull()?.endIndex ?: 0 > normalizedStart) {
                return@fold acc
            }
            acc += token.copy(startIndex = normalizedStart, endIndex = normalizedEnd)
            acc
        }

    val segments = mutableListOf<ReplacementSheetToken>()
    var cursor = 0
    for (token in protectedTokens) {
        if (cursor < token.startIndex) {
            segments += buildFallbackTokens(
                text = text.substring(cursor, token.startIndex),
                offset = cursor,
            )
        }
        segments += ReplacementSheetToken(
            text = text.substring(token.startIndex, token.endIndex),
            startIndex = token.startIndex,
            endIndex = token.endIndex,
            isSuspicious = token.isAsrSuspicious,
            isEdited = token.isEdited,
            suggestions = token.suggestions,
        )
        cursor = token.endIndex
    }
    if (cursor < text.length) {
        segments += buildFallbackTokens(
            text = text.substring(cursor),
            offset = cursor,
        )
    }
    return segments
}

internal fun applyTokenReplacement(
    sentenceText: String,
    target: ReplacementSheetToken,
    replacement: String,
): String {
    val trimmedReplacement = replacement.trim()
    if (trimmedReplacement.isEmpty()) {
        return sentenceText
    }
    val safeStart = target.startIndex.coerceIn(0, sentenceText.length)
    val safeEnd = target.endIndex.coerceIn(safeStart, sentenceText.length)
    return buildString(sentenceText.length - (safeEnd - safeStart) + trimmedReplacement.length) {
        append(sentenceText.substring(0, safeStart))
        append(trimmedReplacement)
        append(sentenceText.substring(safeEnd))
    }
}

internal fun applyTokenReplacement(
    sentenceState: ReplacementSentenceState,
    target: ReplacementSheetToken,
    replacement: String,
): ReplacementSentenceState {
    val trimmedReplacement = replacement.trim()
    if (trimmedReplacement.isEmpty()) {
        return sentenceState
    }

    val updatedText = applyTokenReplacement(sentenceState.text, target, trimmedReplacement)
    val delta = trimmedReplacement.length - (target.endIndex - target.startIndex)
    var replaced = false
    val updatedTokens = sentenceState.tokens.mapNotNull { token ->
        when {
            !replaced &&
                token.startIndex == target.startIndex &&
                token.endIndex == target.endIndex &&
                token.text == target.text -> {
                replaced = true
                token.copy(
                    text = trimmedReplacement,
                    endIndex = token.startIndex + trimmedReplacement.length,
                    isSuspicious = false,
                    isEdited = true,
                    suggestions = emptyList(),
                )
            }

            token.endIndex <= target.startIndex -> token
            token.startIndex >= target.endIndex -> token.copy(
                startIndex = token.startIndex + delta,
                endIndex = token.endIndex + delta,
            )

            else -> null
        }
    }

    return sentenceState.copy(
        text = updatedText,
        tokens = if (replaced) {
            updatedTokens
        } else {
            sentenceState.tokens + ReplacementSheetToken(
                text = trimmedReplacement,
                startIndex = target.startIndex,
                endIndex = target.startIndex + trimmedReplacement.length,
                isEdited = true,
            )
        }.sortedBy(ReplacementSheetToken::startIndex),
    )
}

private fun buildFallbackTokens(
    text: String,
    offset: Int,
): List<ReplacementSheetToken> {
    return fallbackTokenRegex.findAll(text).map { match ->
        ReplacementSheetToken(
            text = match.value,
            startIndex = offset + match.range.first,
            endIndex = offset + match.range.last + 1,
        )
    }.toList()
}

internal fun visibleSuggestionBatch(
    suggestions: List<ReplacementSuggestion>,
    pageIndex: Int,
    pageSize: Int = suggestionPageSize,
): List<ReplacementSuggestion> {
    if (suggestions.isEmpty()) {
        return emptyList()
    }
    val totalPages = ((suggestions.size - 1) / pageSize) + 1
    val normalizedPageIndex = pageIndex.coerceAtLeast(0) % totalPages
    val start = normalizedPageIndex * pageSize
    val end = minOf(start + pageSize, suggestions.size)
    return suggestions.subList(start, end)
}

internal fun nextSuggestionPageIndex(
    currentPageIndex: Int,
    totalSuggestions: Int,
    pageSize: Int = suggestionPageSize,
): Int {
    if (totalSuggestions <= pageSize) {
        return 0
    }
    val totalPages = ((totalSuggestions - 1) / pageSize) + 1
    return (currentPageIndex + 1) % totalPages
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TokenReplacementSheet(
    selection: ReplacementSelection,
    onDismiss: () -> Unit,
    onReplace: (String) -> Unit,
) {
    var manualReplacement by rememberSaveable(selection.sentenceId, selection.token.startIndex, selection.token.endIndex) {
        mutableStateOf(selection.token.text)
    }
    var suggestionPageIndex by rememberSaveable(
        selection.sentenceId,
        selection.token.startIndex,
        selection.token.endIndex,
    ) {
        mutableStateOf(0)
    }

    LaunchedEffect(selection.sentenceId, selection.token.startIndex, selection.token.endIndex) {
        manualReplacement = selection.token.text
        suggestionPageIndex = 0
    }
    val visibleSuggestions = visibleSuggestionBatch(
        suggestions = selection.token.suggestions,
        pageIndex = suggestionPageIndex,
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "替换词语",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = selection.token.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selection.token.isSuspicious) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (selection.token.suggestions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "推荐替换",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    visibleSuggestions.forEach { suggestion ->
                        AssistChip(
                            onClick = { onReplace(suggestion.value) },
                            label = { Text(suggestion.value) },
                        )
                    }
                    if (selection.token.suggestions.size > suggestionPageSize) {
                        TextButton(
                            onClick = {
                                suggestionPageIndex = nextSuggestionPageIndex(
                                    currentPageIndex = suggestionPageIndex,
                                    totalSuggestions = selection.token.suggestions.size,
                                )
                            },
                        ) {
                            Text("换一批")
                        }
                    }
                }
            }
            OutlinedTextField(
                value = manualReplacement,
                onValueChange = { manualReplacement = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手动输入") },
                singleLine = true,
            )
            Button(
                onClick = { onReplace(manualReplacement) },
                enabled = manualReplacement.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存替换")
            }
        }
    }
}
