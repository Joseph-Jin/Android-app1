import asyncio
import base64
import json
from pathlib import Path
import wave

from app.providers.asr.volcengine_standard_provider import VolcengineStandardAsrProvider
from app.providers.cleanup.openai_provider import OpenAICleanupProvider


class _FakeResponse:
    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, object]:
        return self._payload


class _SequencedClient:
    def __init__(self, responses: list[dict[str, object]]) -> None:
        self._responses = responses
        self.requests: list[dict[str, object]] = []

    async def post(self, url: str, **kwargs: object) -> _FakeResponse:
        self.requests.append({"url": url, **kwargs})
        return _FakeResponse(self._responses.pop(0))


def test_volcengine_standard_asr_provider_submits_audio_and_queries_result(tmp_path: Path):
    audio_path = tmp_path / "note.mp3"
    audio_path.write_bytes(b"fake audio bytes")
    client = _SequencedClient(
        responses=[
            {"code": 0, "id": "task-123"},
            {
                "code": 0,
                "message": "Success",
                "result": {
                    "text": "今天我们主要聊 Vibe Coding",
                },
            },
        ]
    )
    provider = VolcengineStandardAsrProvider(
        app_id="8705011315",
        access_token="token-123",
        resource_id="volc.bigasr.auc",
        submit_url="https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit",
        query_url="https://openspeech.bytedance.com/api/v3/auc/bigmodel/query",
        client=client,  # type: ignore[arg-type]
        request_id_factory=lambda: "req-123",
        uid="388808087185088",
        poll_interval_seconds=0.0,
        max_query_attempts=1,
        sleeper=lambda _seconds: asyncio.sleep(0),
    )

    transcript = asyncio.run(provider.transcribe(str(audio_path)))

    assert transcript == "今天我们主要聊 Vibe Coding"
    assert len(client.requests) == 2

    submit_request = client.requests[0]
    assert submit_request["url"] == "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit"
    assert submit_request["headers"] == {
        "X-Api-App-Key": "8705011315",
        "X-Api-Access-Key": "token-123",
        "X-Api-Resource-Id": "volc.bigasr.auc",
        "X-Api-Request-Id": "req-123",
        "X-Api-Sequence": "-1",
        "Content-Type": "application/json",
    }
    submit_payload = json.loads(submit_request["content"])  # type: ignore[arg-type]
    assert submit_payload["user"]["uid"] == "388808087185088"
    assert submit_payload["audio"]["format"] == "mp3"
    assert submit_payload["audio"]["data"] == base64.b64encode(b"fake audio bytes").decode()
    assert submit_payload["request"]["model_name"] == "bigmodel"
    assert submit_payload["request"]["enable_itn"] is True

    query_request = client.requests[1]
    assert query_request["url"] == "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query"
    query_payload = json.loads(query_request["content"])  # type: ignore[arg-type]
    assert query_payload["id"] == "task-123"


def test_volcengine_standard_asr_provider_can_prepare_unsupported_audio_before_submit(tmp_path: Path):
    source_audio_path = tmp_path / "note.m4a"
    source_audio_path.write_bytes(b"source audio")
    prepared_audio_path = tmp_path / "note.wav"
    prepared_audio_path.write_bytes(b"prepared wav")
    client = _SequencedClient(
        responses=[
            {},
            {
                "audio_info": {"duration": 1234},
                "result": {"text": "转码后的识别结果"},
            },
        ]
    )

    def fake_audio_preparer(audio_path: str):
        assert audio_path == str(source_audio_path)
        return str(prepared_audio_path), "wav", lambda: None

    provider = VolcengineStandardAsrProvider(
        app_id="8705011315",
        access_token="token-123",
        resource_id="volc.bigasr.auc",
        submit_url="https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit",
        query_url="https://openspeech.bytedance.com/api/v3/auc/bigmodel/query",
        client=client,  # type: ignore[arg-type]
        request_id_factory=lambda: "req-123",
        uid="388808087185088",
        poll_interval_seconds=0.0,
        max_query_attempts=1,
        sleeper=lambda _seconds: asyncio.sleep(0),
        audio_preparer=fake_audio_preparer,
    )

    transcript = asyncio.run(provider.transcribe(str(source_audio_path)))

    assert transcript == "转码后的识别结果"
    submit_request = client.requests[0]
    submit_payload = json.loads(submit_request["content"])  # type: ignore[arg-type]
    assert submit_payload["audio"]["format"] == "wav"
    assert submit_payload["audio"]["data"] == base64.b64encode(b"prepared wav").decode()


def test_volcengine_standard_asr_provider_submits_near_silent_wav(tmp_path: Path):
    audio_path = tmp_path / "silent.wav"
    with wave.open(str(audio_path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(16_000)
        wav_file.writeframes((b"\x00\x00") * 16_000)

    client = _SequencedClient(
        responses=[
            {"code": 0, "id": "task-silent"},
            {
                "code": 0,
                "result": {"text": ""},
            },
        ]
    )
    provider = VolcengineStandardAsrProvider(
        app_id="8705011315",
        access_token="token-123",
        resource_id="volc.bigasr.auc",
        submit_url="https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit",
        query_url="https://openspeech.bytedance.com/api/v3/auc/bigmodel/query",
        client=client,  # type: ignore[arg-type]
        poll_interval_seconds=0.0,
        max_query_attempts=2,
        sleeper=lambda _seconds: asyncio.sleep(0),
    )

    try:
        asyncio.run(provider.transcribe(str(audio_path)))
        raise AssertionError("expected fake client to exhaust polling responses")
    except IndexError:
        pass

    assert len(client.requests) >= 2


def test_openai_cleanup_provider_supports_ark_responses_input_shape():
    client = _SequencedClient(
        responses=[
            {
                "output": [
                    {
                        "type": "message",
                        "role": "assistant",
                        "content": [
                            {"type": "output_text", "text": "清洗后的内容"}
                        ],
                    }
                ]
            }
        ]
    )
    provider = OpenAICleanupProvider(
        api_key="ark-key",
        base_url="https://ark.cn-beijing.volces.com/api/v3",
        model="doubao-seed-2-0-mini-260215",
        client=client,  # type: ignore[arg-type]
    )

    cleaned = asyncio.run(provider.clean("嗯 今天我们主要聊 vibe coding 啊"))

    assert cleaned == "清洗后的内容"
    request = client.requests[0]
    assert request["url"] == "https://ark.cn-beijing.volces.com/api/v3/responses"
    assert request["headers"] == {"Authorization": "Bearer ark-key"}
    assert request["json"]["model"] == "doubao-seed-2-0-mini-260215"
    assert "保留原意" in request["json"]["instructions"]
    assert request["json"]["input"][0]["role"] == "user"
    content = request["json"]["input"][0]["content"]
    assert content[0] == {
        "type": "input_text",
        "text": "嗯 今天我们主要聊 vibe coding 啊",
    }
