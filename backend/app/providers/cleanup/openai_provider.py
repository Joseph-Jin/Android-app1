from __future__ import annotations

import httpx

from app.providers.cleanup.base import CleanupProvider
from app.providers.openai_http import create_openai_async_client


_CLEANUP_INSTRUCTIONS = (
    "你是一个中文语音转写清洗助手。"
    "你的任务是把 ASR 转写结果清洗成保留原话感的干净版内容。"
    "严格遵守："
    "1. 保留原意，不新增事实，不补充用户没说过的信息。"
    "2. 删除口头禅、重复、明显停顿词、无意义语气词。"
    "3. 修正明显的 ASR 误识别，但仅在高置信度时修正；不确定时尽量保留原词。"
    "4. 保留原来的表达顺序，不要重组段落，不要总结，不要扩写。"
    "5. 输出通顺自然的书面中文，但不要改得太像正式文章。"
    "6. 专有名词、英文名、产品名尽量保留。"
    "7. 只输出最终清洗后的文本，不要解释，不要加标题，不要加引号。"
)

_INVALID_CLEANUP_PREFIXES = (
    "你是一个中文语音转写清洗助手",
    "请提供需要清洗的ASR转写结果",
    "请提供需要清洗的 asr 转写结果",
)


def _extract_output_text(payload: object) -> str | None:
    if isinstance(payload, str):
        stripped = payload.strip()
        return stripped or None

    if not isinstance(payload, dict):
        return None

    output_text = payload.get("output_text")
    if isinstance(output_text, str):
        stripped = output_text.strip()
        if stripped:
            return stripped

    output = payload.get("output")
    if not isinstance(output, list):
        return None

    for item in output:
        if not isinstance(item, dict):
            continue
        content = item.get("content")
        if not isinstance(content, list):
            continue
        for part in content:
            if not isinstance(part, dict):
                continue
            text = part.get("text")
            if isinstance(text, str):
                stripped = text.strip()
                if stripped:
                    return stripped
    return None


class OpenAICleanupProvider(CleanupProvider):
    def __init__(
        self,
        *,
        api_key: str | None,
        base_url: str = "https://api.openai.com/v1",
        model: str | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model = model or "gpt-4.1-mini"
        self._client = client or create_openai_async_client()
        self._owns_client = client is None

    async def clean(self, transcript: str) -> str:
        if not self.api_key:
            raise RuntimeError("OPENAI_API_KEY is required for OpenAI cleanup")

        response = await self._client.post(
            f"{self.base_url}/responses",
            headers={"Authorization": f"Bearer {self.api_key}"},
            json={
                "model": self.model,
                "instructions": _CLEANUP_INSTRUCTIONS,
                "input": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "input_text",
                                "text": transcript,
                            },
                        ],
                    }
                ],
            },
        )

        response.raise_for_status()
        cleaned = _extract_output_text(response.json())
        if cleaned and _is_valid_cleaned_text(cleaned):
            return cleaned
        raise RuntimeError("OpenAI cleanup response returned invalid cleaned text")

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()


def _is_valid_cleaned_text(text: str) -> bool:
    normalized = " ".join(text.split()).strip().lower()
    if not normalized:
        return False
    if "严格遵守" in normalized and "保留原意" in normalized:
        return False
    return not any(normalized.startswith(prefix.lower()) for prefix in _INVALID_CLEANUP_PREFIXES)
