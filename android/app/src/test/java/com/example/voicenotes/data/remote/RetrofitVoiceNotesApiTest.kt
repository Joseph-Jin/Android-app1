package com.example.voicenotes.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrofitVoiceNotesApiTest {

    @Test
    fun createNoteJob_postsMultipartAudioAndMetadata() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"jobId":"job-123","status":"processing"}"""
                ),
        )
        server.start()

        try {
            val api = RetrofitVoiceNotesApi(server.url("/").toString())
            val response = api.createNoteJob(
                UploadNoteRequest(
                    cardId = "card-1",
                    fileName = "note.m4a",
                    mimeType = "audio/mp4",
                    audioBytes = "fake-audio".toByteArray(),
                    createdAtEpochMillis = 1234L,
                )
            )

            assertEquals("job-123", response.jobId)

            val request = server.takeRequest()
            assertEquals("/api/note-jobs", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"cardId\""))
            assertTrue(body.contains("card-1"))
            assertTrue(body.contains("name=\"fileName\""))
            assertTrue(body.contains("note.m4a"))
            assertTrue(body.contains("name=\"audio\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun getNoteJob_fetchesJobSnapshotFromNetwork() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "jobId":"job-123",
                      "status":"completed",
                      "cleanText":"finished",
                      "rawTranscript":"original",
                      "sentences":[{"id":"sentence-1","text":"finished"}]
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val api = RetrofitVoiceNotesApi(server.url("/").toString())

            val snapshot = api.getNoteJob("job-123")

            assertEquals("job-123", snapshot.jobId)
            assertEquals("completed", snapshot.status)
            assertEquals("finished", snapshot.cleanText)

            val request = server.takeRequest()
            assertEquals("/api/note-jobs/job-123", request.path)
        } finally {
            server.shutdown()
        }
    }
}
