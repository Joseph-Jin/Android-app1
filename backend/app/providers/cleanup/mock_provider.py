from app.providers.cleanup.base import CleanupProvider
from app.providers.mock_data import MOCK_CLEAN_TRANSCRIPT


class MockCleanupProvider(CleanupProvider):
    async def clean(self, transcript: str) -> str:
        return MOCK_CLEAN_TRANSCRIPT
