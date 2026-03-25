from __future__ import annotations

from dataclasses import dataclass, field
import json


@dataclass(slots=True)
class NoteJobRecord:
    job_id: str
    status: str
    raw_transcript: str = ""
    clean_text: str = ""
    sentences: list[dict[str, object]] = field(default_factory=list)
    tokens: list[dict[str, object]] = field(default_factory=list)
    suggestions: list[dict[str, object]] = field(default_factory=list)
    audio_path: str = ""
    error_message: str = ""
    card_id: str = ""

    def to_response(self) -> dict[str, object]:
        return {
            "jobId": self.job_id,
            "status": self.status,
            "rawTranscript": self.raw_transcript,
            "cleanText": self.clean_text,
            "sentences": self.sentences,
            "tokens": self.tokens,
            "suggestions": self.suggestions,
            "errorMessage": self.error_message or None,
        }

    def to_row(self) -> dict[str, object]:
        return {
            "job_id": self.job_id,
            "card_id": self.card_id or None,
            "status": self.status,
            "raw_transcript": self.raw_transcript,
            "clean_text": self.clean_text,
            "sentences_json": json.dumps(self.sentences, ensure_ascii=False),
            "tokens_json": json.dumps(self.tokens, ensure_ascii=False),
            "suggestions_json": json.dumps(self.suggestions, ensure_ascii=False),
            "audio_path": self.audio_path,
            "error_message": self.error_message,
        }

    @classmethod
    def from_row(cls, row: object) -> "NoteJobRecord":
        return cls(
            job_id=row["job_id"],
            card_id=row["card_id"] or "",
            status=row["status"],
            raw_transcript=row["raw_transcript"] or "",
            clean_text=row["clean_text"] or "",
            sentences=json.loads(row["sentences_json"] or "[]"),
            tokens=json.loads(row["tokens_json"] or "[]"),
            suggestions=json.loads(row["suggestions_json"] or "[]"),
            audio_path=row["audio_path"] or "",
            error_message=row["error_message"] or "",
        )
