from __future__ import annotations

import httpx


OPENAI_HTTP_TIMEOUT = httpx.Timeout(connect=10.0, read=120.0, write=120.0, pool=10.0)


def create_openai_async_client() -> httpx.AsyncClient:
    return httpx.AsyncClient(timeout=OPENAI_HTTP_TIMEOUT)
