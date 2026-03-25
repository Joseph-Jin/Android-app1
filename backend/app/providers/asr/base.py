from abc import ABC, abstractmethod


class AsrProvider(ABC):
    @abstractmethod
    async def transcribe(self, audio_path: str) -> str:
        raise NotImplementedError
