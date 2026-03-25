from __future__ import annotations

from dataclasses import dataclass
import re


_SENTENCE_SPLIT_PATTERN = re.compile(r"[^。！？!?\.]+[。！？!?\.]?")
_TOKEN_PATTERN = re.compile(
    r"[\u4e00-\u9fff]+|[A-Za-z0-9]+(?:[/-][A-Za-z0-9]+)*",
    re.UNICODE,
)

_COMMON_CJK_WORDS = {
    "今天",
    "我们",
    "主要",
    "稍后",
    "提示词",
    "整理",
    "一下",
    "语音",
    "识别",
    "错误",
    "词语",
    "专业",
    "推荐",
    "替换",
    "修改",
    "复制",
    "结果",
    "内容",
    "处理",
    "清洗",
    "刷新",
    "按钮",
    "高亮",
    "这个",
    "那个",
    "可以",
    "直接",
    "继续",
    "用户",
    "点击",
    "分段",
    "句子",
    "卡片",
    "今天",
    "主要",
    "我们",
    "稍后",
    "整理",
    "一下",
    "聊",
    "我",
    "会",
    "把",
    "和",
}
_MAX_CJK_WORD_LENGTH = max(len(word) for word in _COMMON_CJK_WORDS)

_ASR_SUSPICIOUS_PHRASES = (
    "web coding",
    "open ai",
    "chat gpt",
)


@dataclass(frozen=True, slots=True)
class TextSpan:
    text: str
    start_index: int
    end_index: int
    is_asr_suspicious: bool = False

    def to_dict(self) -> dict[str, object]:
        return {
            "text": self.text,
            "startIndex": self.start_index,
            "endIndex": self.end_index,
            "isAsrSuspicious": self.is_asr_suspicious,
        }


def split_sentences(text: str) -> list[str]:
    cleaned = text.strip()
    if not cleaned:
        return []

    pieces = [piece.strip() for piece in _SENTENCE_SPLIT_PATTERN.findall(cleaned)]
    return [piece for piece in pieces if piece]


def _merge_spans(spans: list[tuple[int, int]]) -> list[tuple[int, int]]:
    merged: list[tuple[int, int]] = []
    for start, end in sorted(spans):
        if start >= end:
            continue
        if not merged or start > merged[-1][1]:
            merged.append((start, end))
            continue
        previous_start, previous_end = merged[-1]
        merged[-1] = (previous_start, max(previous_end, end))
    return merged


def _find_protected_spans(sentence: str, protected_phrases: list[str]) -> list[tuple[int, int]]:
    spans: list[tuple[int, int]] = []
    for phrase in sorted({phrase.strip() for phrase in protected_phrases if phrase.strip()}, key=len, reverse=True):
        pattern = re.compile(re.escape(phrase), re.IGNORECASE)
        for match in pattern.finditer(sentence):
            spans.append((match.start(), match.end()))
    return _merge_spans(spans)


def _tokenize_plain_text(text: str, offset: int = 0) -> list[TextSpan]:
    tokens: list[TextSpan] = []
    for match in _TOKEN_PATTERN.finditer(text):
        token_text = match.group(0)
        if token_text.isspace():
            continue
        if re.fullmatch(r"[\u4e00-\u9fff]+", token_text):
            tokens.extend(
                _split_cjk_text(
                    token_text,
                    offset=offset + match.start(),
                )
            )
            continue
        tokens.append(
            TextSpan(
                text=token_text,
                start_index=offset + match.start(),
                end_index=offset + match.end(),
            )
        )
    return tokens


def _split_cjk_text(text: str, offset: int = 0) -> list[TextSpan]:
    if not text:
        return []

    tokens: list[TextSpan] = []
    cursor = 0
    while cursor < len(text):
        matched_word: str | None = None
        matched_length = 0
        remaining = len(text) - cursor
        for length in range(min(_MAX_CJK_WORD_LENGTH, remaining), 0, -1):
            candidate = text[cursor:cursor + length]
            if candidate in _COMMON_CJK_WORDS:
                matched_word = candidate
                matched_length = length
                break
        if matched_word is not None:
            tokens.append(
                TextSpan(
                    text=matched_word,
                    start_index=offset + cursor,
                    end_index=offset + cursor + matched_length,
                )
            )
            cursor += matched_length
            continue

        next_boundary = cursor + 1
        while next_boundary < len(text):
            next_match_found = False
            for length in range(min(_MAX_CJK_WORD_LENGTH, len(text) - next_boundary), 0, -1):
                if text[next_boundary:next_boundary + length] in _COMMON_CJK_WORDS:
                    next_match_found = True
                    break
            if next_match_found:
                break
            next_boundary += 1
        tokens.append(
            TextSpan(
                text=text[cursor:next_boundary],
                start_index=offset + cursor,
                end_index=offset + next_boundary,
            )
        )
        cursor = next_boundary
    return tokens


def tokenize_sentence(
    sentence: str,
    protected_phrases: list[str] | None = None,
    *,
    offset: int = 0,
) -> list[dict[str, object]]:
    protected_spans = _find_protected_spans(sentence, protected_phrases or [])
    if not protected_spans:
        return [span.to_dict() for span in _tokenize_plain_text(sentence, offset)]

    tokens: list[TextSpan] = []
    cursor = 0
    for start, end in protected_spans:
        if cursor < start:
            tokens.extend(_tokenize_plain_text(sentence[cursor:start], offset + cursor))
        tokens.append(
            TextSpan(
                text=sentence[start:end],
                start_index=offset + start,
                end_index=offset + end,
                is_asr_suspicious=True,
            )
        )
        cursor = end
    if cursor < len(sentence):
        tokens.extend(_tokenize_plain_text(sentence[cursor:], offset + cursor))
    return [span.to_dict() for span in tokens]


def extract_suspicious_phrases(text: str) -> list[str]:
    phrases: list[tuple[int, str]] = []
    seen: set[str] = set()
    for suspicious_phrase in _ASR_SUSPICIOUS_PHRASES:
        pattern = re.compile(rf"\b{re.escape(suspicious_phrase)}\b", re.IGNORECASE)
        for match in pattern.finditer(text):
            phrase = match.group(0).strip()
            normalized = phrase.casefold()
            if phrase and normalized not in seen:
                phrases.append((match.start(), phrase))
                seen.add(normalized)
    phrases.sort(key=lambda item: item[0])
    return [phrase for _, phrase in phrases]
