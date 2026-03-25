from __future__ import annotations

import asyncio
from dataclasses import replace
from pathlib import Path
import sqlite3
from uuid import uuid4

from fastapi import HTTPException, UploadFile

from app.config import load_settings
from app.providers.factory import build_provider_registry
from app.services.audio_storage import AudioStorage, StoredAudio
from app.services.suggestion_builder import (
    build_suggestion_candidates,
    build_token_suggestions,
)
from app.store.models import NoteJobRecord
from app.store.repository import NoteJobRepository
from app.services.text_segmentation import (
    extract_suspicious_phrases,
    split_sentences,
    tokenize_sentence,
)


def _collapse_whitespace(text: str) -> str:
    return " ".join(text.split()).strip()


def _format_error_message(exc: Exception) -> str:
    messages: list[str] = []
    current: BaseException | None = exc
    seen: set[int] = set()
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        message = str(current).strip()
        if message:
            messages.append(message)
        current = current.__cause__ or current.__context__
    return ": ".join(messages) if messages else exc.__class__.__name__


class _JobMapping:
    def __init__(self, repository: NoteJobRepository) -> None:
        self._repository = repository

    def __getitem__(self, job_id: str) -> NoteJobRecord:
        return self._repository.get_job(job_id)

    def __setitem__(self, job_id: str, record: NoteJobRecord) -> None:
        if job_id != record.job_id:
            raise ValueError("job_id must match the record being stored")
        self._repository.upsert_job(record)

    def get(self, job_id: str, default: NoteJobRecord | None = None) -> NoteJobRecord | None:
        try:
            return self._repository.get_job(job_id)
        except KeyError:
            return default


class NoteJobService:
    def __init__(
        self,
        *,
        data_dir: Path | str | None = None,
        storage: AudioStorage | None = None,
        repository: NoteJobRepository | None = None,
        provider_registry: dict[str, object] | None = None,
    ) -> None:
        resolved_data_dir = Path(data_dir) if data_dir is not None else load_settings().data_dir
        self._storage = storage or AudioStorage(resolved_data_dir / "uploads")
        self._repository = repository or NoteJobRepository(
            resolved_data_dir / "note-jobs.sqlite3"
        )
        self._jobs = _JobMapping(self._repository)
        self._provider_registry = provider_registry
        self._tasks: set[asyncio.Task[None]] = set()

    def _get_provider_registry(self) -> dict[str, object]:
        if self._provider_registry is not None:
            return self._provider_registry
        self._provider_registry = build_provider_registry()
        return self._provider_registry

    def _track_task(self, task: asyncio.Task[None]) -> None:
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    def _schedule_job_completion(self, job_id: str, stored_audio: StoredAudio) -> None:
        task = asyncio.create_task(self._complete_job(job_id, stored_audio))
        self._track_task(task)

    async def create_job(self, audio: UploadFile, card_id: str) -> NoteJobRecord:
        normalized_card_id = card_id.strip()
        if not normalized_card_id:
            raise HTTPException(status_code=400, detail="cardId is required")

        existing_record = self._repository.get_job_by_card_id(normalized_card_id)
        if existing_record is not None:
            return existing_record

        job_id = uuid4().hex
        stored_audio = await self._storage.save_upload(job_id, audio)
        record = NoteJobRecord(
            job_id=job_id,
            status="processing",
            audio_path=stored_audio.path,
            card_id=normalized_card_id,
        )
        try:
            self._repository.upsert_job(record)
        except sqlite3.IntegrityError:
            Path(stored_audio.path).unlink(missing_ok=True)
            existing_record = self._repository.get_job_by_card_id(normalized_card_id)
            if existing_record is not None:
                return existing_record
            raise
        self._schedule_job_completion(job_id, stored_audio)
        return record

    async def recover_processing_jobs(self) -> None:
        for record in self._repository.list_jobs_by_status("processing"):
            if not record.audio_path:
                continue
            stored_audio = StoredAudio(
                job_id=record.job_id,
                original_filename=Path(record.audio_path).name or "audio.bin",
                content_type=None,
                path=record.audio_path,
            )
            self._schedule_job_completion(record.job_id, stored_audio)

    async def _complete_job(self, job_id: str, stored_audio: StoredAudio) -> None:
        try:
            completed = await self._process_job(job_id, stored_audio)
        except Exception as exc:
            try:
                current_record = self._repository.get_job(job_id)
            except KeyError:
                current_record = NoteJobRecord(
                    job_id=job_id,
                    status="failed",
                    audio_path=stored_audio.path,
                )
            failed_record = replace(
                current_record,
                status="failed",
                error_message=_format_error_message(exc),
            )
            self._repository.upsert_job(failed_record)
            return

        self._repository.upsert_job(completed)

    async def _process_job(self, job_id: str, stored_audio: StoredAudio) -> NoteJobRecord:
        raw_transcript = await self._transcribe(stored_audio.path, stored_audio.original_filename)
        clean_text = await self._clean(raw_transcript)
        suspicious_phrases = extract_suspicious_phrases(clean_text)
        sentences = self._build_sentences(job_id, clean_text, suspicious_phrases)
        tokens = self._build_tokens(job_id, clean_text, suspicious_phrases)
        suggestions = build_token_suggestions(clean_text, suspicious_phrases)
        return NoteJobRecord(
            job_id=job_id,
            status="completed",
            raw_transcript=raw_transcript,
            clean_text=clean_text,
            sentences=sentences,
            tokens=tokens,
            suggestions=suggestions,
            audio_path=stored_audio.path,
        )

    def _build_tokens(
        self,
        job_id: str,
        text: str,
        suspicious_phrases: list[str],
        *,
        sentence_index: int | None = None,
        offset: int = 0,
    ) -> list[dict[str, object]]:
        token_entries: list[dict[str, object]] = []
        token_prefix = (
            f"{job_id}-sentence-{sentence_index}" if sentence_index is not None else job_id
        )
        for token_index, token in enumerate(
            tokenize_sentence(text, suspicious_phrases, offset=offset)
        ):
            token_entries.append(
                {
                    **token,
                    "id": f"{token_prefix}-token-{token_index}",
                    "suggestions": build_suggestion_candidates(token["text"])
                    if token["isAsrSuspicious"]
                    else [],
                }
            )
        return token_entries

    def _build_sentences(
        self, job_id: str, clean_text: str, suspicious_phrases: list[str]
    ) -> list[dict[str, object]]:
        sentence_texts = split_sentences(clean_text)
        sentences: list[dict[str, object]] = []
        cursor = 0
        for index, sentence_text in enumerate(sentence_texts):
            sentence_start = clean_text.find(sentence_text, cursor)
            if sentence_start < 0:
                raise RuntimeError("Unable to align sentence offsets")
            sentences.append(
                {
                    "id": f"{job_id}-sentence-{index}",
                    "text": sentence_text,
                    "tokens": self._build_tokens(
                        job_id,
                        sentence_text,
                        suspicious_phrases,
                        sentence_index=index,
                        offset=sentence_start,
                    ),
                }
            )
            cursor = sentence_start + len(sentence_text)
        return sentences

    async def _transcribe(self, audio_path: str, original_filename: str) -> str:
        providers = self._get_provider_registry()
        provider = providers.get("asr")
        if provider is None:
            raise RuntimeError("ASR provider is not configured")
        try:
            transcript = await provider.transcribe(audio_path)  # type: ignore[union-attr]
        except Exception as exc:
            raise RuntimeError("ASR provider failed to transcribe audio") from exc
        transcript = _collapse_whitespace(transcript)
        if not transcript:
            raise RuntimeError("ASR provider returned an empty transcript")
        return transcript

    async def _clean(self, transcript: str) -> str:
        providers = self._get_provider_registry()
        provider = providers.get("cleanup")
        if provider is None:
            raise RuntimeError("cleanup provider is not configured")
        try:
            cleaned = await provider.clean(transcript)  # type: ignore[union-attr]
        except Exception as exc:
            raise RuntimeError("cleanup provider failed to clean transcript") from exc
        cleaned = _collapse_whitespace(cleaned)
        if not cleaned:
            raise RuntimeError("cleanup provider returned an empty transcript")
        return cleaned

    def get_job(self, job_id: str) -> NoteJobRecord:
        try:
            return self._repository.get_job(job_id)
        except KeyError as exc:
            raise HTTPException(status_code=404, detail="Note job not found") from exc
