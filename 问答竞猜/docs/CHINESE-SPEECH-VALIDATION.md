# B22 · 中文答题真实识别与安全回退

本批没有通过中文自动候选验收。固定官方 tiny 模型对两条明确短句都识别错误；保守匹配器返回空候选，分数与会话版本保持不变。用户手动选择 C 并明确确认后才从 0 分变成 100 分。测试通过的是这条安全回退链，不是中文准确率。

| 固定合成文本 | 实际私有 ASR 文本 | 自动候选 |
| --- | --- | --- |
| 我选择第三项。 | 無選擇第三項 | 空 |
| 答案是三。 | 大十三 | 空 |

同一实际 worker 的英文对照输出 `My answer is option C.`，候选 C 正确，确认前 0 分、确认后 100 分。中文 2 条安全回归与英文 1 条对照分别运行实际 Spring HTTP → Java → literal-loopback Whisper，均零跳过。没有放宽匹配器、把否定/错误文本映射为答案、挑选更多短句或改用云识别。

## 证据与范围

- `LOCAL-CHINESE-SPEECH-SMOKE.json` 绑定准确源码、模型与音频 SHA-256，明确中文自动候选 0/2；B12 英文历史凭据保持原样
- 两个固定样本位于 `speech-worker/fixtures/`，16 kHz、单声道、PCM16，分别 1.614 和 1.289 秒，不剪裁，不使用用户录音、真人克隆或任意文本入口
- `tts-candidates/render_answer.py` 只接受两个内置 fixture 名；复用已审核拒网/无 ONNX/RSS 限制，固定本地模型、单线程 PyTorch，峰值 RSS 约 1.56 GiB。第三项样本重复合成字节一致；这不承诺跨环境一致
- Java 使用 256 MiB 堆/2 CPU，原私有 ASR 至多 2 CPU 线程。TTS 先结束，再运行 Java/ASR；最终所有进程退出
- 原模型、依赖锁、生产识别代码和保守选择匹配器保持不变。新增中文题包/身份/配额均只存在测试源码和 test profile
- 20 条标准库 TTS 检查验证两个新样本及旧 pilot 的来源、完整 WAV、资源/审批标志、固定输入与安全加载合同；CI 不安装模型或执行原生语音

最初专用测试只有英文测试题包，中文开局正确失败关闭；补足仅测试中文题包后才进入识别。另一次分离执行环境中的 loopback 连接失败，最终将 worker 与 Java 放在同一 shell/network namespace 并先检查 `/health`，使用原来同一个 127.0.0.1 端口，没有代理或对外地址。首次正向识别断言确实失败，随后保留两条误识别作为明确的安全负例，而非声称修复了模型准确率。

## 可重复运行

使用已按各 README 官方固定来源准备的本地环境和模型；这里的运行步骤不下载或安装任何依赖。先设 `TTS_PYTHON`、`TTS_MODELS`、`ASR_PYTHON`、`ASR_MODEL` 为对应隔离解释器和模型的绝对路径。ASR 环境必须是无 ONNX 的白名单环境。不要使用曾触发 ONNX 遥测的旧环境。

从项目目录，若要重新合成（输出目录必须不存在）：

```sh
"$TTS_PYTHON" tts-candidates/render_answer.py --models "$TTS_MODELS" --fixture third-option --output "$WORK/new-third-option"
"$TTS_PYTHON" tts-candidates/render_answer.py --models "$TTS_MODELS" --fixture answer-three --output "$WORK/new-answer-three"
```

测试默认读取仓库中已有的两个中文固定样本。模型与 Java 必须位于同一个网络命名空间；一个 shell 中启动、健康检查、运行、清理：

```sh
set -eu
"$ASR_PYTHON" speech-worker/private_asr.py --model-dir "$ASR_MODEL" --port 29609 &
asr_pid=$!
trap 'kill "$asr_pid" 2>/dev/null || true; wait "$asr_pid" 2>/dev/null || true' EXIT
python3 - <<'PY_HEALTH'
import http.client, time
for attempt in range(20):
    try:
        connection = http.client.HTTPConnection('127.0.0.1', 29609, timeout=.5)
        connection.request('GET', '/health')
        response = connection.getresponse()
        assert response.status == 200
        response.read()
        connection.close()
        break
    except (OSError, AssertionError):
        time.sleep(.25)
else:
    raise SystemExit('ASR loopback not ready')
PY_HEALTH
cd backend
QUIZ_REAL_ASR_INTEGRATION=true QUIZ_REAL_ASR_LOCALE=zh-CN \
MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' \
mvn -DforkCount=0 -Dtest=RealSpeechHttpIntegrationTest test
```

可设置 `QUIZ_REAL_ASR_RECEIPT` 为一个新的输出文件前缀，测试按 fixture 名输出 JSON，拒绝覆盖既有凭据。英文对照使用 `QUIZ_REAL_ASR_LOCALE=en` 和 `QUIZ_REAL_ASR_WAV` 指向自行合成的固定英文 C 答题 WAV；不会录音。普通 CI 不设置 `QUIZ_REAL_ASR_INTEGRATION`，实际模型用例仍明确跳过。

## 未完成

当前 tiny 中文模型没有达到本次两句样本的可用要求，不能把安全测试绿色当作语音体验可用。下一步需要单独限定资源与许可的更合适本地中文识别方案评估，或维持文字/手动选择入口；不在本批下载新模型。真实麦克风、浏览器设备、人工听音和代表性口音/噪声评估仍未完成。

运行中原生 UCX 输出可选 VFS socket 创建被拒的日志，未修改系统权限或绕过。先前 strace 限制保持不变；Python 层 TTS 拒网与本地模型配置不等于原生库零外传证明。本批未进行完整网络观测，也没有真实用户音频。
