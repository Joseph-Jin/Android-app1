from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import os


@dataclass(frozen=True, slots=True)
class Settings:
    data_dir: Path = Path("data") / "voice-notes"
    asr_provider: str = "openai"
    cleanup_provider: str = "openai"
    asr_api_key: str | None = None
    asr_base_url: str = "https://api.openai.com/v1"
    asr_model: str | None = None
    cleanup_api_key: str | None = None
    cleanup_base_url: str = "https://api.openai.com/v1"
    cleanup_model: str | None = None
    volc_asr_app_id: str | None = None
    volc_asr_access_token: str | None = None
    volc_asr_resource_id: str = "volc.bigasr.auc"
    volc_asr_submit_url: str = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit"
    volc_asr_query_url: str = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query"
    volc_asr_model_name: str = "bigmodel"
    volc_asr_uid: str = "voice-notes-user"


def _env(*names: str, default: str | None = None) -> str | None:
    for name in names:
        value = os.getenv(name)
        if value is None:
            continue
        stripped = value.strip()
        if stripped:
            return stripped
    return default


def load_settings() -> Settings:
    return Settings(
        data_dir=Path(
            _env("VOICE_NOTES_DATA_DIR", default=str(Path("data") / "voice-notes"))
            or Path("data") / "voice-notes"
        ),
        asr_provider=_env("ASR_PROVIDER", default="openai") or "openai",
        cleanup_provider=_env("CLEANUP_PROVIDER", default="openai") or "openai",
        asr_api_key=_env("ASR_API_KEY", "OPENAI_API_KEY"),
        asr_base_url=_env(
            "ASR_BASE_URL",
            "OPENAI_BASE_URL",
            default="https://api.openai.com/v1",
        )
        or "https://api.openai.com/v1",
        asr_model=_env("ASR_MODEL", "OPENAI_ASR_MODEL"),
        cleanup_api_key=_env("CLEANUP_API_KEY", "OPENAI_API_KEY"),
        cleanup_base_url=_env(
            "CLEANUP_BASE_URL",
            "OPENAI_BASE_URL",
            default="https://api.openai.com/v1",
        )
        or "https://api.openai.com/v1",
        cleanup_model=_env("CLEANUP_MODEL", "OPENAI_CLEANUP_MODEL"),
        volc_asr_app_id=_env("VOLCENGINE_ASR_APP_ID"),
        volc_asr_access_token=_env("VOLCENGINE_ASR_ACCESS_TOKEN"),
        volc_asr_resource_id=_env("VOLCENGINE_ASR_RESOURCE_ID", default="volc.bigasr.auc") or "volc.bigasr.auc",
        volc_asr_submit_url=_env(
            "VOLCENGINE_ASR_SUBMIT_URL",
            default="https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit",
        )
        or "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit",
        volc_asr_query_url=_env(
            "VOLCENGINE_ASR_QUERY_URL",
            default="https://openspeech.bytedance.com/api/v3/auc/bigmodel/query",
        )
        or "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query",
        volc_asr_model_name=_env("VOLCENGINE_ASR_MODEL_NAME", default="bigmodel") or "bigmodel",
        volc_asr_uid=_env("VOLCENGINE_ASR_UID", default="voice-notes-user") or "voice-notes-user",
    )
