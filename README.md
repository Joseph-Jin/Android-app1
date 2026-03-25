# 连续语音笔记

这是一个第一版的连续语音笔记产品原型，包含：

- Android 客户端：按住录音、松手提交、连续生成多张卡片
- FastAPI 后端：接收音频、转写、清洗、切句、生成 ASR 误识别推荐

## 当前能力

- 连续录多条语音，每次松手生成一张独立卡片
- 前一条处理中时，下一条仍可继续录
- 卡片支持 `处理中 / 已完成 / 失败` 三种状态
- 已完成卡片支持句子内词语点击替换
- 推荐替换和手动输入替换都会立即生效
- 复制内容为当前修正后的整张卡片文本
- ASR provider 和清洗 provider 可独立配置，不写死在业务主流程里

## 环境要求

### Backend

- Python `>= 3.11`
- 建议用独立虚拟环境

### Android

- JDK `17`
- Android SDK / platform-tools
- 可用模拟器或真机

## Backend 启动

```bash
cd backend
python -m pip install -e .
uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

本地无模型 key 联调时，建议直接使用 `mock provider`：

```bash
cd backend
ASR_PROVIDER=mock CLEANUP_PROVIDER=mock uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

健康检查：

```bash
curl http://127.0.0.1:8000/health
```

预期返回：

```json
{"status":"ok"}
```

## Backend 环境变量

后端当前支持把 `ASR` 和“清洗/重写”两条能力拆开配置：

```bash
export VOICE_NOTES_DATA_DIR=./data/voice-notes

export ASR_PROVIDER=openai
export ASR_API_KEY=...
export ASR_BASE_URL=https://api.openai.com/v1
export ASR_MODEL=...

export CLEANUP_PROVIDER=openai
export CLEANUP_API_KEY=...
export CLEANUP_BASE_URL=https://api.openai.com/v1
export CLEANUP_MODEL=...
```

兼容别名：

- `OPENAI_API_KEY` 可作为 `ASR_API_KEY` / `CLEANUP_API_KEY` 的兜底
- `OPENAI_BASE_URL` 可作为 `ASR_BASE_URL` / `CLEANUP_BASE_URL` 的兜底
- `OPENAI_ASR_MODEL` 和 `OPENAI_CLEANUP_MODEL` 也可分别兜底

## Mock Provider 联调模式

如果你暂时没有真实模型 API key，可以直接使用：

```bash
export ASR_PROVIDER=mock
export CLEANUP_PROVIDER=mock
```

这会让后端返回固定的本地样例数据，不调用任何外部模型服务。当前 mock 样例会产出：

- 原始转写：`今天我们主要聊 web coding 和 open ai 稍后我会把 chat gpt 的提示词整理一下`
- 清洗结果：`今天我们主要聊 Web coding 和 open ai。稍后我会把 Chat GPT 的提示词整理一下。`
- 可点击词语：`Web coding`、`open ai`、`Chat GPT`
- 推荐替换：`Vibe Coding`、`OpenAI`、`ChatGPT`

## 如何切换更便宜的 ASR / 清洗模型

当前仓库内置了 `openai`、`openai_compatible` 和 `mock` 三种 provider 入口，但主流程已经抽象成可替换结构。

切换方式不是改业务逻辑，而是：

1. 在 `backend/app/providers/asr/` 或 `backend/app/providers/cleanup/` 下新增实现
2. 在 [`backend/app/providers/factory.py`](/Users/insta360/.config/superpowers/worktrees/Android%20app/codex-task7-verification-docs/backend/app/providers/factory.py) 注册新的 provider builder
3. 通过环境变量切换 `ASR_PROVIDER` / `CLEANUP_PROVIDER`

也就是说，后续你要换更便宜的 API，重点是“新增 provider + 改配置”，而不是重写上传、任务、轮询、卡片、替换这些业务流程。

如果你后面要先用 `OpenAI` 调试，再切到 `Qwen / MiniMax` 这类兼容 OpenAI 风格接口的服务，推荐直接用：

```bash
export ASR_PROVIDER=openai_compatible
export ASR_BASE_URL=https://your-asr-provider.example/v1
export ASR_API_KEY=...
export ASR_MODEL=...

export CLEANUP_PROVIDER=openai_compatible
export CLEANUP_BASE_URL=https://your-cleanup-provider.example/v1
export CLEANUP_API_KEY=...
export CLEANUP_MODEL=...
```

说明：

- `openai` 会继续保留，方便你当前直接用 OpenAI 调试
- `openai_compatible` 更适合后面切到 `Qwen / MiniMax` 之类供应商
- `ASR` 和 `cleanup` 仍然可以分别配置成不同 provider，不需要绑在同一家

## Android 启动

构建 Debug 包：

```bash
cd android
./gradlew :app:assembleDebug
```

安装到模拟器/真机：

```bash
cd android
./gradlew :app:installDebug
```

如果要指定后端地址：

```bash
cd android
./gradlew :app:assembleDebug -PVOICE_NOTES_API_BASE_URL=http://10.0.2.2:8000/
```

说明：

- 默认 `VOICE_NOTES_API_BASE_URL` 是 `http://10.0.2.2:8000/`
- 对 Android 模拟器来说，`10.0.2.2` 指向宿主机
- 如果你用真机，需要换成宿主机局域网地址

## 测试命令

### Backend

```bash
cd backend
pytest app/tests -v
```

### Android

```bash
cd android
./gradlew :app:testDebugUnitTest
```

## 2026-03-24 验证记录

已完成：

- `cd backend && pytest app/tests -v`
  - 结果：`25 passed`
- `cd android && ./gradlew :app:testDebugUnitTest`
  - 结果：`BUILD SUCCESSFUL`
- 本地启动后端并请求 `GET /health`
  - 结果：返回 `{"status":"ok"}`

受环境阻塞，未完成：

- `cd android && ./gradlew :app:installDebug`
  - 当前机器的 `adb` 启动失败，错误为 `could not install *smartsocket* listener: Address already in use`
- 因为没有稳定的模拟器/真机链路，本轮没有完成真实设备上的连续录音手工联调

## 第一版范围外

- iOS / Web 客户端
- 账号体系与跨设备同步
- 多卡合并复制
- 录音过程实时字幕
- 云端共享术语词库
- 协作编辑
- 超出当前处理必需范围的服务端长期内容托管

## 当前已知限制

- 用户正在按住录音时，如果发生旋转屏幕或 Activity 重建，这次录音会被取消
- 词语点击替换当前主要依赖后端返回的切句和 token 粒度，实际体验会受 provider 输出质量影响
- 手工联调还需要稳定的 ADB / 模拟器 / 真机环境补齐
