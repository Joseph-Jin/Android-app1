package com.example.voicenotes.data.remote

interface VoiceNotesApi {
    suspend fun createNoteJob(request: UploadNoteRequest): UploadNoteResponse

    suspend fun getNoteJob(jobId: String): NoteJobSnapshot
}
