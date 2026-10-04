# 本机语音候选处理器

这是可选处理器，Java默认关闭ASR，原身份仍未接入。固定合成英文句的功能链已验证；B22 的两条中文句在 tiny 上均未形成正确候选，B23 small 的独立评估见文末。真实麦克风、噪声环境与生产仍未验收，也未以完整网络观测证明零外联。不要将模型本地存放等同于完成所有隐私验收。

## 依赖与本地模型准备

要求Python3.12、CPU推理，使用一个全新的专用venv：

```sh
python3 -I speech-worker/install_runtime.py /chosen/new/venv
/chosen/new/venv/bin/python -I speech-worker/prepare_model.py /chosen/new/model-directory
```

安装器只从requirements.lock明确版本列表安装，强制--no-deps，不修改已有环境。故意不安装ONNX Runtime，且worker在导入任何模型运行库前发现它存在就拒绝启动；本代码使用非批处理WhisperModel，固定vad_filter=False，依靠PCM静音门槛而不是ONNX VAD。不要改回普通pip依赖自动解析；上游包元数据列出的ONNX VAD依赖在此受限路径刻意不可用。更换模型/启用VAD需重新审阅和测试。

prepare_model.py是独立、明确执行的联网准备步骤，只下载model-source.json指定仓库/提交的四个公共数据文件，并逐项核对SHA256；不使用HF账号token、不发送录音。worker不会调用该准备程序。模型不随源码或JAR打包，也不自动下载。已有目录必须完全通过已固定清单验证，不会用未知内容替换它。

## 显式运行

```sh
/chosen/new/venv/bin/python -I speech-worker/private_asr.py --model-dir /chosen/verified/model-directory --port 9609
```

只绑定字面127.0.0.1，没有外网绑定选项。模型导入前设置HF离线/禁用遥测环境，并使用local_files_only=True。关闭API通常发生在模块导入之后，不能当作初始化之前已关闭行为的证明，所以这里直接排除不需要的ONNX依赖。仍需部署方的网络出口观测/隔离与依赖审阅，不能仅凭环境变量宣称无外联。

单请求处理、CPU线程2、int8、默认模型文件上限100MiB、解码最多48新token；默认只接受固定hash的Tiny模型。显式 small-2ec96c54 档案仅对已批准 model.bin 允许精确483,546,902字节，并对四文件逐一校验精确大小和SHA256；其他文件与tiny上限不变。仅接受0.1–6秒、16kHz、单声道PCM16、44字节固定头WAV，最大192044字节；不调用压缩媒体解码器。不保存录音文件，不记录转写/请求头。内存缓冲会随请求结束释放；宿主调试/转储等另受部署策略管理。

协议：POST /internal/quiz/asr，Content-Type: audio/wav，X-Quiz-Language: en或zh-CN，返回有限JSON文本。GET /health只表明此处理器已载入模型，不能代表原身份、题包或游戏已生产就绪。此端口绝不可由反向代理公开给浏览器。

Java端需同时配置QUIZ_ASR_ENABLED=true及QUIZ_ASR_URL=http://127.0.0.1:9609/internal/quiz/asr。只允许这类字面loopback地址、明确端口和固定路径；禁止DNS别名、代理、重定向、URL账号和任意远程服务。Java只向处理器发送音频和语言，不发送Cookie、用户/site身份或正确答案。

## 测试与边界

- test_private_asr.py用标准库HTTP和替身识别器验证协议/拒绝边界；不会导入模型，不冒充识别准确率
- SpeechRulesTest/LoopbackTranscriberTest验证PCM、保守选项匹配、有限响应/总超时、两请求容量和禁止重定向
- GameHttpFlowTest用明确test profile身份验证候选不计分、必须单独答题确认、跨scope拒绝和识别期间过期/撤权
- 本批一次真实本地合成测试使用独立无ONNX环境、固定Tiny模型与“My answer is option C.”：游戏HTTP→处理器→候选C，确认前0分、单独确认后100分。它不证明其他句型、语言或设备质量
- RealSpeechHttpIntegrationTest默认跳过；只有明确QUIZ_REAL_ASR_INTEGRATION=true、合成QUIZ_REAL_ASR_WAV路径及loopback29609测试worker才运行。当前CI不自动启动真实模型；不要把这个默认跳过项算通过

引用与模型来源：[faster-whisper](https://github.com/SYSTRAN/faster-whisper)、[固定模型版本](https://huggingface.co/Systran/faster-whisper-tiny/tree/d90ca5fe260221311c53c58e660288d3deb8d356)。部署前须审阅相应软件和模型许可。当前没有声纹识别、说话人身份判断、声音克隆或自动作答。

## 固定 small 评估档案（显式选择，不是默认升级）

`model-source-small.json` 固定官方仓库 `Systran/faster-whisper-small`、提交 `2ec96c5472da50d38d40c0cfe0602af2e94b4c8a`。四文件共486,212,372字节；二进制SHA与官方LFS一致，其余文件下载后验证官方Git blob SHA，再记录SHA256。读取的元数据在 `model-source-small-origin.json`。权重不提交Git或装入发布包。

明确准备（联网只下载这四个公开文件，不发送音频），之后运行必须使用同一个显式档案：

```sh
HF_HOME="$WORK/hf-cache" "$ASR_PYTHON" -I speech-worker/prepare_model.py "$WORK/small-2ec96c54" --model-profile small-2ec96c54
"$ASR_PYTHON" -I speech-worker/private_asr.py --model-dir "$WORK/small-2ec96c54" --model-profile small-2ec96c54 --port 9609
```

不带 `--model-profile` 仍是 tiny，给 tiny 传 small 目录会失败；不支持任意目录模型、自选 revision、扩大通用限额或自动升级。

显式本机评估器 `run_native_evaluation.py --help` 只启动一个私有 worker，串行运行实际中文HTTP、英文对照和小规模队列用例。参数是既有无ONNX解释器、本地模型、Maven/本地缓存、固定英文测试WAV及全新输出目录的绝对路径。不会下载模型/安装依赖/录音；不自动进入CI。评估器每40ms采样worker RSS，超过3GiB终止并记失败，240秒总时限；这是评估保护和采样值，不是生产进程硬内存配额或完整原生网络观测。

Java真实4秒传输时限和每个客户端容量2保持不变。测量包括串行两条、同客户端同时2/4条及两个客户端共4条共享单ASR进程；记录拒绝和超时，不能将一次观测外推为商用并发容量。完整原音频与准确率/确认/资源结果见 `docs/SMALL-ASR-EVALUATION.md`。

许可来源：[固定转换制品](https://huggingface.co/Systran/faster-whisper-small/tree/2ec96c5472da50d38d40c0cfe0602af2e94b4c8a) 模型卡标MIT；[原始模型卡](https://huggingface.co/openai/whisper-small) 元数据为Apache2。保留来源及各制品适用声明，不把其中一个标签当全部上游权利保证。
