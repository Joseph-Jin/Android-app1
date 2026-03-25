package com.example.voicenotes.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

internal class RetrofitVoiceNotesApi(
    baseUrl: String,
    private val service: VoiceNotesService = buildService(baseUrl),
) : VoiceNotesApi {
    override suspend fun createNoteJob(request: UploadNoteRequest): UploadNoteResponse {
        return service.createNoteJob(
            cardId = request.cardId.toRequestBody(TEXT_PLAIN),
            fileName = request.fileName.toRequestBody(TEXT_PLAIN),
            mimeType = request.mimeType.toRequestBody(TEXT_PLAIN),
            createdAtEpochMillis = request.createdAtEpochMillis.toString().toRequestBody(TEXT_PLAIN),
            audio = request.toAudioPart(),
        )
    }

    override suspend fun getNoteJob(jobId: String): NoteJobSnapshot {
        return service.getNoteJob(jobId)
    }

    private fun UploadNoteRequest.toAudioPart(): MultipartBody.Part {
        val mime = mimeType.toMediaTypeOrNull() ?: DEFAULT_AUDIO_MEDIA_TYPE
        val body = audioBytes.toRequestBody(mime)
        return MultipartBody.Part.createFormData(
            name = "audio",
            filename = fileName.ifBlank { "$cardId.m4a" },
            body = body,
        )
    }

    private companion object {
        val DEFAULT_AUDIO_MEDIA_TYPE = "application/octet-stream".toMediaType()
        val TEXT_PLAIN = "text/plain".toMediaType()
    }
}

internal interface VoiceNotesService {
    @Multipart
    @POST("api/note-jobs")
    suspend fun createNoteJob(
        @Part("cardId") cardId: okhttp3.RequestBody,
        @Part("fileName") fileName: okhttp3.RequestBody,
        @Part("mimeType") mimeType: okhttp3.RequestBody,
        @Part("createdAtEpochMillis") createdAtEpochMillis: okhttp3.RequestBody,
        @Part audio: MultipartBody.Part,
    ): UploadNoteResponse

    @GET("api/note-jobs/{jobId}")
    suspend fun getNoteJob(
        @Path("jobId") jobId: String,
    ): NoteJobSnapshot
}

internal fun buildService(baseUrl: String): VoiceNotesService {
    val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    return Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(
            OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .build(),
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(VoiceNotesService::class.java)
}
