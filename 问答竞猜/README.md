# 环球挑战 · Quiz Challenge

这是从2026-10-03开始的重新实现，不是丢失版本的恢复。历史测试结果不作为此项目的测试成绩。

原创电视竞猜舞台：Vue3 JavaScript / GSAP / Canvas；独立Spring Boot Java21后端，MySQL权威状态，Redis仅限流与缓存。与AI中台共用明确选择的逻辑数据库，业务只使用quiz_*表，不接团队、不复制身份账号。

## 目录
- frontend：可玩本地体验与正式API前端
- backend：独立服务端与fail-closed身份边界
- deploy：操作与共库迁移契约
- docs：设计、验收与每批进展

## 运行
前端：`cd frontend && npm ci && npm run dev`
检查：`npm run verify`
后端：完整JDK21/Maven，`cd backend && mvn test`

当前有两个明确分开的入口：
- `index.html`：可玩本地演示，5题闯关、双语、50:50、键盘、原创音效/BGM、减少动画和模拟语音候选确认；不保存真实成绩
- `server.html`：正式API客户端，只展示服务器快照/计时/成绩；同源现有登录、审核题包、共享数据库及分布式准入就绪后才能开局。当前真实身份尚未接通；Redis准入已有显式启用实现、默认关闭，缺失或故障时后端拒绝开局

B13当次前端37条Node+51条DOM/生命周期测试通过、双入口构建通过；可选语音仅在用户同意且服务明确配置后录音，识别结果仍需再次确认。源码客户端的WAV编码/候选流程和5题750分链路经过实际测试HTTP；真实浏览器和麦克风仍未验收。测试身份仅在后端测试源码，生产包不含伪登录。真实浏览器/设备视觉与听感尚未验收，不能宣称商用上线。原token验证/失效规则、物理DDL、身份权限仍待实际系统提供。公开演示题不得当正式审核题包；正式内容与音频需要事实、翻译、许可及实际试听审查。详见docs/SERVER-FRONTEND.md和docs/REMAINING-LAUNCH-PATH.md。

B14：20对公开文本已完成有来源的assistant事实/双语审校；旁白可按已审核本地WAV精确绑定、当前用户/轮次授权读取并在server读题阶段播放，默认关闭。实际音频仍未试听，不随文字审校自动批准。详见[旁白与审校合同](docs/NARRATION.md)。

B15：提供固定官方MeloTTS/PyTorch中文本地候选工具及一条7.593秒pilot，实际资源受控合成已验证，未试听/未批准。仅CPU、无ONNX、运行时不自动下载；原生网络验收仍未完成。见[中文候选与限制](tts-candidates/README.md)。
