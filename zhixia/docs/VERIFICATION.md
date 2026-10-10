# Phase0 验证记录

日期：2026-10-10 UTC。环境：Linux 云工作区，Python 3.12.14、标准库 sqlite3。未使用用户 Windows 电脑、第三方运行包、模型密钥或计费调用。

## 已通过

- `python -m unittest discover -s tests -v`：74 tests，全部通过，非零用例，无跳过
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

## Windows候选适配增量

- 新增19项纯合同/平台阻断测试，总计44项；所有fake收据标contract_test、real_windows_acceptance=false
- 两次独立审批、PID/文本变动失效、设备变化阻断、并发唯一父子任务、取消、不覆盖和GUI收据丢失不重放
- Linux实跑 `python -m zhixia.notepad_cli check` 返回BLOCKED / windows_acceptance=NOT_RUN，退出码2
- 复核修复：显式task/operation上下文取代全库hash反查；移除全局快捷键，注入焦点变化仍通过目标UIA pattern定向操作，未知菜单无键鼠fallback
- 未安装pywinauto或其依赖；候选包哈希/元数据审计不等于Windows兼容验收

## HTTP模型候选增量

- 新增30项本机HTTP/预算/CLI测试，总计74项全部通过；fixture只绑定127.0.0.1，密钥/模型/usage都是合成数据
- 验证真实HTTP请求字段、两种token参数显式互斥、schema/拒绝/截断/工具调用、usage缺失/非法/超预留、HTTP错误/redirect、输出上限和慢header期限
- 两连接预算预留竞争、原子计划/结算回滚、取消前后、崩溃unknown保持占用、缺usage阻断新调用
- 复核修复：响应model不匹配保留未核验usage、占用全额预留且第二次调用零网络；单项token超限即使总报价未超预留也阻断
- 显式模型选择跨重启保持，旧待审批计划不变；只导出TaskSummary，无隐藏推理和凭据入库
- 真实厂商、实际费用、TLS服务与Windows模型端到端仍NOT_RUN

## 复核中发现并修复

独立只读安全复核发现：reconcile 在锁外读完证据前另一个连接 abandon，原 _complete 可能把 failed 改回 ready。现 _complete 在 BEGIN IMMEDIATE 事务内要求状态为 running/needs_reconciliation；abandon 同事务标 operation 为 abandoned。双连接竞争用例已纳入回归并通过。此处没有将同用户直接改 SQLite 视为受保护边界。

## 未运行 / 不声称完成

- Windows 10/11 GUI、记事本、UIA、junction/reparse、NTFS、长路径、取消进程树、UAC 和真实 OS 沙箱
- Codex app-server、MCP、真实厂商provider/费用/模型切换验收（本机HTTP预算合同已测）
- 长期记忆 CRUD/检索、语音摄像头、多设备、多租户
- 电源故障目录持久性/文件系统竞争攻击测试、渗透测试、完整依赖安全审计
- Windows 安装包构建/签名/安装/升级、远端自动 CI

文件发布用同目录临时文件 + fsync + 无覆盖 hard-link；它在本环境通过，不代表全部 Windows 文件系统或断电条件已验证。路径策略是合作进程内的保护，不是防恶意同用户进程的 OS 隔离。

## 复现

从仓库 `zhixia/` 运行上述三条检查。测试目录均使用临时目录自动清理，运行状态不进 Git。其他平台的适用结果必须另行记录，不能直接复制本记录的 PASS。

## 本批最终独立复核（2026-10-10 UTC）

修复后独立只读复核重跑原 model mismatch loopback 探针：首调用 response_model_mismatch，第二调用在发送前以 unresolved_cost 阻断；fixture 实际请求数为 1，financial_uncertainty=True，预留 6144，charged=NULL，usage 100/30 作为未核验事实保留。复核确认本批已复现问题关闭，74/74 unittest 通过；此结论仅覆盖本候选范围。

HTTP loopback 使用真实本地网络传输和合成响应，并不证明真实模型任务成功。真实厂商、实际费用和真实 Windows 端到端仍为 NOT_RUN。当前凭据检测仅针对运行时当前 API key 的已知字符串（包括 JSON 解码后的字符串）；不是全面秘密识别或防泄露系统，不能识别所有其他凭据、变形/编码秘密或敏感任务内容。操作者仍须审查将发送的正文。

收尾检查：本批测试进程及本地 HTTP fixture 已退出；未发现遗留 zhixia/unittest/test_chat/http.server 进程。文档收尾后执行普通提交、远端内容读回与工作区 clean 检查，不扩展验收范围。
