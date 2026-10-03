# 公开开发候选，不是正式题库

world-foundations-r1.json有20道配对题，即中文20条、英文20条。覆盖太空、海洋、国际单位制和八处世界遗产。2026-10-03依据各条NASA、NOAA、BIPM、UNESCO官方链接核对了所用基础事实；题目/选项/简短解释是本项目原创措辞，没有复制来源图片、标志、长文或音频。

答案在公开仓库中可见。它们只用于开发和审阅，不能当作保密正式竞赛题池。真实运营内容应经受控流程存储，不把受保密要求的题包提交到此公开repo。不存在自动审批、游客身份、自动灌库或演示题回退。

B14已由assistant逐条完成事实、题意唯一性与中英表达审校，附官方来源、具体审校结论及原题字节hash，见reviews/world-foundations-r1.assistant.json。原始候选文件保持不变，其pending-human是B11历史元数据，已由该新记录取代文本审校状态；没有伪称人类批准。文字措辞检查只说明原创基础事实题未引用来源媒体/长文，不构成法律意见。部署发布及实际听音尚未完成。

## 工具

```sh
python3 问答竞猜/tools/content_candidates.py 问答竞猜/content/candidates/world-foundations-r1.json
python3 问答竞猜/tools/content_candidates.py 问答竞猜/content/candidates/world-foundations-r1.json --output /chosen/new/review-directory --created-at-ms <明确时间戳>
```

验证20配对、严格字段/类型、选项唯一、两语言答案索引一致、官方HTTPS来源。导出两个schema2草稿文档及SQL预览。reviewedBy始终null，reviewedAtMillis始终0；即使错误改表状态，这些草稿也不符合运行加载器的审核约束。SQL只INSERT draft，不覆盖已有记录、不写audit，并以ROLLBACK结束。工具没有数据库连接代码，也没有批准或COMMIT开关。

## 英文音频候选

content/audio-candidates/world-foundations-en-r1包含20条离线Flite SLT合成英文预览，每条配原题和A–D选项文本、文本hash、音频hash、时长、峰值/RMS和来源候选hash。只读问题及所有选项，不读解释或指出正确答案。

实际格式为16kHz单声道FLAC，总音频3,416,970字节；时长7.9–12.1秒。检查通过字节/hash、解码、声道/采样率、有限时长、非静音和削波上限，以及题目15秒读题窗口能覆盖音频加1秒余量。以上不是实际试听，listened=false、approved=false；普通合成声音不代表最终电视竞猜主持效果。

未生成中文音频，manifest明确列出zh-CN缺口，没有用英文模型硬读中文冒充完成。B14已建立显式审核的本地WAV旁白绑定/授权读取/预加载播放合同，见../docs/NARRATION.md；这些未试听FLAC候选不会自动转成正式音频。B12/B13已有私有ASR候选与前端录音确认链，实际设备及中文质量仍待验收。

```sh
# 验证已提交音频；只需FFmpeg/FFprobe，不重新合成
python3 问答竞猜/tools/audio_candidates.py 问答竞猜/content/candidates/world-foundations-r1.json 问答竞猜/content/audio-candidates/world-foundations-en-r1 --verify
# 重生新的候选目录；要求FFmpeg安装包含flite过滤器及SLT内置声音
python3 问答竞猜/tools/audio_candidates.py 问答竞猜/content/candidates/world-foundations-r1.json /chosen/new/audio-directory
```

生成只在本机处理原创文本，不上传文字或声音。不会覆盖旧目录。输出绑定当次工具版本；没有声称不同FFmpeg/Flite版本的波形逐字节相同。最终部署前必须审阅声音/语言许可、实际听感、词名发音、选项完整性、设备音量和中断恢复，再通过专门审批发布。

工具单元测试含mock音频测量，用来验证审批/路径/文本/hash/窗口拒绝边界；content-contract CI另用FFmpeg真实解码全部20个已提交文件，不能混称。

## 已审校文本导出

`python3 tools/content_review.py content/candidates/world-foundations-r1.json content/reviews/world-foundations-r1.assistant.json --output /new/reviewed-text-directory`（项目目录执行）。验证全部20条hash与来源后，生成带真实assistant审校标识的schema2文本。无DB连接/自动approved/audit写；公开题与固定答案布局仅用于开发，不能作为保密竞赛内容。

## B15中文单题pilot

`audio-candidates/world-foundations-zh-pilot`只包含world-001的一条本地MeloTTS中文候选与真实运行收据，7.593秒、规范mono 16k PCM16 WAV；未试听/未批准，不能直接投入正式旁白。安装来源、固定许可声明、2线程/3GiB停止阈值和原生网络观测限制见[中文候选说明](../tts-candidates/README.md)。英文旧manifest的zh-CN缺失指它自己的音频包；中文完整20题仍未生成。
