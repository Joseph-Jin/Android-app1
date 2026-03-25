package com.example.voicenotes.ui.home

import com.example.voicenotes.data.model.ReplacementSuggestion
import com.example.voicenotes.data.model.SentenceSlice
import com.example.voicenotes.data.model.TokenPhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenReplacementSheetTest {

    @Test
    fun buildReplacementSheetTokens_makesEveryLexicalSegmentClickable() {
        val sentence = SentenceSlice(
            text = "alpha beta, gamma",
            tokens = listOf(
                TokenPhrase(
                    text = "beta",
                    startIndex = 6,
                    endIndex = 10,
                    isAsrSuspicious = true,
                    suggestions = listOf(ReplacementSuggestion(value = "Beta")),
                )
            ),
        )

        val segments = buildReplacementSheetTokens(sentence)
        val clickableTexts = segments.filter { it.isClickable }.map { it.text }

        assertEquals(listOf("alpha", "beta", "gamma"), clickableTexts)
        assertTrue(segments.first { it.text == "alpha" }.isClickable)
        assertFalse(segments.first { it.text == "alpha" }.isSuspicious)
        assertTrue(segments.first { it.text == "beta" }.isSuspicious)
        assertTrue(segments.first { it.text == "gamma" }.isClickable)
    }

    @Test
    fun applyTokenReplacement_replacesOnlyTheSelectedWholePhrase() {
        val sentence = SentenceSlice(
            text = "web coding and web coding",
            tokens = listOf(
                TokenPhrase(
                    text = "web coding",
                    startIndex = 0,
                    endIndex = 10,
                    isAsrSuspicious = true,
                    suggestions = listOf(ReplacementSuggestion(value = "Vibe Coding")),
                ),
                TokenPhrase(
                    text = "web coding",
                    startIndex = 15,
                    endIndex = 25,
                    isAsrSuspicious = true,
                    suggestions = listOf(ReplacementSuggestion(value = "Vibe Coding")),
                ),
            ),
        )

        val target = buildReplacementSheetTokens(sentence)
            .first { it.text == "web coding" && it.startIndex == 15 && it.endIndex == 25 }

        val updated = applyTokenReplacement(sentence.text, target, "Vibe Coding")

        assertEquals("web coding and Vibe Coding", updated)
    }

    @Test
    fun applyTokenReplacement_sentenceState_keepsReplacementTokenClickable() {
        val sentence = SentenceSlice(
            text = "web coding",
            tokens = listOf(
                TokenPhrase(
                    text = "web coding",
                    startIndex = 0,
                    endIndex = 10,
                    isAsrSuspicious = true,
                    suggestions = listOf(ReplacementSuggestion(value = "Vibe Coding")),
                ),
            ),
        )
        val sentenceState = createReplacementSentenceState(sentence)
        val target = sentenceState.tokens.first()

        val updated = applyTokenReplacement(sentenceState, target, "Vibe Coding")

        assertEquals("Vibe Coding", updated.text)
        assertEquals(1, updated.tokens.size)
        assertEquals("Vibe Coding", updated.tokens.first().text)
        assertTrue(updated.tokens.first().isClickable)
        assertFalse(updated.tokens.first().isSuspicious)
    }

    @Test
    fun createReplacementSentenceState_restoresEditedTokensFromPersistedSentence() {
        val sentence = SentenceSlice(
            text = "今天我们主要聊 Vibe Coding。",
            tokens = listOf(
                TokenPhrase(
                    text = "今天",
                    startIndex = 0,
                    endIndex = 2,
                ),
                TokenPhrase(
                    text = "Vibe Coding",
                    startIndex = 8,
                    endIndex = 19,
                    isEdited = true,
                ),
            ),
        )

        val state = createReplacementSentenceState(sentence)

        assertEquals("Vibe Coding", state.tokens.last().text)
        assertTrue(state.tokens.last().isEdited)
        assertTrue(state.tokens.last().isClickable)
    }

    @Test
    fun visibleSuggestionBatch_rotatesAcrossFixedSuggestionsWithoutRequestingMore() {
        val suggestions = (1..10).map { index ->
            ReplacementSuggestion(value = "候选$index")
        }

        assertEquals(
            listOf("候选1", "候选2", "候选3", "候选4"),
            visibleSuggestionBatch(suggestions, pageIndex = 0, pageSize = 4).map { it.value },
        )
        assertEquals(
            listOf("候选5", "候选6", "候选7", "候选8"),
            visibleSuggestionBatch(suggestions, pageIndex = 1, pageSize = 4).map { it.value },
        )
        assertEquals(
            listOf("候选9", "候选10"),
            visibleSuggestionBatch(suggestions, pageIndex = 2, pageSize = 4).map { it.value },
        )
        assertEquals(1, nextSuggestionPageIndex(currentPageIndex = 0, totalSuggestions = 10, pageSize = 4))
        assertEquals(2, nextSuggestionPageIndex(currentPageIndex = 1, totalSuggestions = 10, pageSize = 4))
        assertEquals(0, nextSuggestionPageIndex(currentPageIndex = 2, totalSuggestions = 10, pageSize = 4))
    }
}
