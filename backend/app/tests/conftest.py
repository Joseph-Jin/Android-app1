from __future__ import annotations

import os
import tempfile
from pathlib import Path


_TEST_DATA_DIR_HANDLE = tempfile.TemporaryDirectory(prefix="voice-notes-tests-")
_TEST_DATA_DIR = Path(_TEST_DATA_DIR_HANDLE.name) / "voice-notes"
os.environ["VOICE_NOTES_DATA_DIR"] = str(_TEST_DATA_DIR)
