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

当前已完成新的可玩舞台：5题闯关、双语、50:50、键盘、原创音效/BGM、减少动画和模拟语音候选确认。新规则/DOM测试24项通过、构建通过；真实浏览器/设备视觉与听感尚未验收。后端仍为安全骨架，不能宣称商用上线。原token验证/失效规则、物理DDL、身份权限仍待实际系统提供。可玩体验只在本地判题，不保存真实成绩，不伪造账号。公开题面是演示内容，正式题包必须经事实、翻译、音频及许可审查。
