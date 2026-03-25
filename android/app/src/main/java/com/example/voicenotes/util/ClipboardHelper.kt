package com.example.voicenotes.util

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString

fun copyTextToClipboard(
    clipboardManager: ClipboardManager,
    text: String,
) {
    clipboardManager.setText(AnnotatedString(text))
}
