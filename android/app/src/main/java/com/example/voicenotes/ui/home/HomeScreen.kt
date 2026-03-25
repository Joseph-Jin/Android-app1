package com.example.voicenotes.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.voicenotes.audio.HoldToRecordController
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val clipboardManager = LocalClipboardManager.current
    val controller = remember(context) { HoldToRecordController(context) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var hasRecordAudioPermission by remember {
        mutableStateOf(checkRecordAudioPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordAudioPermission = granted
        if (!granted) {
            viewModel.onRecordCanceled("需要麦克风权限才能录音")
        }
    }

    DisposableEffect(controller) {
        onDispose {
            controller.release()
        }
    }

    LaunchedEffect(uiState.isRecording) {
        while (controller.isRecording()) {
            viewModel.onRecordingAmplitudeSample(controller.sampleAmplitude())
            delay(80L)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        if (lifecycleOwner == null) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasRecordAudioPermission = checkRecordAudioPermission(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ),
                ),
            ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Voice Notes",
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    actions = {
                        TextButton(
                            onClick = { showClearAllDialog = true },
                            enabled = uiState.cards.isNotEmpty(),
                        ) {
                            Text("清空记录")
                        }
                    }
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HoldToRecordButton(
                        uiState = uiState,
                        hasRecordAudioPermission = hasRecordAudioPermission,
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onPressStart = {
                            if (controller.startRecording()) {
                                viewModel.onRecordPressed()
                            } else {
                                viewModel.onRecordCanceled("无法开始录音，请检查麦克风权限后重试")
                            }
                        },
                        onPressStop = {
                            when (val result = controller.stopRecording()) {
                                is HoldToRecordController.Success -> {
                                    viewModel.onRecordReleased(result.audioPath)
                                }

                                HoldToRecordController.Failed -> {
                                    viewModel.onRecordCanceled()
                                }
                            }
                        },
                        onPressCancel = {
                            controller.cancelRecording()
                            viewModel.onRecordCanceled()
                        },
                    )
                }
            },
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "按住底部按钮录音，松手后会立刻生成一张新卡片。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        uiState.transientMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                val cards = uiState.cards
                if (cards.isEmpty()) {
                    item {
                        EmptyStateCard()
                    }
                } else {
                    items(
                        items = cards,
                        key = { card -> card.id },
                    ) { card ->
                        NoteCardItem(
                            card = card,
                            onCardEdited = { cardId, sentences, copyText ->
                                viewModel.onCardEdited(
                                    cardId = cardId,
                                    sentences = sentences,
                                    copyText = copyText,
                                )
                            },
                            onRetryClick = { cardId ->
                                viewModel.onRetryFailedCard(cardId)
                            },
                            onCopyClick = {
                                val copyText = card.copyText ?: return@NoteCardItem
                                clipboardManager.setText(AnnotatedString(copyText))
                            },
                        )
                    }
                }
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("清空全部记录") },
            text = { Text("这会删除当前设备里的所有卡片和本地录音文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAllDialog = false
                        viewModel.onClearAllRecords()
                    },
                ) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

private fun checkRecordAudioPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun EmptyStateCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "还没有录音卡片",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "按住下方按钮开始第一条录音。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HoldToRecordButton(
    uiState: HomeUiState,
    hasRecordAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPressStart: () -> Unit,
    onPressStop: () -> Unit,
    onPressCancel: () -> Unit,
) {
    val buttonColor = if (uiState.isRecording) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    Surface(
        modifier = Modifier
        .fillMaxWidth()
        .height(96.dp)
            .pointerInput(hasRecordAudioPermission) {
                detectTapGestures(
                    onPress = {
                        if (!hasRecordAudioPermission) {
                            onRequestPermission()
                            return@detectTapGestures
                        }

                        onPressStart()
                        val released = tryAwaitRelease()
                        if (released) {
                            onPressStop()
                        } else {
                            onPressCancel()
                        }
                    },
                )
            }
            .semantics {
                contentDescription = if (uiState.isRecording) "松开结束录音" else "按住录音"
            },
        shape = RoundedCornerShape(32.dp),
        color = buttonColor,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isRecording) {
                RecordingMeter(
                    levels = uiState.recordingMeterLevels,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (uiState.showRecordingQuietHint) {
                    Text(
                        text = "没有检测到明显声音",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            Text(
                text = if (uiState.isRecording) "正在录音，松开结束" else "按住录音",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = if (hasRecordAudioPermission) {
                    "松开后会立刻生成独立卡片"
                } else {
                    "首次使用需要麦克风权限"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun RecordingMeter(
    levels: List<Float>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        levels.forEach { level ->
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height((12 + (level * 28f)).dp)
                    .background(
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                            alpha = 0.35f + (level * 0.6f)
                        ),
                        shape = RoundedCornerShape(999.dp),
                    ),
            )
        }
    }
}
