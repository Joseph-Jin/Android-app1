package com.example.voicenotes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "note_cards")
data class NoteCardEntity(
    @PrimaryKey val id: String,
    val createdAtEpochMillis: Long,
    val status: String,
    val audioLocalPath: String,
    val rawTranscript: String? = null,
    val cleanText: String? = null,
    val sentencesJson: String = "[]",
    val copyText: String? = null,
    val errorMessage: String? = null,
    val remoteJobId: String? = null,
)
