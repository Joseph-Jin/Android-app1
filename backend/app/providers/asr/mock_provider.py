from app.providers.asr.base import AsrProvider
from app.providers.mock_data import MOCK_RAW_TRANSCRIPT


class MockAsrProvider(AsrProvider):
    async def transcribe(self, audio_path: str) -> str:
        return MOCK_RAW_TRANSCRIPT
