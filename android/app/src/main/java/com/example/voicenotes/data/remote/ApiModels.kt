package com.example.voicenotes.data.remote

data class UploadNoteRequest(
    val cardId: String,
    val fileName: String,
    val mimeType: String,
    val audioBytes: ByteArray,
    val createdAtEpochMillis: Long,
)

data class UploadNoteResponse(
    val jobId: String,
    val status: String = "processing",
)

data class SentenceSliceDto(
    val id: String,
    val text: String,
    val tokens: List<TokenPhraseDto> = emptyList(),
    val rawSourceSpan: String? = null,
)

data class TokenPhraseDto(
    val id: String,
    val text: String,
    val startIndex: Int,
    val endIndex: Int,
    val isAsrSuspicious: Boolean = false,
    val suggestions: List<ReplacementSuggestionDto> = emptyList(),
)

data class ReplacementSuggestionDto(
    val value: String,
    val reason: String? = null,
)

data class NoteJobSnapshot(
    val jobId: String,
    val status: String,
    val rawTranscript: String? = null,
    val cleanText: String? = null,
    val sentences: List<SentenceSliceDto> = emptyList(),
    val errorMessage: String? = null,
)
