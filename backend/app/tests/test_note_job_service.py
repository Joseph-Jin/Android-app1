import asyncio
from pathlib import Path

from app.config import Settings
from app.providers.factory import build_provider_registry
from app.services.note_job_service import NoteJobRecord
from app.services.note_job_service import NoteJobService
from app.services.audio_storage import StoredAudio
from app.store.repository import NoteJobRepository
from app.services.text_segmentation import tokenize_sentence


class FakeUpload:
    def __init__(
        self,
        filename: str = "note.m4a",
        content: bytes = b"fake-audio",
        content_type: str = "audio/m4a",
    ) -> None:
        self.filename = filename
        self.content_type = content_type
        self._content = content

    async def read(self) -> bytes:
        return self._content


def test_tokenize_sentence_ignores_punctuation_tokens():
    tokens = tokenize_sentence("第一句测试 Web coding。", ["Web coding"])

    assert [token["text"] for token in tokens] == ["第一句测试", "Web coding"]


def test_process_job_uses_global_token_offsets_for_multi_sentence_notes():
    service = NoteJobService()

    async def fake_transcribe(audio_path: str, original_filename: str) -> str:
        return "ignored"

    async def fake_clean(transcript: str) -> str:
        return "第一句测试 Web coding。第二句继续。"

    service._transcribe = fake_transcribe  # type: ignore[method-assign]
    service._clean = fake_clean  # type: ignore[method-assign]

    stored_audio = StoredAudio(
        job_id="job123",
        original_filename="note.m4a",
        content_type="audio/m4a",
        path="/tmp/note.m4a",
    )

    record = asyncio.run(service._process_job("job123", stored_audio))

    assert [token["text"] for token in record.tokens] == [
        "第一句测试",
        "Web coding",
        "第二句",
        "继续",
    ]
    assert record.tokens[0]["startIndex"] == 0
    assert record.tokens[1]["startIndex"] > record.tokens[0]["startIndex"]
    assert record.tokens[2]["startIndex"] > record.tokens[1]["startIndex"]
    assert record.tokens[3]["startIndex"] > record.tokens[2]["startIndex"]
    assert record.tokens[1]["suggestions"]
    assert record.sentences[0]["tokens"][0]["startIndex"] == 0
    assert record.sentences[0]["tokens"][1]["startIndex"] == record.tokens[1]["startIndex"]
    assert record.sentences[1]["tokens"][0]["startIndex"] == record.tokens[2]["startIndex"]


def test_process_job_with_mock_providers_generates_clickable_asr_tokens(tmp_path):
    service = NoteJobService(
        data_dir=tmp_path,
        provider_registry=build_provider_registry(
            Settings(asr_provider="mock", cleanup_provider="mock")
        ),
    )
    audio_path = tmp_path / "note.m4a"
    audio_path.write_bytes(b"fake-audio")
    stored_audio = StoredAudio(
        job_id="job123",
        original_filename="note.m4a",
        content_type="audio/m4a",
        path=str(audio_path),
    )

    record = asyncio.run(service._process_job("job123", stored_audio))

    assert record.raw_transcript == "今天我们主要聊 web coding 和 open ai 稍后我会把 chat gpt 的提示词整理一下"
    assert record.clean_text == "今天我们主要聊 Web coding 和 open ai。稍后我会把 Chat GPT 的提示词整理一下。"
    assert [sentence["text"] for sentence in record.sentences] == [
        "今天我们主要聊 Web coding 和 open ai。",
        "稍后我会把 Chat GPT 的提示词整理一下。",
    ]
    suspicious_tokens = [token for token in record.tokens if token["isAsrSuspicious"]]
    assert [token["text"] for token in suspicious_tokens] == ["Web coding", "open ai", "Chat GPT"]
    assert suspicious_tokens[0]["suggestions"][0]["value"] == "Vibe Coding"
    assert suspicious_tokens[1]["suggestions"][0]["value"] == "OpenAI"
    assert suspicious_tokens[2]["suggestions"][0]["value"] == "ChatGPT"


def test_complete_job_marks_failed_when_processing_cannot_finish():
    service = NoteJobService()

    stored_audio = StoredAudio(
        job_id="job123",
        original_filename="note.m4a",
        content_type="audio/m4a",
        path="/tmp/note.m4a",
    )
    service._jobs["job123"] = NoteJobRecord(
        job_id="job123",
        status="processing",
        audio_path=stored_audio.path,
    )

    async def failing_process_job(job_id: str, stored_audio: StoredAudio) -> NoteJobRecord:
        raise RuntimeError("provider unavailable")

    service._process_job = failing_process_job  # type: ignore[method-assign]

    asyncio.run(service._complete_job("job123", stored_audio))

    record = service.get_job("job123")
    assert record.status == "failed"
    assert record.raw_transcript == ""
    assert record.clean_text == ""
    assert record.sentences == []
    assert record.tokens == []
    assert record.suggestions == []
    assert record.error_message == "provider unavailable"


def test_complete_job_preserves_provider_bootstrap_error_message(monkeypatch):
    service = NoteJobService()

    stored_audio = StoredAudio(
        job_id="job123",
        original_filename="note.m4a",
        content_type="audio/m4a",
        path="/tmp/note.m4a",
    )
    service._jobs["job123"] = NoteJobRecord(
        job_id="job123",
        status="processing",
        audio_path=stored_audio.path,
    )

    def failing_build_provider_registry():
        raise RuntimeError("ASR_API_KEY is required when using the ASR provider")

    monkeypatch.setattr(
        "app.services.note_job_service.build_provider_registry",
        failing_build_provider_registry,
    )

    asyncio.run(service._complete_job("job123", stored_audio))

    record = service.get_job("job123")
    assert record.status == "failed"
    assert record.error_message == "ASR_API_KEY is required when using the ASR provider"


def test_note_jobs_persist_across_service_restarts(tmp_path):
    db_path = tmp_path / "note-jobs.sqlite3"
    repository = NoteJobRepository(db_path)
    first_service = NoteJobService(repository=repository)

    record = NoteJobRecord(
        job_id="job123",
        status="completed",
        raw_transcript="raw",
        clean_text="clean",
        sentences=[{"id": "sentence-1", "text": "clean", "tokens": []}],
        tokens=[{"id": "token-1", "text": "clean"}],
        suggestions=[],
        audio_path="/tmp/note.m4a",
    )
    first_service._repository.upsert_job(record)

    second_service = NoteJobService(repository=NoteJobRepository(db_path))

    restored = second_service.get_job("job123")

    assert restored.status == "completed"
    assert restored.raw_transcript == "raw"
    assert restored.clean_text == "clean"
    assert restored.sentences == record.sentences
    assert restored.tokens == record.tokens


def test_create_job_reuses_persisted_card_id_after_restart(tmp_path):
    db_path = tmp_path / "note-jobs.sqlite3"
    repository = NoteJobRepository(db_path)
    first_service = NoteJobService(data_dir=tmp_path, repository=repository)
    first_service._schedule_job_completion = lambda *_args, **_kwargs: None  # type: ignore[method-assign]

    first_record = asyncio.run(
        first_service.create_job(FakeUpload(), card_id="card-123")
    )

    second_service = NoteJobService(data_dir=tmp_path, repository=NoteJobRepository(db_path))
    second_service._schedule_job_completion = lambda *_args, **_kwargs: None  # type: ignore[method-assign]

    second_record = asyncio.run(
        second_service.create_job(FakeUpload(), card_id="card-123")
    )

    assert second_record.job_id == first_record.job_id
    assert second_service.get_job(first_record.job_id).card_id == "card-123"
    assert len(list((tmp_path / "uploads").iterdir())) == 1


def test_complete_job_preserves_card_id_when_processing_finishes(tmp_path):
    db_path = tmp_path / "note-jobs.sqlite3"
    service = NoteJobService(data_dir=tmp_path, repository=NoteJobRepository(db_path))

    stored_audio = StoredAudio(
        job_id="job123",
        original_filename="note.m4a",
        content_type="audio/m4a",
        path="/tmp/note.m4a",
    )
    service._jobs["job123"] = NoteJobRecord(
        job_id="job123",
        status="processing",
        audio_path=stored_audio.path,
        card_id="card-123",
    )

    async def fake_process_job(job_id: str, stored_audio: StoredAudio) -> NoteJobRecord:
        return NoteJobRecord(
            job_id=job_id,
            status="completed",
            raw_transcript="raw",
            clean_text="clean",
            audio_path=stored_audio.path,
        )

    service._process_job = fake_process_job  # type: ignore[method-assign]

    asyncio.run(service._complete_job("job123", stored_audio))

    record = service.get_job("job123")
    assert record.status == "completed"
    assert record.card_id == "card-123"


def test_recover_processing_jobs_reschedules_persisted_jobs(tmp_path):
    db_path = tmp_path / "note-jobs.sqlite3"
    repository = NoteJobRepository(db_path)
    repository.upsert_job(
        NoteJobRecord(
            job_id="job123",
            status="processing",
            audio_path=str(Path("/tmp/note.m4a")),
        )
    )

    service = NoteJobService(repository=NoteJobRepository(db_path))

    async def fake_process_job(job_id: str, stored_audio: StoredAudio) -> NoteJobRecord:
        return NoteJobRecord(
            job_id=job_id,
            status="completed",
            raw_transcript="raw",
            clean_text="clean",
            audio_path=stored_audio.path,
        )

    service._process_job = fake_process_job  # type: ignore[method-assign]

    async def run_recovery() -> NoteJobRecord:
        await service.recover_processing_jobs()
        for _ in range(10):
            restored = service.get_job("job123")
            if restored.status == "completed":
                return restored
            await asyncio.sleep(0)
        return service.get_job("job123")

    restored = asyncio.run(run_recovery())

    assert restored.status == "completed"
    assert restored.raw_transcript == "raw"
    assert restored.clean_text == "clean"


def test_note_job_service_uses_configured_data_dir(monkeypatch, tmp_path):
    data_dir = tmp_path / "persistent-data"
    monkeypatch.setenv("VOICE_NOTES_DATA_DIR", str(data_dir))

    service = NoteJobService()

    assert service._repository._db_path == data_dir / "note-jobs.sqlite3"
    assert service._storage._base_dir == data_dir / "uploads"
