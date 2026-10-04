# 审核路线与五轮选题 · B20

用户可以保留自由挑战，也可主动读取当前语言的审核路线，选择路线后开局。路线预览只有名称、五轮题类和编辑难度，不包含题目ID、题文或答案。选中路线以服务器快照中的名称/当前轮难度显示；开局不会为缺失路线自动回退随机题。

本批提供“环球进阶”和“遗产之旅”两套双语编辑方案，复用已有20对来源审校题文。难度1/2/3是assistant对基础常识、常见专业知识、较专门知识的编辑估计，没有真实玩家校准；不代表资格评定或自适应测评。当前是有序五轮路线，没有章节解锁、跨局去重或失败扣除机制。

## 版本与审核

- 题包schema2继续用于原自由挑战；分类/路线字段只能缺省或null
- schema3要求每题ReviewedQuestion增加category和difficulty，整个包增加plans。每个plan有唯一id、当前语言title、恰好五个slots；每个slot是category+difficulty。难度只能1–3且不下降，最多12条路线
- 每条路线都必须有足够多的不同题覆盖槽位；重复题类/难度槽也必须用不同题。分类缺失、重复路线、题数不足或审核/hash不匹配时，整个候选包拒绝提供目录/开局。不会静默使用旧包或demo
- 每次选择只使用已有审核记录匹配的有效包。路线按槽位从对应候选中打乱抽取，五题各异；自由模式仍从全包随机抽五题
- 双语标注在content/reviews/world-foundations-r2.routes.json，对原题文和B14文本review各自绑定SHA256；共享题类/难度/槽位，路线标题分语言。20条难度理由均明确为assistant编辑判断

从仓库根目录运行只读验证：

```
python3 问答竞猜/tools/challenge_plans.py 问答竞猜/content/candidates/world-foundations-r1.json 问答竞猜/content/reviews/world-foundations-r1.assistant.json 问答竞猜/content/reviews/world-foundations-r2.routes.json
```

附加 `--output /path/to/new-empty-directory` 可导出两份schema3 JSON及hash receipt；建议独立包版本world-foundations-r2-routes。该工具不连接数据库、不写审核状态、不授权部署或音频。原题包及音频候选不被修改；若换bankVersion启用路线，原旁白必须另行绑定并经过既定试听条件，不能因题文相同直接冒用旧绑定。

## HTTP与持久会话

`GET /api/quiz/challenges?locale=en`（或zh-CN）需要相同身份复验及Redis配额，返回version、locale、plans。没有题类路线的schema2返回空plans；读取失败有明确错误，前端不会捏造列表。前端语言变化、开局、卸载取消旧请求，认证失效清理会话。

POST /sessions 的原locale、idempotencyKey不变，路线模式同时发送challengeId和challengeVersion；自由模式省略二者。目录版本变化或路线撤下返回409，用户需重新查看或明确选择自由挑战。重复同key、同scope、同语言/方案/版本重放原会话；变更任何选择参数返回幂等冲突，不换题、不重建会话。审核包撤下不破坏已建立的会话快照。

新会话state_json envelope为schema4，保存不可变ChallengePlan和原bankVersion；公开会话DTO新增challenge和challengeVersion，自由挑战为null。读取兼容schema3自由会话，旧schema3不得含非空路线；非法版本/数据拒绝读取而不删除。没有DDL、表、history或账号权限变化，V001原字节保持。

部署必须先停止旧实例接收新游戏并协调前后端版本，再整体启用B20及以上。B19不能读取schema4，不能直接回滚二进制继续接管新会话；应保留可读schema4的修复版本。旧schema3仍可继续游戏，发生原有业务状态写入时保存为schema4，不做生产批量转换。

## 验证边界

本地后端134通过/65专项跳过，HTTP20含源码JS客户端读取路线→五题750分、目录无题文、版本/幂等冲突；H2验证9条新增题包与存储合同。本批CI负责实际MySQL53、Redis11和可复现打包。前端62 Node+102 DOM通过，Python42通过。浏览器设备/视觉/听音以及真实原系统身份/目标数据库验收仍未完成。
