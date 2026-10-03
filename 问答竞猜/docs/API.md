# 权威会话 API · B07

Base `/api/quiz`，统一 envelope `{version:1, requestId, mode:"server", data, error}`。error为null或`{code}`。成功业务响应来自服务器；当前实际原身份及分布式配额未配时默认503，不存在生产fake登录模式。

- POST `/sessions`：`{locale, idempotencyKey}` → SessionView
- GET `/sessions/{id}/current` → SessionView，事务中结算已过期时间
- POST `/sessions/{id}/rounds/{round}/ready`：空正文 → SessionView
- POST `.../answer`：`{choice:0..3,idempotencyKey}` → `{session,result,accepted}`
- POST `.../next`、`.../fifty-fifty`：空正文 → SessionView
- POST `/sessions/{id}/abandon`：空正文 → SessionView
- GET `/me/progress`、`/me/sessions?limit=20`：本人本站点的进度/归档

SessionView只给当前题、选项、状态/时间/分数；正确答案与解释在reveal中，揭晓前为null。无完整题库、未来题目或身份ID。语言仅在开局指定。客户端不传计时、分数、user/site/team ID。JSON未知字段、重复键及数字字符串转换均拒绝。

LOADING从题面下发起最多15秒，ready后进入已审核readingMillis，随后20秒回答窗口；不允许无限延迟ready研究已展示题目。回答以服务器进入规则操作时的时间裁决，截止时刻即超时。客户端时间只用于显示，隐藏页面不暂停。解释/下一步可稍后操作；完成局不会因活跃期限变成弃局。

开局的重放查询、题包选择、插入及最后授权复验在同一外层事务内。相同owner/site/key重放原局，即使原题包后来退役；同key改locale返回409。题包选择以其批准读取时刻为边界，已选快照不被后续退役改写。

认证适配器必须每次做新鲜、有界、只读验证，不能消费正文、续期或切换身份。过滤器在正文前认证与配额、读取后复验；服务在获得行锁后及提交前再次复验同一身份。最终复验失败会回滚业务写入。实际token/CSRF/撤销规则待原系统提供，当前未实现。

JSON只接受有Content-Length的UTF-8 application/json，最多16KiB；拒绝Transfer-Encoding/Content-Encoding及非JSON接口正文。10秒读取预算在读操作返回时检查，连接超时/有界线程同时配置；这不宣称替代生产代理的绝对读取时限、连接限流和慢连接验收。默认同Origin校验且不开放CORS；TLS反向代理和Host/转发配置必须按真实部署验收。

RequestQuota没有宽松生产fallback；缺失时拒绝。真实Redis实现仍待完成。测试注入只在src/test且需test profile，不打入生产JAR。NODE_HTTP用同一server-api.js源码，通过实际loopback HTTP完成5题750分及成绩/历史验证；其身份和内容是合成fixture，持久层是H2，不代表真实用户登录或此HTTP全链已在MySQL上运行。

B07会话存储信封升级到schemaVersion3以固定loadingDeadline。B05/B06的版本2和更早/不兼容会话保持原字节并拒绝自动纳管；未提供自动迁移，更没有删除旧数据。物理V001仍不变。真实历史兼容必须另行审阅。
