from __future__ import annotations

from pathlib import Path

import httpx

from app.providers.asr.base import AsrProvider
from app.providers.openai_http import create_openai_async_client


class OpenAIAsrProvider(AsrProvider):
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
        self.model = model or "whisper-1"
        self._client = client or create_openai_async_client()
        self._owns_client = client is None

    async def transcribe(self, audio_path: str) -> str:
        if not self.api_key:
            raise RuntimeError("OPENAI_API_KEY is required for OpenAI ASR")

        audio_file_path = Path(audio_path)
        request_url = f"{self.base_url}/audio/transcriptions"
        with audio_file_path.open("rb") as audio_file:
            response = await self._client.post(
                request_url,
                headers={"Authorization": f"Bearer {self.api_key}"},
                data={"model": self.model},
                files={"file": (audio_file_path.name, audio_file)},
            )

        response.raise_for_status()
        payload = response.json()
        if isinstance(payload, dict):
            transcript = payload.get("text")
            if isinstance(transcript, str) and transcript.strip():
                return transcript
        raise RuntimeError("OpenAI ASR response did not include transcript text")

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()
