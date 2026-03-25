from __future__ import annotations

import re


_COMMON_ASR_REPLACEMENTS: dict[str, list[tuple[str, str]]] = {
    "web coding": [
        (
            "Vibe Coding",
            "Likely ASR confusion for the spoken phrase 'vibe coding'.",
        ),
        (
            "Vibe coding",
            "Likely ASR confusion for the spoken phrase 'vibe coding'.",
        ),
        (
            "vibe coding",
            "Lowercase variant of the likely spoken phrase.",
        ),
        (
            "VibeCoding",
            "Collapsed product-style spelling for the likely spoken phrase.",
        ),
        (
            "Live Coding",
            "Alternative spoken-tech phrase often confused by ASR.",
        ),
        (
            "Web Coding",
            "Capitalized fallback if the current wording was actually intended.",
        ),
    ],
    "vibe coding": [
        (
            "Vibe Coding",
            "Normalize the common product phrase that sounds similar in speech.",
        ),
        (
            "Vibe coding",
            "Sentence-case normalization for the common spoken phrase.",
        ),
        (
            "vibe coding",
            "Lowercase normalization for the common spoken phrase.",
        ),
        (
            "VibeCoding",
            "Collapsed product-style spelling for the common spoken phrase.",
        ),
        (
            "Live Coding",
            "Nearby spoken-tech phrase that may also fit the context.",
        ),
        (
            "Web Coding",
            "Fallback in case the original wording was intended.",
        ),
    ],
    "open ai": [
        (
            "OpenAI",
            "Common product-name correction for ASR output.",
        ),
        (
            "Open AI",
            "Spaced product-name variant for ASR correction.",
        ),
        (
            "OpenAi",
            "Camel-case variant of the product name.",
        ),
        (
            "OPEN AI",
            "Uppercase variant of the product name.",
        ),
        (
            "Open-AI",
            "Hyphenated variant that still preserves the intended name.",
        ),
        (
            "Open A.I.",
            "Punctuated variant that can match spoken letter-by-letter output.",
        ),
    ],
    "chat gpt": [
        (
            "ChatGPT",
            "Common product-name correction for ASR output.",
        ),
        (
            "Chat GPT",
            "Spaced product-name variant for ASR correction.",
        ),
        (
            "chatGPT",
            "Lower-leading camel-case product-name variant.",
        ),
        (
            "Chat-GPT",
            "Hyphenated variant that still preserves the intended name.",
        ),
        (
            "CHAT GPT",
            "Uppercase variant of the product name.",
        ),
        (
            "Chat G.P.T.",
            "Punctuated variant that can match spoken letter-by-letter output.",
        ),
        (
            "Chat G P T",
            "Letter-separated variant that can match spoken letter-by-letter output.",
        ),
        (
            "Chat G.P.T",
            "Partially punctuated acronym variant for ASR correction.",
        ),
    ],
}


def _find_phrase_spans(sentence: str, suspicious_phrases: list[str]) -> list[tuple[int, int]]:
    spans: list[tuple[int, int]] = []
    seen: set[tuple[int, int]] = set()
    for phrase in sorted({phrase.strip() for phrase in suspicious_phrases if phrase.strip()}, key=len, reverse=True):
        pattern = re.compile(re.escape(phrase), re.IGNORECASE)
        for match in pattern.finditer(sentence):
            span = (match.start(), match.end())
            if span in seen:
                continue
            spans.append(span)
            seen.add(span)
    spans.sort(key=lambda item: item[0])
    return spans


def build_suggestion_candidates(phrase: str) -> list[dict[str, str]]:
    normalized = re.sub(r"\s+", " ", phrase).strip()
    if not normalized:
        return []

    key = normalized.lower()
    candidates: list[dict[str, str]] = []
    seen_values: set[str] = set()

    def add_candidate(value: str, reason: str) -> None:
        candidate_value = value.strip()
        if not candidate_value or candidate_value in seen_values:
            return
        candidates.append({"value": candidate_value, "reason": reason})
        seen_values.add(candidate_value)

    for value, reason in _COMMON_ASR_REPLACEMENTS.get(key, []):
        add_candidate(value, reason)

    return candidates


def build_token_suggestions(sentence: str, suspicious_phrases: list[str]) -> list[dict[str, object]]:
    suggestions: list[dict[str, object]] = []
    for start, end in _find_phrase_spans(sentence, suspicious_phrases):
        text = sentence[start:end]
        suggestions.append(
            {
                "text": text,
                "startIndex": start,
                "endIndex": end,
                "isAsrSuspicious": True,
                "suggestions": build_suggestion_candidates(text),
            }
        )
    return suggestions
