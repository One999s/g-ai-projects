# 持久章节旅程 · B21

本批是一条有限的三章旅程，每章五题。第一章基础题，第二章混合进阶，第三章深入探索；通关门槛依次为至少答对3、3、4题。完成一章且达标才解锁下一章，已解锁章节可以重玩。编辑难度不等于玩家数据校准，公开开发题包不能作为保密比赛题库。

## 用户流程

服务器入口选择语言→“进入章节旅程”→读取服务器地图→选择已解锁章节→五题答题→明确显示是否通关→返回章节地图查看解锁/重玩。地图展示每章目标、难度槽位、完成次数、最佳分数和最佳答对数。锁定章节没有可用开局按钮，服务端同样拒绝越级请求。自由挑战/单条路线入口保留，其成绩不产生章节解锁。

“完成次数”只计算五题完整结算，未完成或放弃不计。完成但未达标会记录一次并允许重玩；已通关章节重玩失败不会收回解锁或降低最佳记录。第三章达标后显示三章全部通关，仍可返回地图重玩。没有体力、付费解锁、钱包、团队或排行榜。

地图不是缓存推测：进入、刷新、重新可见时读取当前身份范围进度；隐藏/卸载/语言切换清空并取消请求，身份失效清理整个旧会话。失败不显示虚构的零战绩/已解锁，网络重试保持同一开局选择和幂等键。

## 题包schema4与规则身份

schema4沿用schema3的分类/路线，并增加campaign：id、version、当前语言title及恰好三个levels。level含id、title、planId、requiredCorrect。每章引用已验证的五轮路线，三章的难度总和必须严格提高，通关门槛不下降；缺题、错误引用、重复章节或不合法规则令整个包失效。schema2/3继续支持原自由/路线模式，其章节目录为空。

新world-foundations-r3.journey.json仍绑定原20题及双语事实审校hash，包含四条路线、三章和明确编辑难度理由。导出命令（仓库根目录）：

```
python3 问答竞猜/tools/challenge_plans.py 问答竞猜/content/candidates/world-foundations-r1.json 问答竞猜/content/reviews/world-foundations-r1.assistant.json 问答竞猜/content/reviews/world-foundations-r3.journey.json --output /path/to/new-empty-directory
```

工具只导出双语schema4 JSON及receipt，建议独立bankVersion `world-foundations-r3-journey`。不连接DB、不写批准记录、不批准音频/部署。没有修改原题文、V001或音频候选。

进度规则键是SHA256：campaign id/version、各章id、引用plan id、门槛及有序题类/难度槽位的规范文本。格式为id换行version换行，再逐章 `levelId|planId|requiredCorrect|category:difficulty;...` 加换行；全部标识符受限ASCII，拒绝保留字free，避免目录展示不可进入的章节。语言、展示标题及题文不参与此键，所以同规则双语共享进度，普通内容更新可延续进度。改变规则/门槛/槽位/标识会得到新键，旧进度保留，不自动转授新版本解锁。任何版本仍必须通过题包hash/审核约束。

## 服务端权威与事务

新增身份/Redis保护的GET `/api/quiz/journey?locale=en|zh-CN`；响应只含章节规则元数据及当前site/user完成记录，没有题文、答案、原身份字段。POST /sessions以 `chapterId` 和 `challengeVersion` 选择章节，与challengeId互斥；语言、章节和包版本进入创建幂等绑定。错误选择400、越级/目录变化/章节不可用409，无隐式降级。

开局在原创建事务内锁定Quiz已有的用户累计行，再读取同规则键的前置章节通关记录，检查后才保存会话；完成事务也通过同一用户行串行。下一章开局不能观察尚未提交的通关；通关事务撤销时等待中的开局仍被拒绝。

最后一题结算同时写成绩、累计记录、章节完成/最佳/通关标志及outbox。重复提交不会再计完成次数；任何后续写入或最终身份复验失败均回滚整个结算。新会话保存不可变章节合同，题包后来撤下不会改写既有会话规则。进度按site_id、site_user_id、规则hash、章节序号隔离，跨site/user及新规则不继承解锁。

## 迁移与回滚

新增且仅新增Quiz-owned V002__quiz_chapter_progress.sql的一张quiz_chapter_progress表，无外键或原实体/AIhub表修改。V001原SHA保持9129c52f44988c69bc3f7f3e7524b249c8cceeb912950160cd06b989491883e7；V002 SHA是c674dea8b6e47ba90532c870f15da8f30fca8a0543918d56a4cd755b3536d2aa。

受控offline migration owner先核对目标与备份，在共同迁移锁下按独立quiz_schema_history执行增量，再由授权部署流程授予运行账号此表SELECT/INSERT/UPDATE；无DELETE/DDL权限。运行时不自动迁移、不创建账号、不写原身份数据。AIhub共库fixture必须同步这份精确SQL和2迁移/7表契约；两个应用的独立history/迁移顺序及重复迁移保数由实际MySQL合同验证，不能用H2替代。

会话新写schema5，兼容读schema3自由会话和schema4自由/路线会话，旧版本不能携带非空章节。原业务状态写入保存为schema5，不做生产批量转换。先完成增量迁移和最小权限、排空旧实例，再部署配套前后端；B20不能读取schema5，禁止直接回滚旧JAR接管新会话。未知/不兼容数据仍保留并拒绝读取。

## 验证与限制

本地后端全量224条：147通过、77专项跳过；发布前保留字校验同步修正后另跑题包31条定向通过；HTTP21包含实际源码JS客户端章节五题750分→下一章解锁，以及三章失败/重玩/逐级达标全链路。H2包含并发提交等待/回滚拒绝、重复结算、后续SQL失败回滚、跨site/user/规则版本隔离。新增实际MySQL合同预计65条、Redis11条由本批CI执行；AIhub双顺序共库结果由同步fixture的独立CI确认。

CI修复后的前端178测试、Python45测试；地图/隐藏/失效等是DOM合同。真实设备视觉、原系统身份及部署库验收尚未完成，英文20条和中文1条音频仍未试听批准；不声明production ready。当前没有章节间题目去重或无限关卡，也不以这些扩展替代真实接入与设备验收。
