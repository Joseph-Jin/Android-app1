from __future__ import annotations

from pathlib import Path
import sqlite3

from app.config import load_settings
from app.store.models import NoteJobRecord


def _default_db_path() -> Path:
    return load_settings().data_dir / "note-jobs.sqlite3"


class NoteJobRepository:
    def __init__(self, db_path: Path | str | None = None) -> None:
        self._db_path = Path(db_path) if db_path is not None else _default_db_path()
        self._db_path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self._db_path)
        connection.row_factory = sqlite3.Row
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS note_jobs (
                    job_id TEXT PRIMARY KEY,
                    card_id TEXT,
                    status TEXT NOT NULL,
                    raw_transcript TEXT NOT NULL,
                    clean_text TEXT NOT NULL,
                    sentences_json TEXT NOT NULL,
                    tokens_json TEXT NOT NULL,
                    suggestions_json TEXT NOT NULL,
                    audio_path TEXT NOT NULL,
                    error_message TEXT NOT NULL
                )
                """
            )
            columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(note_jobs)").fetchall()
            }
            if "card_id" not in columns:
                connection.execute("ALTER TABLE note_jobs ADD COLUMN card_id TEXT")
            connection.execute(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS note_jobs_card_id_idx
                ON note_jobs(card_id)
                """
            )

    def upsert_job(self, record: NoteJobRecord) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO note_jobs (
                    job_id,
                    card_id,
                    status,
                    raw_transcript,
                    clean_text,
                    sentences_json,
                    tokens_json,
                    suggestions_json,
                    audio_path,
                    error_message
                )
                VALUES (
                    :job_id,
                    :card_id,
                    :status,
                    :raw_transcript,
                    :clean_text,
                    :sentences_json,
                    :tokens_json,
                    :suggestions_json,
                    :audio_path,
                    :error_message
                )
                ON CONFLICT(job_id) DO UPDATE SET
                    card_id = COALESCE(excluded.card_id, note_jobs.card_id),
                    status = excluded.status,
                    raw_transcript = excluded.raw_transcript,
                    clean_text = excluded.clean_text,
                    sentences_json = excluded.sentences_json,
                    tokens_json = excluded.tokens_json,
                    suggestions_json = excluded.suggestions_json,
                    audio_path = excluded.audio_path,
                    error_message = excluded.error_message
                """,
                record.to_row(),
            )

    def get_job_by_card_id(self, card_id: str) -> NoteJobRecord | None:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT
                    job_id,
                    card_id,
                    status,
                    raw_transcript,
                    clean_text,
                    sentences_json,
                    tokens_json,
                    suggestions_json,
                    audio_path,
                    error_message
                FROM note_jobs
                WHERE card_id = ?
                """,
                (card_id,),
            ).fetchone()

        if row is None:
            return None

        return NoteJobRecord.from_row(row)

    def get_job(self, job_id: str) -> NoteJobRecord:
        with self._connect() as connection:
            row = connection.execute(
                """
                SELECT
                    job_id,
                    card_id,
                    status,
                    raw_transcript,
                    clean_text,
                    sentences_json,
                    tokens_json,
                    suggestions_json,
                    audio_path,
                    error_message
                FROM note_jobs
                WHERE job_id = ?
                """,
                (job_id,),
            ).fetchone()

        if row is None:
            raise KeyError(job_id)

        return NoteJobRecord.from_row(row)

    def list_jobs_by_status(self, status: str) -> list[NoteJobRecord]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT
                    job_id,
                    card_id,
                    status,
                    raw_transcript,
                    clean_text,
                    sentences_json,
                    tokens_json,
                    suggestions_json,
                    audio_path,
                    error_message
                FROM note_jobs
                WHERE status = ?
                ORDER BY job_id
                """,
                (status,),
            ).fetchall()

        return [NoteJobRecord.from_row(row) for row in rows]
