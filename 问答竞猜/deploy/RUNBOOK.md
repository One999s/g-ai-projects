# 运行与交付说明

此包是可核验的软件交付，不是已上线的商用服务。原身份adapter尚未实现，默认业务503。没有生产账号、Token、DDL迁移权限、题包审批或麦克风权限被打包进去。

## 产物与复现

完整JDK21、Node24、npm和Maven。从已提交且干净的仓库执行：

```sh
python3 -m unittest discover -s 问答竞猜/tools -p 'test_*.py' -v
python3 问答竞猜/tools/build_release.py
```

默认输出到问答竞猜/.release/<commit前12位>，已有目录拒绝覆盖。流程执行npm锁定安装、前端测试/构建、后端clean verify，再次clean package并校验JAR字节一致；前端也重建比对。用提交时间固定ZIP时间戳。最后启动实际JAR，只使用清理过的环境及随机loopback端口，验证状态和缺身份先于正文拒绝，再验证缺shared-db目标无法监听。不会读取部署凭据或连接生产DB/Redis。

receipt.json记录commit/tree、每个源码输入及交付文件SHA256、真实JUnit通过/跳过数量、两次构建比较和packaged smoke。跳过的MySQL/Redis合同不算通过；CI发布作业须等两项独立真实服务合同全绿后才构建。CI保存14天artifact便于下载，长期依据仍是Git源码和可复现流程；不能把短期artifact当永久备份。

JAR排除已知测试身份类/头标记、test profile和测试依赖；没有演示登录开关。source.tar.gz仅含此项目及相关构建配置。迁移SQL随源码保留但绝不自动执行。收据不是签名；从可信GitHub提交核对来源后再校验hash，不能只信来历不明的包内收据。

## 默认安全启动

```sh
java -Xmx256m -XX:ActiveProcessorCount=2 -jar quiz-challenge-<commit>.jar
```

默认仅监听127.0.0.1:8081，GET /api/quiz/status返回productionReady=false；会话请求503 IDENTITY_ADAPTER_NOT_CONFIGURED。这可用于部署前检查，不代表已能真实登录答题。

前端index.html是明确标注的本地演示；server.html必须由同源HTTPS站点配合/api/quiz反向代理使用。不要把server入口与演示误标为同一正式环境。生产静态文件请设置安全响应头，API禁止共享缓存，不启用宽泛CORS；代理保持可信Origin/Host链，未审阅前不要开启任意forwarded header信任。

## 接入顺序与停止条件

1. 核对真实系统的登录/current-user实现、Token签发/验证/注销撤销、账号状态、site有效性/权限与CSRF契约
2. 开发并审阅ExistingIdentityAdapter，部署前重建新commit、新测试、新收据。它必须在每次resolve时重新验证且有有限超时，返回真实siteId/siteUserId；不能从请求体、自称ID头、实体字段或不验签Token构造身份。异步缓存失效、注销后重放、跨site和账号冻结需要实际系统合同测试
3. 独立schema owner确认目标逻辑库、实际DDL/history/存量版本、备份及授权，持同一连接的shared_apps_schema_migration锁，串行管理独立ai_hub_schema_history/quiz_schema_history。运行账号不持DDL/history写权限。现有表/history不自动baseline/repair/drop；失败先审阅MySQL非事务DDL结果。不能仅因两个应用“同库”就操作原生产实例
4. 审阅quiz-runtime-grants.sql.template；运行时只使用六张quiz_*表及必要版本只读权限，不访问原身份/team/wallet/AI中台业务表。若未来身份adapter需原系统调用或只读访问，要重新审阅其最小权限，不把原管理员账号给游戏
5. 通过机密注入提供下表配置，先在授权一次性环境验证，再决定生产部署；本仓库不会替你创建生产凭据
6. 按REDIS-ADMISSION.md配置受限ACL、TLS、内存/noeviction与恢复；正式文字题包仍需审核和独立发布流程，音频不因文本审批自动批准
7. 验收实际登录→5题→唯一结算→同用户site归属、注销/超时/重试、最小权限、备份恢复与反向代理慢正文边界。缺任何关键资料时保留503保护

## 环境配置

| 变量 | 作用 |
| --- | --- |
| SPRING_PROFILES_ACTIVE=shared-db | 显式使用共享逻辑库 |
| SHARED_DATABASE_URL | 必填MySQL URL；不提供示例真实地址/凭据，须经严格白名单，生产审阅VERIFY_IDENTITY |
| QUIZ_DATABASE_USER / QUIZ_DATABASE_PASSWORD | 独立运行账号，机密注入 |
| QUIZ_DB_POOL_SIZE | 默认4，有界1–32；须按总连接预算审阅 |
| QUIZ_REDIS_ENABLED=true | 显式启用分布式准入，默认false |
| QUIZ_REDIS_HOST / PORT / TLS | 专用明确端点；PORT默认6379，TLS默认true |
| QUIZ_REDIS_USERNAME / PASSWORD | 专用最小ACL身份；无跨应用/管理权限 |

此表是配置契约，不是可直接运行的生产.env。不要提交.env、密码或原实体ZIP。Redis设置不从Spring通用Redis URL继承；MySQL不接受额外Hikari/SQL hook覆盖。

## 数据版本、回退与运营

B20新写state_json版本4，兼容版本3自由会话，正式文字包支持schema2自由挑战及schema3审核路线；不批量转换或删除旧状态。回退JAR前必须核对其读写版本兼容性，不能把替换二进制当数据回滚。先保留数据库备份与迁移清单，由schema owner决定恢复顺序。不要删除不兼容行来“恢复服务”。

公开status只表示进程存活且productionReady=false，不是完整就绪探针；部署系统需额外核验审核题包、真实身份链与依赖状态。错误用请求ID关联日志，日志不应记录Token、Cookie、语音字节、原始凭据或完整私有问题库。代理层设置连接/正文总时限、大小和速率上限；应用读后检查不能替代绝对慢请求截止。

当前无外部outbox消费者、现金结算、团队或钱包动作。AI主持和CMS不是该发布门槛。

## 可选语音候选

QUIZ_ASR_ENABLED默认false。明确启用时，QUIZ_ASR_URL只接受http://127.0.0.1:<port>/internal/quiz/asr，不能指向远程供应商或系统代理。处理器不与Java一起自动安装/下载/启动；用speech-worker/README.md所列无ONNX显式白名单环境和已核对本地模型，重新完成运行和网络出口验收。

配置成功不等于真实识别、隐私或设备验收成功。当前仅固定合成英文样本完成过功能检查，中文、实际麦克风、真实身份和完整网络观测仍未通过。ASR不可用时返回503，正常键盘/点击答题不需要它。语音候选绝不直接计分。

交付脚本现在仅从已跟踪输入复制deploy/docs/content/speech-worker，避免把被Git忽略的本地.env、模型或缓存扫入包；前端也拒绝隐藏文件、符号链接和未知文件类型。不要把跟踪或打包原凭据当成合法机密注入方式。

## 可选审核旁白

QUIZ_NARRATION_ENABLED默认false；启用需明确QUIZ_NARRATION_DIRECTORY和QUIZ_NARRATION_MANIFEST_SHA256。只接受已真实试听审阅的规范本地WAV与精确问题/选项/版本绑定，启动加载后不访问外网或文件系统。未提供正式审核音频；B11的FLAC是未试听候选。配置格式、32MiB内存上限与授权读取/服务器读题时钟见../docs/NARRATION.md。


## B18配套部署要求

本批LOADING/ABANDONED响应隐藏题文，旁白读取只允许READING。必须以同一commit的前端+JAR配套切换，不能只替换后端或继续提供缓存的旧server入口资产。旧/新混用将被快照校验拒绝。它不改DDL、存储state_json版本、身份契约或积分结算规则。


### B20状态与路线兼容
部署B20前协调全部游戏实例停止旧版写入，再启动配套前后端。新state_json为schema4，兼容读取schema3自由会话；B19不能读schema4，不允许直接回滚旧JAR接管新会话。无DDL/history变化，无生产批量状态转换。schema2审核包仍支持自由挑战，schema3路线包必须独立版本与匹配审核/hash；导出工具只生成JSON，不自动上传/审批/迁移。详细步骤与边界见docs/CHALLENGE-PLANS.md。
