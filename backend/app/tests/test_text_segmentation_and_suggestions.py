from app.services.suggestion_builder import build_suggestion_candidates
from app.services.text_segmentation import tokenize_sentence


def test_tokenize_sentence_splits_chinese_runs_into_smaller_clickable_tokens():
    tokens = tokenize_sentence("今天我们主要聊 Web coding。", ["Web coding"])

    assert [token["text"] for token in tokens] == ["今天", "我们", "主要", "聊", "Web coding"]


def test_build_suggestion_candidates_returns_multiple_rotation_batches_for_known_asr_phrase():
    suggestions = build_suggestion_candidates("chat gpt")

    assert len(suggestions) >= 6
    assert suggestions[0]["value"] == "ChatGPT"
    assert len({item["value"] for item in suggestions}) == len(suggestions)


def test_build_suggestion_candidates_returns_enough_candidates_for_web_coding_rotation():
    suggestions = build_suggestion_candidates("web coding")

    assert len(suggestions) >= 6
    assert suggestions[0]["value"] == "Vibe Coding"
    assert len({item["value"] for item in suggestions}) == len(suggestions)


def test_build_suggestion_candidates_returns_enough_candidates_for_open_ai_rotation():
    suggestions = build_suggestion_candidates("open ai")

    assert len(suggestions) >= 6
    assert suggestions[0]["value"] == "OpenAI"
    assert len({item["value"] for item in suggestions}) == len(suggestions)
