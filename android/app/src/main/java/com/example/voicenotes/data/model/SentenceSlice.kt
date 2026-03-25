package com.example.voicenotes.data.model

import java.util.UUID

data class SentenceSlice(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val tokens: List<TokenPhrase> = emptyList(),
    val rawSourceSpan: String? = null,
)
