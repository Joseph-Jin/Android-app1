from abc import ABC, abstractmethod


class CleanupProvider(ABC):
    @abstractmethod
    async def clean(self, transcript: str) -> str:
        raise NotImplementedError
