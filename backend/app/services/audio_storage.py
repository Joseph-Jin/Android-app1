from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from fastapi import UploadFile

from app.config import load_settings


@dataclass(frozen=True, slots=True)
class StoredAudio:
    job_id: str
    original_filename: str
    content_type: str | None
    path: str


class AudioStorage:
    def __init__(self, base_dir: Path | None = None) -> None:
        self._base_dir = base_dir or (load_settings().data_dir / "uploads")

    async def save_upload(self, job_id: str, upload: UploadFile) -> StoredAudio:
        job_dir = self._base_dir / job_id
        job_dir.mkdir(parents=True, exist_ok=True)

        original_name = Path(upload.filename or "audio.bin").name
        destination = job_dir / original_name

        contents = await upload.read()
        destination.write_bytes(contents)

        return StoredAudio(
            job_id=job_id,
            original_filename=original_name,
            content_type=upload.content_type,
            path=str(destination),
        )
