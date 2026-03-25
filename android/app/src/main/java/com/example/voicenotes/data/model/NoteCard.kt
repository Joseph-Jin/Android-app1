package com.example.voicenotes.data.model

import java.util.UUID

enum class NoteStatus(val value: String) {
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed");

    companion object {
        fun fromValue(value: String): NoteStatus {
            return entries.firstOrNull { it.value == value } ?: PROCESSING
        }
    }
}

data class NoteCard(
    val id: String = UUID.randomUUID().toString(),
    val createdAtEpochMillis: Long,
    val status: NoteStatus,
    val audioLocalPath: String,
    val rawTranscript: String? = null,
    val cleanText: String? = null,
    val sentences: List<SentenceSlice> = emptyList(),
    val copyText: String? = null,
    val errorMessage: String? = null,
    val remoteJobId: String? = null,
)
