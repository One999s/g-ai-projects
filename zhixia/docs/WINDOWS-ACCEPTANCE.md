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
4. 绑定窗口的 UIA 菜单 Invoke / 保存对话框，填写授权路径，拒绝覆盖提示、扩展变更和目录逃逸
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

## 候选实现后的显式真机命令

当前已有可插拔候选代码，仍没有Windows实测。该实现限定经典单空白Edit控件Notepad；tabbed/packaged重宿主进程、未知菜单树/对话框、缺Value/Invoke/ExpandCollapse pattern均停止，不会降级全局键鼠。

先按[依赖说明](WINDOWS-DEPENDENCIES.md)在获授权的Windows CPython3.11 x64隔离环境安装候选包。以下示例假定激活该venv，在项目`zhixia/`下执行；Linux执行check只会返回BLOCKED。

```powershell
python -m zhixia.notepad_cli check
python -m zhixia.notepad_cli prepare-launch --path hello.txt --text "你好，知夏" --file-menu "File" --save-as-menu "Save As..." --save-dialog-title "Save As"
```

菜单名必须与目标机器实际UIA树精确一致；英文参数不适用于中文机器。中文候选可明确使用`文件(F)`/`另存为(A)...`/`另存为`，但没有匹配就阻断，不做模糊选择。不得仅凭这些示例声称目标树已验证；真机首测需记录实际UIA树/版本。

第一阶段仅启动。将返回值替换LAUNCH_TASK：

```powershell
python -m zhixia.notepad_cli run LAUNCH_TASK --enable-uia
python -m zhixia.notepad_cli show LAUNCH_TASK
python -m zhixia.notepad_cli approve LAUNCH_APPROVAL_ID
python -m zhixia.notepad_cli run LAUNCH_TASK --enable-uia
python -m zhixia.notepad_cli show LAUNCH_TASK
```

首次run只产生审批；核对完整提案后，approve批准启动，第二次run才启动。启动收据必须有真实PID/HWND/进程创建时间、系统Notepad exe、设备及adapter版本。第二阶段另建输入任务，不复用启动审批：

```powershell
python -m zhixia.notepad_cli prepare-input LAUNCH_TASK
python -m zhixia.notepad_cli run INPUT_TASK --enable-uia
python -m zhixia.notepad_cli show INPUT_TASK
python -m zhixia.notepad_cli approve INPUT_APPROVAL_ID
python -m zhixia.notepad_cli run INPUT_TASK --enable-uia
python -m zhixia.notepad_cli run INPUT_TASK --enable-uia
python -m zhixia.notepad_cli show INPUT_TASK
```

第二阶段审批绑定窗口/PID/进程创建时间、完整输入、保存路径、设备、adapter版本、精确菜单/对话框标签、禁止覆盖及UTF-8编码。首次执行仅走UIA Value.SetValue → 定向File菜单Expand → 定向Save As Invoke → 绑定所有者的Save As对话框Value/Invoke，不使用Ctrl+S、全局键鼠、剪贴板或菜单模糊匹配。后一次run独立读取落盘文件，需同时存在durable UIA调用收据，才产生带`real_windows_acceptance=true`的验收证据。

保存目标为`.runtime/notepad/workspace/hello.txt`，必须事先不存在。子目录须由操作员事先准备；已有文件、焦点/控件异常、UTF-8字节不符均不报成功。只读恢复可以检查文件，但GUI动作收据丢失时文件本身不能证明UIA链路，仍留待人工核查。

取消/未知结果处理：

```powershell
python -m zhixia.notepad_cli cancel INPUT_TASK
python -m zhixia.notepad_cli recover INPUT_TASK --worker-stopped
python -m zhixia.notepad_cli reconcile INPUT_TASK
```

recover只在确认旧worker退出后运行。不会自动关闭记事本、清除文本、覆写文件或重放GUI动作；放弃用abandon，残留副作用仍由操作员核查。测试用注入backend收据明确标contract_test，不允许作为真机PASS。
