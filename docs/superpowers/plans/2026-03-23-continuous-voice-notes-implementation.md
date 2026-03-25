# 连续语音笔记 App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个 Android 连续语音笔记应用和最小云端处理后端，支持连续录音、独立卡片产出、ASR 误识别词语修正，以及后续切换到更便宜模型 API。

**Architecture:** 仓库拆成 `android/` 与 `backend/` 两个子项目。Android 端负责录音、上传队列、本地历史、卡片流和词语替换；后端负责音频接收、任务管理、ASR、轻中度清洗、切句和基于 ASR 误识别的替换推荐。ASR 与清洗都通过 provider 抽象层接入，默认先接一家可用服务，但实现时不得把供应商写死。

**Tech Stack:** Android Kotlin + Jetpack Compose + Room + WorkManager + Retrofit/OkHttp + JUnit；Backend Python 3.11 + FastAPI + Pydantic + pytest + provider abstraction；本地开发用 SQLite 和文件存储。

---

## 文件结构

### Android 端

- `android/settings.gradle.kts`
- `android/build.gradle.kts`
- `android/app/build.gradle.kts`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/example/voicenotes/MainActivity.kt`
- `android/app/src/main/java/com/example/voicenotes/VoiceNotesApplication.kt`
- `android/app/src/main/java/com/example/voicenotes/app/AppContainer.kt`
- `android/app/src/main/java/com/example/voicenotes/audio/HoldToRecordController.kt`
- `android/app/src/main/java/com/example/voicenotes/data/local/AppDatabase.kt`
- `android/app/src/main/java/com/example/voicenotes/data/local/NoteCardEntity.kt`
- `android/app/src/main/java/com/example/voicenotes/data/local/NoteCardDao.kt`
- `android/app/src/main/java/com/example/voicenotes/data/model/NoteCard.kt`
- `android/app/src/main/java/com/example/voicenotes/data/model/SentenceSlice.kt`
- `android/app/src/main/java/com/example/voicenotes/data/model/TokenPhrase.kt`
- `android/app/src/main/java/com/example/voicenotes/data/model/ReplacementSuggestion.kt`
- `android/app/src/main/java/com/example/voicenotes/data/remote/ApiModels.kt`
- `android/app/src/main/java/com/example/voicenotes/data/remote/VoiceNotesApi.kt`
- `android/app/src/main/java/com/example/voicenotes/data/repo/NoteCardsRepository.kt`
- `android/app/src/main/java/com/example/voicenotes/data/repo/UploadQueueRepository.kt`
- `android/app/src/main/java/com/example/voicenotes/work/UploadNoteWorker.kt`
- `android/app/src/main/java/com/example/voicenotes/ui/home/HomeViewModel.kt`
- `android/app/src/main/java/com/example/voicenotes/ui/home/HomeScreen.kt`
- `android/app/src/main/java/com/example/voicenotes/ui/home/NoteCardItem.kt`
- `android/app/src/main/java/com/example/voicenotes/ui/home/TokenReplacementSheet.kt`
- `android/app/src/main/java/com/example/voicenotes/util/ClipboardHelper.kt`
- `android/app/src/test/java/com/example/voicenotes/data/repo/NoteCardsRepositoryTest.kt`
- `android/app/src/test/java/com/example/voicenotes/ui/home/HomeViewModelTest.kt`
- `android/app/src/test/java/com/example/voicenotes/ui/home/TokenReplacementSheetTest.kt`

### 后端

- `backend/pyproject.toml`
- `backend/app/main.py`
- `backend/app/config.py`
- `backend/app/api/routes/health.py`
- `backend/app/api/routes/notes.py`
- `backend/app/schemas/note_jobs.py`
- `backend/app/services/note_job_service.py`
- `backend/app/services/audio_storage.py`
- `backend/app/services/text_segmentation.py`
- `backend/app/services/suggestion_builder.py`
- `backend/app/providers/asr/base.py`
- `backend/app/providers/asr/openai_provider.py`
- `backend/app/providers/cleanup/base.py`
- `backend/app/providers/cleanup/openai_provider.py`
- `backend/app/providers/factory.py`
- `backend/app/store/models.py`
- `backend/app/store/repository.py`
- `backend/app/tests/test_health.py`
- `backend/app/tests/test_note_job_api.py`
- `backend/app/tests/test_note_job_service.py`
- `backend/app/tests/test_suggestion_builder.py`

### 文档

- `docs/superpowers/specs/2026-03-23-continuous-voice-notes-design.md`
- `docs/superpowers/plans/2026-03-23-continuous-voice-notes-implementation.md`
- `README.md`

## 实现约束

1. ASR provider 与清洗 provider 必须通过接口抽象和配置选择，不允许把供应商名散落在业务代码里。
2. 默认实现可以先接一家可用 API，但切换到更便宜模型时，不应影响 Android 端契约。
3. 第一版不做账号体系、同步、实时流式字幕、多卡合并复制。
4. 所有 TDD 步骤都要先写失败测试，再写最小实现，再跑通测试。
5. 每个任务结束都要提交一次 git commit。

## 任务拆分

### Task 1: 初始化仓库结构与后端/客户端骨架

**Files:**
- Create: `README.md`
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/com/example/voicenotes/MainActivity.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/VoiceNotesApplication.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/app/AppContainer.kt`
- Create: `backend/pyproject.toml`
- Create: `backend/app/main.py`
- Create: `backend/app/api/routes/health.py`
- Test: `backend/app/tests/test_health.py`

- [ ] **Step 1: 写后端健康检查失败测试**

```python
from fastapi.testclient import TestClient

from app.main import app


def test_health_endpoint_returns_ok():
    client = TestClient(app)

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd backend && pytest app/tests/test_health.py -v`
Expected: FAIL，提示 `ModuleNotFoundError` 或 `app.main`/路由不存在

- [ ] **Step 3: 实现最小后端骨架和健康检查**

```python
# backend/app/main.py
from fastapi import FastAPI

from app.api.routes.health import router as health_router

app = FastAPI()
app.include_router(health_router)
```

```python
# backend/app/api/routes/health.py
from fastapi import APIRouter

router = APIRouter()


@router.get("/health")
def health():
    return {"status": "ok"}
```

- [ ] **Step 4: 再次运行测试，确认通过**

Run: `cd backend && pytest app/tests/test_health.py -v`
Expected: PASS

- [ ] **Step 5: 搭 Android 最小启动骨架**

实现一个能启动 Compose 空白页面的最小 App，至少包含：

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text("Voice Notes")
        }
    }
}
```

- [ ] **Step 6: 验证 Android 工程可编译**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add README.md android backend
git commit -m "chore: scaffold android and backend projects"
```

### Task 2: 建立后端任务模型与可替换 provider 抽象

**Files:**
- Create: `backend/app/config.py`
- Create: `backend/app/schemas/note_jobs.py`
- Create: `backend/app/store/models.py`
- Create: `backend/app/store/repository.py`
- Create: `backend/app/providers/asr/base.py`
- Create: `backend/app/providers/asr/openai_provider.py`
- Create: `backend/app/providers/cleanup/base.py`
- Create: `backend/app/providers/cleanup/openai_provider.py`
- Create: `backend/app/providers/factory.py`
- Test: `backend/app/tests/test_note_job_service.py`

- [ ] **Step 1: 写 provider 工厂失败测试**

```python
from app.providers.factory import build_provider_registry


def test_provider_registry_exposes_asr_and_cleanup_keys():
    registry = build_provider_registry()

    assert "asr" in registry
    assert "cleanup" in registry
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd backend && pytest app/tests/test_note_job_service.py -v`
Expected: FAIL，提示 `build_provider_registry` 未定义

- [ ] **Step 3: 定义抽象接口与默认 provider**

```python
# backend/app/providers/asr/base.py
from abc import ABC, abstractmethod


class AsrProvider(ABC):
    @abstractmethod
    async def transcribe(self, audio_path: str) -> str:
        raise NotImplementedError
```

```python
# backend/app/providers/cleanup/base.py
from abc import ABC, abstractmethod


class CleanupProvider(ABC):
    @abstractmethod
    async def clean(self, transcript: str) -> str:
        raise NotImplementedError
```

```python
# backend/app/providers/factory.py
def build_provider_registry():
    return {
        "asr": "configurable",
        "cleanup": "configurable",
    }
```

- [ ] **Step 4: 增加配置对象，支持按环境变量切换 provider**

要求至少支持：

- `ASR_PROVIDER`
- `CLEANUP_PROVIDER`
- `OPENAI_API_KEY`

并明确：以后切换到更便宜模型时，优先通过新增 provider 实现和配置切换完成，而不是改业务流程。

- [ ] **Step 5: 再次运行测试，确认通过**

Run: `cd backend && pytest app/tests/test_note_job_service.py -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/app/config.py backend/app/providers backend/app/tests/test_note_job_service.py
git commit -m "feat: add configurable model provider abstractions"
```

### Task 3: 完成后端笔记任务 API 与处理服务

**Files:**
- Create: `backend/app/api/routes/notes.py`
- Create: `backend/app/services/note_job_service.py`
- Create: `backend/app/services/audio_storage.py`
- Create: `backend/app/services/text_segmentation.py`
- Create: `backend/app/services/suggestion_builder.py`
- Modify: `backend/app/main.py`
- Test: `backend/app/tests/test_note_job_api.py`
- Test: `backend/app/tests/test_suggestion_builder.py`

- [ ] **Step 1: 写上传任务 API 失败测试**

```python
from fastapi.testclient import TestClient

from app.main import app


def test_create_note_job_returns_processing_status():
    client = TestClient(app)

    response = client.post(
        "/api/note-jobs",
        files={"audio": ("note.m4a", b"fake-audio", "audio/m4a")},
    )

    assert response.status_code == 202
    body = response.json()
    assert body["status"] == "processing"
    assert "jobId" in body
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd backend && pytest app/tests/test_note_job_api.py::test_create_note_job_returns_processing_status -v`
Expected: FAIL，提示路由不存在

- [ ] **Step 3: 实现任务创建与结果查询 API**

至少支持：

- `POST /api/note-jobs`
- `GET /api/note-jobs/{job_id}`

结果结构必须覆盖：

- `status`
- `rawTranscript`
- `cleanText`
- `sentences`
- `tokens`
- `suggestions`

- [ ] **Step 4: 写推荐构建失败测试**

```python
from app.services.suggestion_builder import build_token_suggestions


def test_build_token_suggestions_marks_complete_phrase():
    result = build_token_suggestions(
        sentence="我们准备接入 Web coding 做原型。",
        suspicious_phrases=["Web coding"],
    )

    assert result[0]["text"] == "Web coding"
    assert result[0]["isAsrSuspicious"] is True
```

- [ ] **Step 5: 实现切句和词语/短语建议构建器**

要求：

- 切句结果用于 UI 陈列
- 可点击单位必须是完整词语或短语
- 推荐面向 ASR 误识别，不做整句改写建议

- [ ] **Step 6: 跑后端全部测试**

Run: `cd backend && pytest app/tests -v`
Expected: PASS

- [ ] **Step 7: 手动验证健康检查和任务接口**

Run: `cd backend && uvicorn app.main:app --reload`
Expected: 访问 `/health` 返回 `{"status":"ok"}`，上传接口可返回 `202`

- [ ] **Step 8: Commit**

```bash
git add backend/app
git commit -m "feat: add note job api and processing pipeline"
```

### Task 4: 实现 Android 本地数据层、卡片流与上传队列

**Files:**
- Create: `android/app/src/main/java/com/example/voicenotes/data/local/AppDatabase.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/data/local/NoteCardEntity.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/data/local/NoteCardDao.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/data/model/NoteCard.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/data/model/SentenceSlice.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/data/model/TokenPhrase.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/data/model/ReplacementSuggestion.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/data/remote/ApiModels.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/data/remote/VoiceNotesApi.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/data/repo/NoteCardsRepository.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/data/repo/UploadQueueRepository.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/work/UploadNoteWorker.kt`
- Test: `android/app/src/test/java/com/example/voicenotes/data/repo/NoteCardsRepositoryTest.kt`

- [ ] **Step 1: 写仓库失败测试，覆盖“松手即生成处理中卡片”**

```kotlin
@Test
fun createPendingCard_marksCardProcessingImmediately() = runTest {
    val repository = buildRepository()

    val card = repository.createPendingCard(audioPath = "/tmp/a.m4a")

    assertEquals(NoteStatus.PROCESSING, card.status)
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*NoteCardsRepositoryTest"`
Expected: FAIL，提示仓库或状态模型不存在

- [ ] **Step 3: 实现 Room 实体、DAO、仓库与上传队列**

关键点：

- `createPendingCard()` 立即落本地库
- `UploadNoteWorker` 负责上传与轮询任务状态
- 一个卡片失败不能阻塞其他卡片排队

- [ ] **Step 4: 再加一个失败测试，覆盖“处理完成后更新卡片内容”**

```kotlin
@Test
fun applyCompletedResult_updatesCleanTextAndSentences() = runTest {
    val repository = buildRepository()
    val card = repository.createPendingCard("/tmp/a.m4a")

    repository.applyCompletedResult(
        card.id,
        cleanText = "我们准备接入 Vibe Coding 做原型。",
        sentences = listOf("我们准备接入 Vibe Coding 做原型。"),
    )

    val updated = repository.observeCards().first().first()
    assertEquals("completed", updated.status.value)
    assertEquals(1, updated.sentences.size)
}
```

- [ ] **Step 5: 跑 Android 单元测试**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*NoteCardsRepositoryTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/example/voicenotes/data android/app/src/main/java/com/example/voicenotes/work android/app/src/test/java/com/example/voicenotes/data/repo/NoteCardsRepositoryTest.kt
git commit -m "feat: add local note storage and upload queue"
```

### Task 5: 实现按住录音主页、连续录音与卡片 UI

**Files:**
- Create: `android/app/src/main/java/com/example/voicenotes/audio/HoldToRecordController.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/ui/home/HomeViewModel.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/ui/home/HomeScreen.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/ui/home/NoteCardItem.kt`
- Modify: `android/app/src/main/java/com/example/voicenotes/MainActivity.kt`
- Test: `android/app/src/test/java/com/example/voicenotes/ui/home/HomeViewModelTest.kt`

- [ ] **Step 1: 写 ViewModel 失败测试，覆盖“上一条处理中时还能继续开始下一条”**

```kotlin
@Test
fun releaseRecording_keepsRecorderReadyForNextNote() = runTest {
    val viewModel = buildViewModel()

    viewModel.onRecordPressed()
    viewModel.onRecordReleased("/tmp/a.m4a")

    assertTrue(viewModel.uiState.value.canRecordNext)
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*HomeViewModelTest"`
Expected: FAIL

- [ ] **Step 3: 实现录音控制器与主页 UI**

UI 必须包含：

- 一个明显的按住录音按钮
- 处理中/已完成/失败三种卡片态
- 笔记卡片按时间倒序展示
- 复制按钮位于单卡上

- [ ] **Step 4: 手动装机验证连续录音**

Run: `cd android && ./gradlew :app:installDebug`
Expected: 真机或模拟器可连续多次按住录音，松手后列表出现多张独立卡片

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/example/voicenotes/audio android/app/src/main/java/com/example/voicenotes/ui/home android/app/src/main/java/com/example/voicenotes/MainActivity.kt android/app/src/test/java/com/example/voicenotes/ui/home/HomeViewModelTest.kt
git commit -m "feat: add continuous recording home screen"
```

### Task 6: 实现词语点击替换、推荐词与复制逻辑

**Files:**
- Create: `android/app/src/main/java/com/example/voicenotes/ui/home/TokenReplacementSheet.kt`
- Create: `android/app/src/main/java/com/example/voicenotes/util/ClipboardHelper.kt`
- Modify: `android/app/src/main/java/com/example/voicenotes/ui/home/NoteCardItem.kt`
- Modify: `android/app/src/main/java/com/example/voicenotes/ui/home/HomeViewModel.kt`
- Test: `android/app/src/test/java/com/example/voicenotes/ui/home/TokenReplacementSheetTest.kt`

- [ ] **Step 1: 写替换面板失败测试**

```kotlin
@Test
fun clickingAnyToken_opensReplacementOptions() = runTest {
    val state = buildCompletedCardState()

    val selected = state.sentences.first().tokens.first()

    assertNotNull(selected)
    assertTrue(selected.isClickable)
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*TokenReplacementSheetTest"`
Expected: FAIL

- [ ] **Step 3: 实现词语点击与替换面板**

要求：

- 所有词语都可点
- 可疑词可以额外高亮，但不能影响其他词可点
- 推荐词点击后立即替换
- 手动输入后保存也立即替换
- 替换单位必须是完整词语/短语

- [ ] **Step 4: 实现卡片复制逻辑**

复制内容必须是当前已修正后的整张卡片文本，而不是原始转写。

- [ ] **Step 5: 跑相关 Android 单测**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*TokenReplacementSheetTest" --tests "*HomeViewModelTest"`
Expected: PASS

- [ ] **Step 6: 手动验证三条关键路径**

1. 点任意词都能弹出替换面板
2. 点推荐词可立即替换
3. 复制得到的是最新修正后的卡片文本

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/example/voicenotes/ui/home android/app/src/main/java/com/example/voicenotes/util android/app/src/test/java/com/example/voicenotes/ui/home/TokenReplacementSheetTest.kt
git commit -m "feat: add token replacement and card copy flow"
```

### Task 7: 端到端联调、文档补充与发布前检查

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-23-continuous-voice-notes-design.md`
- Test: `backend/app/tests/test_note_job_api.py`
- Test: `android/app/src/test/java/com/example/voicenotes/data/repo/NoteCardsRepositoryTest.kt`
- Test: `android/app/src/test/java/com/example/voicenotes/ui/home/HomeViewModelTest.kt`
- Test: `android/app/src/test/java/com/example/voicenotes/ui/home/TokenReplacementSheetTest.kt`

- [ ] **Step 1: 跑后端测试全套**

Run: `cd backend && pytest app/tests -v`
Expected: PASS

- [ ] **Step 2: 跑 Android 单元测试全套**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL 且测试通过

- [ ] **Step 3: 做一次手工联调清单**

至少覆盖：

1. 连续录三条语音，确认产生三张卡片
2. 第一张处理中时，第二张和第三张仍可继续录
3. 一张卡片失败后可单独重试
4. 任意词语可点，推荐和手动替换都生效
5. 复制输出为当前修正后的整卡文本
6. provider 配置改动后，后端仍能在不改业务代码情况下切换模型实现

- [ ] **Step 4: 更新 README**

README 至少写清楚：

- Android 和 backend 的启动方式
- 需要的环境变量
- 如何切换 ASR provider
- 如何切换清洗 provider
- 哪些能力是第一版范围外

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-03-23-continuous-voice-notes-design.md
git commit -m "docs: add setup and verification notes"
```

## 本地审查清单

在真正执行前，先逐项确认：

1. Android 工程是否优先选 Kotlin + Compose，不引入不必要 DI 框架
2. 后端 provider 抽象是否足够薄，切换更便宜模型只需新增实现和改配置
3. 结果 schema 是否能同时满足“切句展示”和“词语点击替换”
4. Room 表结构是否能保存用户本地修正结果
5. WorkManager 队列是否能确保一张失败不会卡死后续卡片

## 执行顺序建议

1. 先完成 Task 1-3，把后端契约与 provider 抽象稳定下来
2. 再做 Task 4-6，把 Android 本地队列、录音 UI 和修正交互串起来
3. 最后做 Task 7 的联调和文档

## 风险提醒

- 录音权限、后台队列和文件路径会是 Android 端最容易踩坑的地方
- ASR 推荐词的质量不稳定，所以手动替换必须从第一版就具备
- 如果 provider 抽象偷懒，后面切更便宜 API 会变成一次大改
- 如果后端返回的 token/phrase 粒度不对，前端“整词可点”的体验会直接受损

## 完成定义

满足以下条件才算第一版完成：

1. 用户可连续录多条语音，生成多张独立卡片
2. 后端返回清洗文本、切句结果、词语或短语推荐
3. 所有词语都可点击替换
4. 推荐替换与手动替换都能即时生效
5. 每张卡片都可单独复制
6. 模型供应商切换通过 provider 配置完成，而不是重写主流程
