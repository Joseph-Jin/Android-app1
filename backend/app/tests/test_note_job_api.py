import asyncio
import time

from fastapi.testclient import TestClient

from app.main import app
from app.services.note_job_service import NoteJobService


def test_create_note_job_returns_processing_status():
    client = TestClient(app)

    response = client.post(
        "/api/note-jobs",
        data={"cardId": "card-123"},
        files={"audio": ("note.m4a", b"fake-audio", "audio/m4a")},
    )

    assert response.status_code == 202
    body = response.json()
    assert body["status"] == "processing"
    assert "jobId" in body


def test_create_note_job_reuses_existing_job_for_duplicate_card_id(monkeypatch):
    service = NoteJobService()
    monkeypatch.setattr(service, "_schedule_job_completion", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(app.state, "note_job_service", service, raising=False)

    client = TestClient(app)

    first_response = client.post(
        "/api/note-jobs",
        data={"cardId": "card-123"},
        files={"audio": ("note.m4a", b"fake-audio", "audio/m4a")},
    )
    second_response = client.post(
        "/api/note-jobs",
        data={"cardId": "card-123"},
        files={"audio": ("note.m4a", b"fake-audio", "audio/m4a")},
    )

    assert first_response.status_code == 202
    assert second_response.status_code == 202
    assert first_response.json()["jobId"] == second_response.json()["jobId"]


def test_note_job_is_pollable_while_processing(monkeypatch):
    service = NoteJobService()
    original_process_job = service._process_job

    async def slow_process_job(job_id, stored_audio):
        await asyncio.sleep(0.2)
        return await original_process_job(job_id, stored_audio)

    monkeypatch.setattr(service, "_process_job", slow_process_job)
    monkeypatch.setattr(app.state, "note_job_service", service, raising=False)

    client = TestClient(app)

    response = client.post(
        "/api/note-jobs",
        data={"cardId": "card-456"},
        files={"audio": ("note.m4a", b"fake-audio", "audio/m4a")},
    )

    job_id = response.json()["jobId"]
    status_response = client.get(f"/api/note-jobs/{job_id}")

    assert status_response.status_code == 200
    assert status_response.json()["status"] == "processing"


def test_failed_note_job_returns_error_message(monkeypatch):
    service = NoteJobService()

    async def failing_process_job(job_id, stored_audio):
        raise RuntimeError("provider unavailable")

    monkeypatch.setattr(service, "_process_job", failing_process_job)
    monkeypatch.setattr(app.state, "note_job_service", service, raising=False)

    client = TestClient(app)

    response = client.post(
        "/api/note-jobs",
        data={"cardId": "card-789"},
        files={"audio": ("note.m4a", b"fake-audio", "audio/m4a")},
    )

    job_id = response.json()["jobId"]
    for _ in range(20):
        status_response = client.get(f"/api/note-jobs/{job_id}")
        if status_response.json()["status"] == "failed":
            break
        time.sleep(0.01)

    assert status_response.status_code == 200
    assert status_response.json()["status"] == "failed"
    assert status_response.json()["errorMessage"] == "provider unavailable"


def test_app_startup_recovers_processing_jobs(monkeypatch):
    service = NoteJobService()
    called = False

    async def recover_processing_jobs():
        nonlocal called
        called = True

    service.recover_processing_jobs = recover_processing_jobs  # type: ignore[method-assign]
    monkeypatch.setattr(app.state, "note_job_service", service, raising=False)

    with TestClient(app):
        pass

    assert called
