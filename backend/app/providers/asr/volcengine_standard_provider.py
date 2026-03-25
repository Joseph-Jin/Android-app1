from __future__ import annotations

import asyncio
import base64
import json
import os
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path

import httpx

from app.providers.asr.base import AsrProvider


class VolcengineStandardAsrProvider(AsrProvider):
    def __init__(
        self,
        *,
        app_id: str | None,
        access_token: str | None,
        resource_id: str = "volc.bigasr.auc",
        submit_url: str = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit",
        query_url: str = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query",
        model_name: str = "bigmodel",
        enable_itn: bool = True,
        uid: str = "voice-notes-user",
        client: httpx.AsyncClient | None = None,
        request_id_factory: callable | None = None,
        poll_interval_seconds: float = 1.0,
        max_query_attempts: int = 60,
        sleeper: callable | None = None,
        audio_preparer: callable | None = None,
    ) -> None:
        self.app_id = app_id
        self.access_token = access_token
        self.resource_id = resource_id
        self.submit_url = submit_url
        self.query_url = query_url
        self.model_name = model_name
        self.enable_itn = enable_itn
        self.uid = uid
        self._client = client or httpx.AsyncClient(timeout=httpx.Timeout(connect=10.0, read=120.0, write=120.0, pool=10.0))
        self._owns_client = client is None
        self._request_id_factory = request_id_factory or (lambda: str(uuid.uuid4()))
        self._poll_interval_seconds = poll_interval_seconds
        self._max_query_attempts = max_query_attempts
        self._sleeper = sleeper or asyncio.sleep
        self._audio_preparer = audio_preparer or _prepare_audio_for_volcengine

    async def transcribe(self, audio_path: str) -> str:
        if not self.app_id:
            raise RuntimeError("VOLCENGINE_ASR_APP_ID is required for volc_asr_standard")
        if not self.access_token:
            raise RuntimeError("VOLCENGINE_ASR_ACCESS_TOKEN is required for volc_asr_standard")

        prepared_audio_path, _audio_format, cleanup = self._audio_preparer(audio_path)
        try:
            task_id = await self._submit(prepared_audio_path, _audio_format)
            return await self._query_until_finished(task_id)
        finally:
            cleanup()

    async def _submit(self, audio_path: str, audio_format: str) -> str:
        audio_file_path = Path(audio_path)
        audio_data = base64.b64encode(audio_file_path.read_bytes()).decode()
        request_id = self._request_id_factory()

        response = await self._client.post(
            self.submit_url,
            headers=self._headers(request_id),
            content=json.dumps(
                {
                    "user": {"uid": self.uid},
                    "audio": {
                        "format": audio_format,
                        "data": audio_data,
                    },
                    "request": {
                        "model_name": self.model_name,
                        "enable_itn": self.enable_itn,
                    },
                }
            ),
        )
        response.raise_for_status()
        payload = response.json()
        if isinstance(payload, dict) and payload.get("code") == 0 and isinstance(payload.get("id"), str):
            return payload["id"]
        response_headers = getattr(response, "headers", {})
        response_request_id = None
        if isinstance(response_headers, dict):
            response_request_id = response_headers.get("X-Api-Request-Id") or response_headers.get("x-api-request-id")
        if response_request_id:
            return response_request_id
        return request_id

    async def _query_until_finished(self, task_id: str) -> str:
        for _ in range(self._max_query_attempts):
            response = await self._client.post(
                self.query_url,
                headers=self._headers(task_id),
                content=json.dumps({"id": task_id}),
            )
            response.raise_for_status()
            payload = response.json()
            if not isinstance(payload, dict):
                raise RuntimeError("Volcengine ASR query response was invalid")

            result = payload.get("result")
            if isinstance(result, dict):
                text = result.get("text")
                if isinstance(text, str) and text.strip():
                    return text.strip()

            await self._sleeper(self._poll_interval_seconds)

        raise RuntimeError("Volcengine ASR query timed out")

    def _headers(self, request_id: str) -> dict[str, str]:
        return {
            "X-Api-App-Key": self.app_id or "",
            "X-Api-Access-Key": self.access_token or "",
            "X-Api-Resource-Id": self.resource_id,
            "X-Api-Request-Id": request_id,
            "X-Api-Sequence": "-1",
            "Content-Type": "application/json",
        }

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()


def _prepare_audio_for_volcengine(audio_path: str) -> tuple[str, str, callable]:
    source_path = Path(audio_path)
    audio_format = source_path.suffix.lstrip(".").lower()
    if audio_format in {"wav", "mp3", "ogg"}:
        return str(source_path), audio_format, lambda: None

    afconvert_path = shutil.which("afconvert")
    if not afconvert_path:
        return str(source_path), audio_format or "mp3", lambda: None

    fd, temp_path = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    subprocess.run(
        [
            afconvert_path,
            "-f",
            "WAVE",
            "-d",
            "LEI16@16000",
            str(source_path),
            temp_path,
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    def cleanup() -> None:
        Path(temp_path).unlink(missing_ok=True)

    return temp_path, "wav", cleanup
