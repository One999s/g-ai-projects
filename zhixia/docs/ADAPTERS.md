# Provider / 执行器接入规范

## 当前实现与未实现

- 已实现：两种 fake 名称同一确定性计划、显式不可用时回退、structured_actions 能力检查、单次预估费用门控、配置安全校验、文件执行器和本地恢复
- 未实现：真实模型 HTTP、流式输出、token/实际账单统计、累计预算预留、真实上下文窗协商、Codex 进程启动/协议解析、MCP 客户端、Windows 和浏览器执行器
- `LiveProviderConfig.connect()` 明确抛 NotImplementedError，不会偷偷改用假模型。`cost_microusd` 只是测试合同的单次预估门控，不应宣传为实际费用控制

## 模型接入次序

首个真实 adapter 选择用户有权限的一个服务或本地 Ollama/vLLM，再逐步覆盖其他供应商；本轮没有用户密钥，不进行计费调用。

| Provider kind | 计划 wire / 接入策略 |
| --- | --- |
| openai | 独立 OpenAI adapter，按实际 Responses/工具协议验证 |
| claude | 独立 Anthropic Messages adapter，不伪装成相同 wire |
| grok / deepseek | 各自厂商 adapter；即使兼容 API 也逐项测试工具格式、流式、错误及用量 |
| ollama / vllm | 明确本地 endpoint；HTTP 仅允许 loopback，远端要求 HTTPS 和授权凭据引用 |
| compatible | 自定义 endpoint + model + 已验证能力清单；不能仅凭名字宣称兼容 |

配置只保存 provider kind、model、endpoint、api_key_env 名称。实际凭据从运行进程环境读取，不写 SQLite、不进异常日志、不随摘要传出。校验拒绝 endpoint userinfo/query/fragment 和非本地 HTTP；这仍不是通用 SSRF 防护，真实联网前另需明确 endpoint 授权、重定向策略、DNS/私网过滤、超时/请求体限制。

### 真实调用必须先补的合同

1. 将 model、tool schema、结构化输出、vision、context window、最大输出、计费单位/价格来源显式协商；不以供应商品牌猜能力
2. 预留累计预算/调用上限，执行前拦截，结束回写真实 usage；失败/取消/流断成本仍需处理，不能把“未收到回答”当零花费
3. 只有明确定义的 transient/unavailable 可回退；拒绝/策略失败、未知副作用、无效工具输出不能用另一模型绕过
4. 每个模型动作输出重新走 allowlist、schema、权限、审批、执行账本；模型永远不持有直接工具旁路
5. 跨供应商开启新会话，仅用显式 `TaskSummary`、选定可见事实和已授权结果引用；不得传隐藏思维链/供应商 reasoning blob/全量历史/凭据
6. 同 provider 换 model 也必须检查能力、上下文、权限和费用，不承诺任意 session 无损迁移

## Codex app-server：规划中的进程 adapter

固定审计版本见 [REUSE-AUDIT](REUSE-AUDIT.md)。实际实施时 pin 可执行版本/哈希，运行其 generate-json-schema/generate-ts 产生版本专属合同，并把脱敏 fixture 放入测试。官方 app-server 当前为实验性，先受控内部使用，不承诺生产稳定。

- 启动：固定可执行路径、受控环境、最小 cwd；stdio JSONL，wire 不自行添加 jsonrpc 字段，不监听 WebSocket/公网
- 生命周期：initialize → 成功 response → initialized → thread/start 或 thread/resume → turn/start
- 持续双向读取：区分带 id 的 server request、response 和通知；限制行长/缓冲/超时，stderr 脱敏，不能用单次 subprocess.run 假装持续协议
- 映射：本地 task/operation ↔ threadId/turnId/itemId，重连先校验对应关系和最后已确认 checkpoint
- 审批：`item/commandExecution/requestApproval`、`item/fileChange/requestApproval`、`item/permissions/requestApproval` 必须桥接至知夏策略；默认拒绝未知请求与新增权限
- 动态工具：`item/tool/call` 也须知夏领取 operation、校验与批准后执行。MCP 工具同样不得绕过
- 完成：`turn/completed` 必须检查 status；只有独立 verifier 的 receipt 才能证明业务完成
- 取消：`turn/interrupt` 仅为中断请求，不证明撤销，外部副作用可能已发生
- 异常：进程退出、超时、未知 response id、schema 不符 → 阻断 / needs_reconciliation；不得自动重放发送/付款/写入
- `TurnStartParams.model` 可覆盖模型，thread start/resume 可带 modelProvider，但不据此承诺跨 provider 透明恢复

Phase0 没有 app-server adapter 可执行代码，以免一个空壳被误认为实连完成。上述接入设计与固定源码已可用于首批 Issue 验收。

## Windows / 浏览器执行器

每次只接受一个经过策略的动作，参数包括 task_id、operation_id、device_id、adapter/version、目标进程/窗口、内容 hash、路径、最大时长及批准 token。业务验证独立于执行器自报成功。

- Windows：先普通权限 UIA/Win32；启动指定 exe、核对窗口与控件唯一性，不得模糊焦点输入。具体步骤见 [WINDOWS-ACCEPTANCE](WINDOWS-ACCEPTANCE.md)
- Browser：原生网站 API 可行则先用 API，否则 Playwright DOM；独立 profile，域名 allowlist，默认禁下载、扩展、剪贴板及通知权限
- 视觉后备：只有 UIA/DOM 不可用且该用途另行授权时启用；桌面截图/键鼠不等于摄像头/麦克风权限
- shell、外发、生产修改、付款当前没有 adapter。以后必须各自明确风险与审批，不能把万能 shell 包成“原生 API”

## 后续角色与记忆边界

规划者只提交 Action；策略层决策；执行器只做限定动作；verifier 出具证据；本地操作员审批。Phase0 为同进程模块职责，不宣称角色已由 OS 权限隔离。下一阶段用独立进程 broker 后再授予真实桌面能力。

六类 MemoryKind 的存储目前未实现。实现时 key = scope + kind + key，scope 至少区分 user/project/device/task；增加 provenance、created/updated/expires、纠错/删除墓碑和检索权限过滤。经验记忆只能建议步骤，不自动提升审批权限。不得把这部分预留接口当作多租户安全完成。
