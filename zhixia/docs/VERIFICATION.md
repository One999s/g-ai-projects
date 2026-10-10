# Phase0 验证记录

日期：2026-10-10 UTC。环境：Linux 云工作区，Python 3.12.14、标准库 sqlite3。未使用用户 Windows 电脑、第三方运行包、模型密钥或计费调用。

## 已通过

- `python -m unittest discover -s tests -v`：25 tests，全部通过，非零用例，无跳过
- `python -m compileall -q zhixia tests`：通过
- `python -m zhixia --help`：通过
- CLI 端到端：每条命令分别启动新进程，create(fake-b) → waiting_approval → approve → write → verify → succeeded；独立读取中文 UTF-8 文件确认逐字节一致
- 真实子进程崩溃：文件写入后、receipt 提交前 `os._exit(73)`；SQLite 保持 running，recover 改为 needs_reconciliation，只读核对后推进，没有重放写动作
- 审批：过期前/后、已消费、参数变化、拒绝、取消；审批与 claim 同事务
- 并发：第二数据库连接不能重复领取 running 操作；人工 abandon 与 reconcile 竞争后终态不能复活
- 工作区：越界/绝对/跨平台歧义路径、Windows 保留名/尾点空格、符号链接、硬链接、未知 shell、超限正文均拒绝
- 文件：已有不同内容不覆盖；独立 verify 检出篡改；未知副作用保持待核对，不自动重复执行
- Provider：fake fallback、显式摘要、预算门控、非预期错误不回退、endpoint 校验、live connect 明确未实现
- 日志：测试验证原始异常私密文本不进入审计事件；没有 API key 写入路径。计划正文仍在数据库，用户不可放入秘密

## 复核中发现并修复

独立只读安全复核发现：reconcile 在锁外读完证据前另一个连接 abandon，原 _complete 可能把 failed 改回 ready。现 _complete 在 BEGIN IMMEDIATE 事务内要求状态为 running/needs_reconciliation；abandon 同事务标 operation 为 abandoned。双连接竞争用例已纳入回归并通过。此处没有将同用户直接改 SQLite 视为受保护边界。

## 未运行 / 不声称完成

- Windows 10/11 GUI、记事本、UIA、junction/reparse、NTFS、长路径、取消进程树、UAC 和真实 OS 沙箱
- Codex app-server、MCP、真实 provider、累计预算和实际模型切换
- 长期记忆 CRUD/检索、语音摄像头、多设备、多租户
- 电源故障目录持久性/文件系统竞争攻击测试、渗透测试、完整依赖安全审计
- Windows 安装包构建/签名/安装/升级、远端自动 CI

文件发布用同目录临时文件 + fsync + 无覆盖 hard-link；它在本环境通过，不代表全部 Windows 文件系统或断电条件已验证。路径策略是合作进程内的保护，不是防恶意同用户进程的 OS 隔离。

## 复现

从仓库 `zhixia/` 运行上述三条检查。测试目录均使用临时目录自动清理，运行状态不进 Git。其他平台的适用结果必须另行记录，不能直接复制本记录的 PASS。
