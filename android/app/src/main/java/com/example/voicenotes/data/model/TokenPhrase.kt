package com.example.voicenotes.data.model

import java.util.UUID

data class TokenPhrase(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startIndex: Int,
    val endIndex: Int,
    val isAsrSuspicious: Boolean = false,
    val isEdited: Boolean = false,
    val suggestions: List<ReplacementSuggestion> = emptyList(),
)
