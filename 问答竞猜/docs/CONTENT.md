# 内容边界

当前8条双语演示题为本项目原创措辞，仅用于交互验收，status=demo_fixture。未声称事实/语言/录音完成正式审批。正式包需独立记录题目ID、revision、locale、来源、授权边界、审核人、有效期、答案唯一性、录音hash/时长，走draft→fact_checked→localized→audio_ready→approved。

所有生产判题答案仅存在服务端；当前前端可见答案是本地演示的明确限制。演示选择与状态不提交到任何账户。正式ASR必须私有接入、用户主动授权录音、披露接收方、按题限制、超时后丢弃候选，最终需再次确认。

## B06 已审核文本题包读取

ApprovedQuestionBank只读quiz_question_packs/quiz_question_audit。在同一SQL语句中查找当前语言、有效时间内、approved状态、与审核人/内容hash/批准时间完全对应的审批事件。选取时刻是内容选择的时间边界；已被选中的不可变快照可继续使用，退役后新选择被阻止。最终HTTP开局流程尚未集成，不宣称已经处理所有发布并发流程。

数据库先排除超过256KiB的JSON，再复核精确UTF-8 SHA256。文本包schemaVersion=2（与会话信封版本独立），questions必须5–500条，ID唯一、语言一致、四个不同选项、唯一有效答案。每条需sources（HTTPS、无URL用户名密码）、rightsNote、reviewedBy及不晚于批准时间的reviewedAtMillis。原子审批身份仍由真实运营流程负责；这些字段检查不是事实/许可审核本身。

解码拒绝重复JSON键、未知字段、尾随内容及标量类型强制转换。没有演示题回退、自动生成、自动批准或审批写接口。成功只返回随机5题不可变文本快照及版本/hash；不是公开返回完整题库的API。

当前模式是已审核文本包。未实现正式旁白音频包、音频字节/时长核验和生产题目导入工具。演示8题仍是demo_fixture，不因本读取器的测试而被标为approved。测试审校人/来源/状态仅是新建临时库内的合成fixture，不是真实人工审批证据。
