# 知夏 AgentOS v2.0

Windows 优先、可换模型、可恢复任务的个人 Agent OS。**当前交付是 Phase0 工程骨架（0.0.1），不是已完成的桌面 Agent 产品。**

## 本轮实际完成

- Python 3.11+、SQLite、标准库 CLI；运行和测试不需要安装第三方依赖或模型密钥
- 持久任务状态、步骤检查点、审批、取消、操作账本、只读恢复核对与文件内容证据
- 工作区内 UTF-8 文件创建与独立字节验证；拒绝越界、符号链接、硬链接目标、Windows 保留名、未知工具及已有不同内容文件
- 审批绑定任务/步骤/完整动作参数 hash/工作区/策略版本，有效期默认 5 分钟、一次性消费
- 两个确定性 fake provider 的路由、能力/预算门控、故障回退合同测试
- 候选 Notepad/UIA backend 与显式验收 CLI：两阶段审批、PID/HWND/进程创建时间绑定；Windows 真机仍 NOT_RUN
- 七类真实模型接入的配置校验与设计；**尚未实现任何真实网络模型调用**
- Codex、UFO、Playwright、Browser Use 的固定版本来源/许可筛查；不 fork、不复制其源码、不安装其运行时

**没有完成：** Windows 真机记事本输入保存与 UIA 兼容验收、浏览器适配器、Codex app-server 实连、真实模型切换、长期记忆数据库、语音摄像头、多设备、商业租户。六类记忆目前只有类型和隔离/删除接口合同。模拟结果不能充当真机验收。

## 立即运行

在仓库 `zhixia/` 下执行（Windows 可将 `python` 换成 `py -3.12`）：

```sh
python -m unittest discover -s tests -v
python -m zhixia --help
python -m zhixia create --path notes/hello.txt --text "你好，知夏" --provider fake-a
```

记下返回的 task_id，替换下面的 TASK_ID：

```sh
python -m zhixia tick TASK_ID
python -m zhixia show TASK_ID
```

`show` 会显示完整动作和审批记录。核对文本、路径、有效期后，将该记录的 id 替换 APPROVAL_ID：

```sh
python -m zhixia approve APPROVAL_ID
python -m zhixia tick TASK_ID
python -m zhixia tick TASK_ID
python -m zhixia show TASK_ID
```

最后应为 `succeeded`，文件位于 `.runtime/workspace/notes/hello.txt`。第一次 tick 执行创建并检查内容，第二次 tick 用独立读取再次验证。每条 CLI 是独立进程，审批和任务在进程退出后保留。默认只生成本地确定性计划，不调用模型、不产生模型费用。

拒绝用 `deny APPROVAL_ID`，取消用 `cancel TASK_ID`。只有确认旧执行进程已经退出时才运行：

```sh
python -m zhixia recover TASK_ID --worker-stopped
python -m zhixia reconcile TASK_ID
```

若落盘内容与计划吻合，记录核对证据后推进；若不存在或不吻合，保持 `needs_reconciliation`，不会自动重写。人工调查后可 `abandon TASK_ID` 标记失败，残留副作用不会被宣称撤销。运行中的取消也是协作式，可能已经写入文件。

## 安全与数据边界

- 这是单用户、可信本地执行器的应用策略，**不是 OS 强沙箱**。同一 OS 用户能改数据库和代码，不构成对该用户的安全隔离
- CLI 审批由本地操作员做出；无远程认证、无公网监听、无任意 shell、无高风险工具。模型只能产提案，不能自行发审批
- workspace 检查不抵御恶意本地进程的文件系统竞争；生产桌面授权前须实施进程隔离、权限代理和 Windows 实机测试
- SQLite 保存任务目标、文件计划正文和执行证据，当前不加密。不要把密码、API 密钥或敏感个人资料放进 `--text`。命令行还可能进入 shell 历史
- API 密钥只允许在接入层使用环境变量引用；不写任务/审计记录，不序列化供应商隐藏推理。任务数据库和运行文件均被 gitignore 排除
- 发布使用普通独立分支；不修改其他项目或 main。核心运行依赖仍为空；可选 UIA 候选组合已独立固定包/哈希，尚未安装/运行验证，再分发前仍需完整 SBOM

## 文档导航

- [架构决策 ADR-001](docs/ADR-001.md)
- [两阶段 Notepad 适配 ADR-002](docs/ADR-002.md)
- [Windows 可选依赖审计与安装候选](docs/WINDOWS-DEPENDENCIES.md)
- [固定版本复用与许可证审计](docs/REUSE-AUDIT.md)
- [Provider、工具与 app-server 接入规范](docs/ADAPTERS.md)
- [Windows 记事本验收规程](docs/WINDOWS-ACCEPTANCE.md)
- [开发规范](docs/DEVELOPMENT.md)
- [任务看板与首批 Issue 拆解](docs/BACKLOG.md)
- [本次验证记录](docs/VERIFICATION.md)
