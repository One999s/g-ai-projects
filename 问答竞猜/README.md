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
检查：`npm test && npm run build`
后端：完整JDK21/Maven，`cd backend && mvn test`

目前开始重建，不能宣称商用上线。原token验证/失效规则、物理DDL、身份权限仍待实际系统提供。可玩体验只在本地判题，不保存真实成绩，不伪造账号。公开题面是演示内容，正式题包必须经事实、翻译、音频及许可审查。
