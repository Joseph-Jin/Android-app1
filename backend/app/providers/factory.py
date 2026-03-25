from __future__ import annotations

from collections.abc import Callable

from app.config import Settings, load_settings
from app.providers.asr.base import AsrProvider
from app.providers.asr.mock_provider import MockAsrProvider
from app.providers.asr.openai_provider import OpenAIAsrProvider
from app.providers.asr.volcengine_standard_provider import VolcengineStandardAsrProvider
from app.providers.cleanup.base import CleanupProvider
from app.providers.cleanup.mock_provider import MockCleanupProvider
from app.providers.cleanup.openai_provider import OpenAICleanupProvider


def _require_api_key(settings: Settings, provider_name: str) -> None:
    if provider_name == "ASR":
        if not settings.asr_api_key:
            raise RuntimeError("ASR_API_KEY is required when using the ASR provider")
        return

    if provider_name == "cleanup" and not settings.cleanup_api_key:
        raise RuntimeError(
            "CLEANUP_API_KEY is required when using the cleanup provider"
        )


def _build_openai_asr_provider(settings: Settings) -> AsrProvider:
    _require_api_key(settings, "ASR")
    return OpenAIAsrProvider(
        api_key=settings.asr_api_key,
        base_url=settings.asr_base_url,
        model=settings.asr_model,
    )


def _build_openai_cleanup_provider(settings: Settings) -> CleanupProvider:
    _require_api_key(settings, "cleanup")
    return OpenAICleanupProvider(
        api_key=settings.cleanup_api_key,
        base_url=settings.cleanup_base_url,
        model=settings.cleanup_model,
    )


def _build_mock_asr_provider(settings: Settings) -> AsrProvider:
    return MockAsrProvider()


def _build_volcengine_standard_asr_provider(settings: Settings) -> AsrProvider:
    if not settings.volc_asr_app_id:
        raise RuntimeError("VOLCENGINE_ASR_APP_ID is required when using the volc_asr_standard provider")
    if not settings.volc_asr_access_token:
        raise RuntimeError("VOLCENGINE_ASR_ACCESS_TOKEN is required when using the volc_asr_standard provider")
    return VolcengineStandardAsrProvider(
        app_id=settings.volc_asr_app_id,
        access_token=settings.volc_asr_access_token,
        resource_id=settings.volc_asr_resource_id,
        submit_url=settings.volc_asr_submit_url,
        query_url=settings.volc_asr_query_url,
        model_name=settings.volc_asr_model_name,
        uid=settings.volc_asr_uid,
    )


def _build_mock_cleanup_provider(settings: Settings) -> CleanupProvider:
    return MockCleanupProvider()


_ASR_PROVIDER_BUILDERS: dict[str, Callable[[Settings], AsrProvider]] = {
    "openai": _build_openai_asr_provider,
    "openai_compatible": _build_openai_asr_provider,
    "volc_asr_standard": _build_volcengine_standard_asr_provider,
    "mock": _build_mock_asr_provider,
}

_CLEANUP_PROVIDER_BUILDERS: dict[str, Callable[[Settings], CleanupProvider]] = {
    "openai": _build_openai_cleanup_provider,
    "openai_compatible": _build_openai_cleanup_provider,
    "mock": _build_mock_cleanup_provider,
}


def _resolve_provider_builder(
    provider_name: str,
    builders: dict[str, Callable[[Settings], object]],
    kind: str,
) -> Callable[[Settings], object]:
    try:
        return builders[provider_name]
    except KeyError as exc:
        raise ValueError(f"Unsupported {kind} provider: {provider_name}") from exc


def build_provider_registry(settings: Settings | None = None) -> dict[str, AsrProvider | CleanupProvider]:
    active_settings = settings or load_settings()
    asr_builder = _resolve_provider_builder(
        active_settings.asr_provider,
        _ASR_PROVIDER_BUILDERS,
        "ASR",
    )
    cleanup_builder = _resolve_provider_builder(
        active_settings.cleanup_provider,
        _CLEANUP_PROVIDER_BUILDERS,
        "cleanup",
    )
    return {
        "asr": asr_builder(active_settings),
        "cleanup": cleanup_builder(active_settings),
    }
