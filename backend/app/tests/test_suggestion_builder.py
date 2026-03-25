from app.services.suggestion_builder import build_suggestion_candidates
from app.services.suggestion_builder import build_token_suggestions
from app.services.text_segmentation import extract_suspicious_phrases


def test_build_token_suggestions_marks_complete_phrase():
    result = build_token_suggestions(
        sentence="我们准备接入 Web coding 做原型。",
        suspicious_phrases=["Web coding"],
    )

    assert result[0]["text"] == "Web coding"
    assert result[0]["isAsrSuspicious"] is True
    assert result[0]["suggestions"][0]["value"] != "Web coding"
    assert "ASR" in result[0]["suggestions"][0]["reason"]


def test_asr_heuristic_ignores_generic_english_phrases():
    assert extract_suspicious_phrases("We had a good morning and kept walking.") == []
    assert build_suggestion_candidates("good morning") == []
