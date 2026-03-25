import asyncio

import httpx

from app.providers.asr.openai_provider import OpenAIAsrProvider
from app.providers.cleanup.openai_provider import OpenAICleanupProvider


class _FakeResponse:
    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, object]:
        return self._payload


class _FakeClient:
    def __init__(self, response_payload: dict[str, object]) -> None:
        self.response_payload = response_payload
        self.requests: list[dict[str, object]] = []

    async def post(self, url: str, **kwargs: object) -> _FakeResponse:
        self.requests.append({"url": url, **kwargs})
        return _FakeResponse(self.response_payload)


def test_openai_asr_provider_transcribes_audio_via_openai_endpoint(tmp_path):
    audio_path = tmp_path / "note.m4a"
    audio_path.write_bytes(b"fake audio")
    client = _FakeClient({"text": "Hello world"})
    provider = OpenAIAsrProvider(
        api_key="sk-test",
        base_url="https://api.example.test/v1",
        model="gpt-4o-mini-transcribe",
        client=client,  # type: ignore[arg-type]
    )

    transcript = asyncio.run(provider.transcribe(str(audio_path)))

    assert transcript == "Hello world"
    assert len(client.requests) == 1
    request = client.requests[0]
    assert request["url"] == "https://api.example.test/v1/audio/transcriptions"
    assert request["headers"] == {"Authorization": "Bearer sk-test"}
    assert request["data"] == {"model": "gpt-4o-mini-transcribe"}

    file_name, file_handle = request["files"]["file"]  # type: ignore[index]
    assert file_name == "note.m4a"
    assert file_handle.closed


def test_openai_cleanup_provider_cleans_transcript_via_responses_api():
    client = _FakeClient(
        {
            "output": [
                {
                    "type": "message",
                    "role": "assistant",
                    "content": [
                        {"type": "output_text", "text": "Cleaned transcript"}
                    ],
                }
            ]
        }
    )
    provider = OpenAICleanupProvider(
        api_key="sk-test",
        base_url="https://api.example.test/v1",
        model="gpt-4.1-mini",
        client=client,  # type: ignore[arg-type]
    )

    cleaned = asyncio.run(provider.clean("raw transcript"))

    assert cleaned == "Cleaned transcript"
    assert len(client.requests) == 1
    request = client.requests[0]
    assert request["url"] == "https://api.example.test/v1/responses"
    assert request["headers"] == {"Authorization": "Bearer sk-test"}
    assert request["json"]["model"] == "gpt-4.1-mini"
    assert "保留原意" in request["json"]["instructions"]
    assert request["json"]["input"][0]["role"] == "user"
    content = request["json"]["input"][0]["content"]
    assert content[0] == {
        "type": "input_text",
        "text": "raw transcript",
    }

def test_openai_cleanup_provider_rejects_prompt_echo_outputs():
    client = _FakeClient(
        {
            "output_text": "你是一个中文语音转写清洗助手。你的任务是把 ASR 转写结果清洗成保留原话感的干净版内容。",
        }
    )
    provider = OpenAICleanupProvider(
        api_key="sk-test",
        base_url="https://api.example.test/v1",
        model="gpt-4.1-mini",
        client=client,  # type: ignore[arg-type]
    )

    try:
        asyncio.run(provider.clean("raw transcript"))
        raise AssertionError("expected echoed cleanup prompt to be rejected")
    except RuntimeError as exc:
        assert "invalid cleaned text" in str(exc)


def test_openai_cleanup_provider_rejects_meta_follow_up_outputs():
    client = _FakeClient(
        {
            "output_text": "请提供需要清洗的ASR转写结果，我会按照要求为你清洗处理。",
        }
    )
    provider = OpenAICleanupProvider(
        api_key="sk-test",
        base_url="https://api.example.test/v1",
        model="gpt-4.1-mini",
        client=client,  # type: ignore[arg-type]
    )

    try:
        asyncio.run(provider.clean("raw transcript"))
        raise AssertionError("expected meta follow-up answer to be rejected")
    except RuntimeError as exc:
        assert "invalid cleaned text" in str(exc)


def test_openai_providers_create_clients_with_explicit_timeout_configuration():
    asr_provider = OpenAIAsrProvider(api_key="sk-test")
    cleanup_provider = OpenAICleanupProvider(api_key="sk-test")

    try:
        assert asr_provider._client.timeout == httpx.Timeout(  # type: ignore[attr-defined]
            connect=10.0,
            read=120.0,
            write=120.0,
            pool=10.0,
        )
        assert cleanup_provider._client.timeout == httpx.Timeout(  # type: ignore[attr-defined]
            connect=10.0,
            read=120.0,
            write=120.0,
            pool=10.0,
        )
    finally:
        asyncio.run(asr_provider.aclose())
        asyncio.run(cleanup_provider.aclose())
