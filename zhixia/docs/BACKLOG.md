# 看板与首批 Issue 拆解

更新：2026-10-10。以下是可直接录入 Issue 的工程任务，当前保存在仓库文档，**未自动创建远端 GitHub Issue**。v2.0 是目标产品名称，当前包版本为 0.0.1。

## 已完成（Phase0）

- ZX-001 固定版本与许可审计：Codex loop/tool/MCP/sandbox/approval/recovery 来源及 UFO/浏览器选型，证据见 REUSE-AUDIT
- ZX-002 SQLite 最小闭环：计划、审批、操作账本、文件验证、取消、真实进程崩溃恢复、禁止未知结果重放
- ZX-003 Provider 最小合同：fake 路由/回退、能力与单次预算门控、真实配置校验；不包含 live 调用
- ZX-004 中文 README、ADR、安全边界、开发规范、验收与 Issue 拆解

## 下一里程碑（按顺序）

### ZX-005 / P0：Windows UIA 受限记事本 adapter

依赖：可用且明确授权的真实 Windows 交互桌面；pywinauto 固定包与依赖许可/哈希审核。

范围：普通用户启动指定 Notepad、精确 UIA 控件定位、中文输入、受控保存、独立文件 verifier、durable receipt。默认不授予完整桌面、摄像头或麦克风，不绕 UAC。

完成定义：WINDOWS-ACCEPTANCE 中适用的正向/中断/焦点/路径/冲突用例有真实版本和证据。不能用 file.write 或 mock 替代。当前 BLOCKED/NOT_RUN。

### ZX-006 / P0：本地执行 broker 与 Windows 路径/权限边界

依赖：ZX-005 开始前先确认威胁模型。

范围：规划与执行分进程、可信身份/审批通道、最小 token/ACL、job/process tree、junction/reparse/long path 处理、超时与取消；验证文件系统竞争风险和不受信模型无法直接改账本。

完成定义：以真实 Windows 测试证明权限边界；清晰区分应用 allowlist 与 OS 强隔离。不默认为管理员，不安装常驻服务。

### ZX-007 / P1：首个真实模型 adapter

依赖：用户选择服务或本地模型、合法凭据与预算范围。

范围：先一个 provider，明确 wire/capabilities/context/usage，超时/限流/流中断、累计预算预留和结算、日志脱敏、跨 provider 仅显式摘要。禁止自动发起计费调用。

完成定义：离线 fixture 全通过；获授权后有限 live 测试，记录真实调用/费用与未知项。扩展其他 provider 必须逐项验证，不以 fake 通过冒充。

### ZX-008 / P1：Codex app-server stdio adapter

依赖：固定可执行版本、schema、许可证/SBOM；ZX-002/006。

范围：初始化、双向 request/reply、thread/turn/item 映射、审批桥接、工具调用、interrupt、completed status、重连 unknown。默认禁实验能力/网络监听。

完成定义：schema fixture 与异常/取消/崩溃测试、有限实连；安全审批不可旁路。官方实验性生产限制必须保留。

### ZX-009 / P1：Playwright BrowserAdapter

依赖：固定 Playwright 和浏览器二进制版本及许可。

范围：独立 browser profile、domain allowlist、DOM locator、最小权限、默认禁下载/扩展；API 可用优先 API。

完成定义：可恢复受限 demo、跨域/下载/权限提示明确拒绝、超时/页面变化和取消测试。Browser Use 仅作为后续可选规划层评估。

### ZX-010 / P1：六类记忆的最小可用存储

依赖：明确 scope 与用户数据保留策略。

范围：SQLite scope/kind/key/provenance/TTL，写入、检索、纠错、删除及权限过滤；先文本检索，真实需要再考虑向量库。

完成定义：六类枚举有实际 CRUD、跨 scope 不泄漏、删除/纠错不被旧摘要恢复、经验不提升权限。当前只有协议，不标完成。

### ZX-011 / P1：自动化测试与 Windows 分发

范围：Linux/Windows CI（GUI 与无头测试分开）、零测试失败检测、固定依赖构建、安装/升级/卸载、包签名方案、SBOM/NOTICE；新增根工作流前审阅项目边界。

完成定义：同一提交所有适用检查有证据，安装包不带状态/凭据，真机启动/升级通过。本轮没有构建安装包或自动 CI。

## 延后，非当前实施范围

ZX-012 语音/摄像头按用途权限；ZX-013 多设备身份、角色及每设备 capability/lease；ZX-014 商业租户、认证/计费/治理。现在只保留必要合同，不先造未被需求驱动的平台。
