# Redis 请求准入

此实现只控制请求频率，不保存游戏权威状态、身份或成绩。MySQL仍是幂等和最终结果来源。默认关闭，缺少它时HTTP返回503；启用后Redis故障同样拒绝，没有进程内替代计数器。

## 明确配置
- QUIZ_REDIS_ENABLED=true：显式启用
- QUIZ_REDIS_HOST：必填单一主机名；不接受URL、userinfo、query或自动选择AI中台连接
- QUIZ_REDIS_PORT：1–65535，默认6379
- QUIZ_REDIS_TLS：默认true；只对精确127.0.0.1或::1允许false作一次性测试。生产使用TLS和受信任证书/主机验证，不能关闭peer验证
- QUIZ_REDIS_USERNAME / QUIZ_REDIS_PASSWORD：非明文loopback必须提供专用ACL身份，经部署机密注入；不写仓库或日志

DB固定0，键前缀固定quiz:quota:v1；不使用spring.data.redis.*、任意URL参数、通用连接属性或共享管理员账号。当前为单端点连接，不宣称已验收Cluster/Sentinel拓扑。

## 原子规则与资源边界

经原身份验证后、读取请求正文前，以siteId+siteUserId为作用域：滚动60秒全部业务请求最多120次，create请求另外最多6次；两个范围在一段Lua中核验并提交。相同用户多实例/多会话/轮次不能拆分总额度。轮询、重试和失败的业务请求也需要准入；没有只凭幂等键免费绕过限流的路径。

时间来自Redis TIME，接纳记录使用服务器随机UUID；键含共同hash tag，成员最多120或6，65秒TTL；未知结果、错误类型、已有不带TTL的键、命令错误均503。达到额度为429。没有本地时间桶或不可靠的先读后写竞态。

每实例一个连接，最多16个正在调用，队列上限16，命令500ms截止、socket连接500ms；新请求在断线时立即拒绝，不无限排队。初次DNS/TLS握手另受驱动协议时限约束，500ms不是整个应用启动的绝对期限。服务器已执行但响应迟到时会保守拒绝，可能消费额度，不能假定超时表示未执行。I/O和计算线程池各2，关闭时释放连接和资源。

## 最小ACL审核清单

部署人员在获授权的Redis上为专用账号设置密码，限制键为quiz:quota:v1:*。仅需要：HELLO、AUTH、PING、CLIENT SETINFO/SETNAME、EVAL及脚本内部TIME/TYPE/PTTL/ZREMRANGEBYSCORE/ZCARD/ZADD/PEXPIRE。无需GET/SET、KEYS/SCAN、FLUSHDB/FLUSHALL、CONFIG、ACL、其他应用前缀或DB选择。CI使用临时账号证明脚本可运行，而外部键、管理写和普通SET均被拒绝。本文不包含可直接创建生产凭据的脚本。

Redis应有独立内存预算、noeviction及运维监控；内存不足时拒绝。重启丢失限流键或淘汰键会重置该窗口，不能把配额当计费/现金风控。生产部署需审阅持久化/恢复与前置网络限流策略。未认证请求、status端点和慢正文攻击还需要反向代理边界，当前实现不替代它们。

实现依据：[Lettuce连接队列与超时](https://redis.github.io/lettuce/advanced-usage/client-options/)；[Redis有序集合范围清理](https://redis.io/docs/latest/commands/zremrangebyscore/)。

## 验证

普通测试使用mock验证键/返回值/配置和默认关闭，不冒充真实Redis。CI专门一次性Redis7.4执行10条实际合同：双实例并发、总配额、Redis时间清理/TTL、损坏键、无部分接纳、存储上界、真实暂停超时、断线、运行factory、受限ACL。不能把该服务或端口转发到真实系统。
