# Windows 首里程碑：记事本输入、保存、独立校验

当前状态：**NOT_RUN / BLOCKED**。本轮只有 Linux 云环境，没有获授权且在线的 Windows 交互桌面；没有运行 UIA、记事本或 Windows 安装包。不请求管理员权限，不启动用户离线电脑，不用 CLI 写文件冒充 GUI 完成。

## 目标

在真实 Windows 10/11 交互桌面，使用普通用户权限打开记事本，输入指定中文内容，通过记事本 UI 保存到授权工作区，再由独立只读 verifier 读取文件并校验内容与证据。Windows VM 也可，但必须是真实 OS/交互桌面，而非 mock。

## 前置条件

1. 用户授权具体设备、目标目录、记事本程序及所需 GUI 操作；设备在线且可交互
2. 记录 OS build、Python、Notepad 版本、adapter/pywinauto 版本与校验哈希，完成固定包和依赖许可筛查
3. 普通用户运行；不绕 UAC、不为这一步新增 uiAccess、服务或远程常驻访问
4. 核对目录、已存在文件、目标编码/换行；默认 overwrite=false，文件冲突立即停止
5. 确认交互 session 有效；RDP 锁定/断开、非交互 service 不能当作可靠 GUI 环境，见 [pywinauto remote execution](https://pywinauto.readthedocs.io/en/latest/remote_execution.html)

## 提案与审批

审批计划绑定 device_id、task_id、operation_id、adapter 版本、exe 绝对路径、进程/窗口身份、完整输入文本及 hash、规范化目标路径、overwrite=false、允许子步骤、到期时间和一次性 nonce。审批后参数变动必须重新批准。摄像头与麦克风默认关闭，不属于本任务权限。

## 执行步骤及每步停机条件

1. 固定 API 启动授权 Notepad，确认新进程/目标窗口身份；发现提权/权限提示或无法区分既有窗口即停
2. 读取真实 UIA tree，唯一定位编辑控件，使用合适的 Value/Edit 模式输入；旧版标题/控件名不可直接套新版 Win11
3. 输入前再核对焦点/进程/窗口，核对输入后内容；歧义、焦点变化、超时即停，禁止盲目全局粘贴
4. 受控 Ctrl+S / UIA 保存对话框，填写授权路径，拒绝覆盖提示、扩展变更和目录逃逸
5. 执行器返回后，独立 verifier 只读落盘文件：核对实际规范化路径、普通文件、非 reparse/link、字节/编码/换行合同、sha256、时间和本次 operation 关联
6. 写入 SQLite receipt 后才能宣布 verified；截图/日志属于辅助证据，不代替文件校验
7. 进程/网络/窗口出错或中断后，标记 unknown / needs_reconciliation，先查证，不自动重放 GUI 保存。取消不等于撤销

## 验收矩阵

| 用例 | 当前结果 | 通过要求 |
| --- | --- | --- |
| Win11 当前 Notepad 中文输入保存 | NOT_RUN | GUI 链路与独立字节验证均通过 |
| Win10 / 旧 Notepad | NOT_RUN | 支持范围按实测写明，不默认继承 Win11 结果 |
| 焦点变化、窗口歧义、UAC | NOT_RUN | 无误输入/权限升级，停止并给出原因 |
| 中文/空格/长路径、保留名、junction/reparse、既有文件 | NOT_RUN | 规范化与拒绝策略均有实机证据 |
| 保存成功但 receipt 前进程崩溃 | NOT_RUN | 核对已写文件，禁止重复执行副作用 |
| 操作前/执行中取消与进程树终止 | NOT_RUN | 明确完成/未知状态，不误报回滚 |
| 断网、模型不可用、审批过期 | NOT_RUN | 本地状态保留、正确阻断/恢复 |
| 摄像头/麦克风默认拒绝 | NOT_RUN | 无设备访问或隐式授权 |

Phase0 Linux 单元测试只证明文件策略、SQLite 账本和恢复合同。不得将这份表改成 PASS，除非附真实 Windows 版本、运行记录及内容证据。
