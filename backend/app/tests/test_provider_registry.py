import pytest

from app.config import Settings
from app.providers.asr.mock_provider import MockAsrProvider
from app.providers.asr.openai_provider import OpenAIAsrProvider
from app.providers.cleanup.mock_provider import MockCleanupProvider
from app.providers.cleanup.openai_provider import OpenAICleanupProvider
from app.providers.factory import build_provider_registry


def test_provider_registry_uses_independent_settings_for_each_capability():
    settings = Settings(
        asr_provider="openai",
        cleanup_provider="openai",
        asr_api_key="asr-key",
        cleanup_api_key="cleanup-key",
        asr_base_url="https://asr.example/v1",
        cleanup_base_url="https://cleanup.example/v1",
        asr_model="asr-model",
        cleanup_model="cleanup-model",
    )

    registry = build_provider_registry(settings)

    assert isinstance(registry["asr"], OpenAIAsrProvider)
    assert isinstance(registry["cleanup"], OpenAICleanupProvider)
    assert registry["asr"].api_key == "asr-key"
    assert registry["asr"].base_url == "https://asr.example/v1"
    assert registry["asr"].model == "asr-model"
    assert registry["cleanup"].api_key == "cleanup-key"
    assert registry["cleanup"].base_url == "https://cleanup.example/v1"
    assert registry["cleanup"].model == "cleanup-model"


def test_provider_registry_supports_mock_providers_without_api_keys():
    settings = Settings(
        asr_provider="mock",
        cleanup_provider="mock",
    )

    registry = build_provider_registry(settings)

    assert isinstance(registry["asr"], MockAsrProvider)
    assert isinstance(registry["cleanup"], MockCleanupProvider)


def test_provider_registry_supports_openai_compatible_provider_names():
    settings = Settings(
        asr_provider="openai_compatible",
        cleanup_provider="openai_compatible",
        asr_api_key="asr-key",
        cleanup_api_key="cleanup-key",
        asr_base_url="https://dashscope.example/compatible-mode/v1",
        cleanup_base_url="https://minimax.example/v1",
        asr_model="qwen-asr",
        cleanup_model="minimax-text",
    )

    registry = build_provider_registry(settings)

    assert isinstance(registry["asr"], OpenAIAsrProvider)
    assert isinstance(registry["cleanup"], OpenAICleanupProvider)
    assert registry["asr"].base_url == "https://dashscope.example/compatible-mode/v1"
    assert registry["cleanup"].base_url == "https://minimax.example/v1"


def test_provider_registry_allows_mixing_openai_compatible_and_mock():
    settings = Settings(
        asr_provider="openai_compatible",
        cleanup_provider="mock",
        asr_api_key="asr-key",
        asr_base_url="https://dashscope.example/compatible-mode/v1",
        asr_model="qwen-asr",
    )

    registry = build_provider_registry(settings)

    assert isinstance(registry["asr"], OpenAIAsrProvider)
    assert isinstance(registry["cleanup"], MockCleanupProvider)


@pytest.mark.parametrize(
    ("settings", "message"),
    [
        (
            Settings(asr_provider="groq", cleanup_provider="openai", asr_api_key="asr-key"),
            "Unsupported ASR provider: groq",
        ),
        (
            Settings(asr_provider="openai", cleanup_provider="anthropic", cleanup_api_key="cleanup-key"),
            "Unsupported cleanup provider: anthropic",
        ),
    ],
)
def test_provider_registry_rejects_unsupported_provider_names(settings, message):
    with pytest.raises(ValueError, match=message):
        build_provider_registry(settings)


def test_openai_asr_selection_requires_asr_api_key():
    settings = Settings(
        asr_provider="openai",
        cleanup_provider="openai",
        cleanup_api_key="cleanup-key",
    )

    with pytest.raises(RuntimeError, match="ASR_API_KEY"):
        build_provider_registry(settings)


def test_openai_cleanup_selection_requires_cleanup_api_key():
    settings = Settings(
        asr_provider="openai",
        cleanup_provider="openai",
        asr_api_key="asr-key",
    )

    with pytest.raises(RuntimeError, match="CLEANUP_API_KEY"):
        build_provider_registry(settings)
