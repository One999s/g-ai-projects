# Windows 可选依赖：固定分发与兼容性边界

审计：2026-10-10。只读访问官方来源，下载公开包到内存重算SHA256/读取静态元数据；没有安装包、运行导入或操作桌面。本目录的 requirements 文件是**候选锁定组合**，不代表组合兼容已通过、最新版本建议或完整漏洞审计。

## pywinauto 0.6.8

[官方tag API](https://api.github.com/repos/pywinauto/pywinauto/git/ref/tags/0.6.8) 实读指向 commit `ff95296e42c16beaf1f8b94252276cb664a4e47e`。

- [固定LICENSE](https://github.com/pywinauto/pywinauto/blob/ff95296e42c16beaf1f8b94252276cb664a4e47e/LICENSE)：BSD-3-Clause，Copyright (c) 2017, Mark Mc Mahon and Contributors
- [PyPI 0.6.8](https://pypi.org/pypi/pywinauto/0.6.8/json)：wheel `pywinauto-0.6.8-py2.py3-none-any.whl`（362903 bytes）SHA256 `931ce622d7f402b1892ab472987a1332e4c0681bf87e106f798390d16ca95e58`
- sdist `pywinauto-0.6.8.tar.gz`（431976 bytes）SHA256 `de23f1e977cc51e7eddd95c8f365710343136433968d1e2ad377962d6bd6540a`
- 两者均包含LICENSE，许可文件SHA256相同：`18a999a95b7b23d86c410b4534e44d69f6d8ce13c3a53719b06ab895d8a2e73a`

[固定setup.py](https://github.com/pywinauto/pywinauto/blob/ff95296e42c16beaf1f8b94252276cb664a4e47e/setup.py) 在Windows依赖six/comtypes，缺win32api时加pywin32；非Windows为six/python-xlib。发布wheel元数据无条件列six/comtypes/pywin32，故不能将none-any理解为可随意装入Linux。文本UIA路径无需可选Pillow，不引入文档工具链。

没有 Requires-Python。Readme声称2.7/3.3+，但固定classifiers和AppVeyor矩阵只覆盖2.7/3.5/3.6/3.7；不能因此声称3.11已验证。tag与PyPI的全部源码未逐文件对照，已核的是tag锚点、分发哈希、许可文件和依赖元数据。

## CPython 3.11 x64 候选组合

锁定文件：[optional/windows-cp311-x64-candidate.txt](../optional/windows-cp311-x64-candidate.txt)。所有包添加win32 marker、only-binary与require-hashes。以下分发包均已下载重算哈希，与PyPI吻合：

| 包 | 分发/元数据许可 | SHA256 |
| --- | --- | --- |
| six 1.16.0 | MIT，无运行依赖 | `8abb2f1d86890a2dfb989f9a77cfcfd3e47c2a354b01111771326f8aa26e0254` |
| comtypes 1.2.0 | MIT，无运行依赖；项目说明含Python3.11 | `26f261b1eed6972d5cdaa3af1fadb3fa76fc59877d0a1293835327a76573380d` |
| pywin32 306 | wheel元数据License=PSF，无Requires-Dist；仅cp311-cp311-win_amd64 | `a7639f51c184c0272e93f244eb24dafca9b1855707d94c192d4a0b4c01e1100e` |

来源：[six](https://pypi.org/pypi/six/1.16.0/json)、[comtypes](https://pypi.org/pypi/comtypes/1.2.0/json)、[pywin32](https://pypi.org/pypi/pywin32/306/json)。其中pywin32的PSF为元数据声明，不能当完整包内所有组件许可已审。真正再分发须补依赖LICENSE/NOTICE及SBOM检查，保留BSD条件/免责与归属。

安装前确认受支持环境；其他Python ABI、ARM64或x86不得复用pywin32单文件哈希。项目核心仍3.11+；仅这个候选UIA组合限制为CPython3.11 x64。

## 仅供获授权Windows操作者的可复现命令

当前执行环境不安装这组依赖。确认要在指定Windows本地隔离venv安装后，在`zhixia/`执行：

```powershell
py -3.11 -m venv .venv-uia
.\.venv-uia\Scripts\python.exe -m pip install -r optional/windows-cp311-x64-candidate.txt
.\.venv-uia\Scripts\python.exe -m pip check
.\.venv-uia\Scripts\python.exe -m zhixia.notepad_cli check
```

check只证明平台/包元数据符合候选，不证明import/UIA/记事本成功；失败会明确BLOCKED。环境正常后，需额外按[真机命令](WINDOWS-ACCEPTANCE.md)逐阶段审批，不自动启动程序。

## 固定源码 API 边界补核

对同一commit静态核验，未运行Windows：

- `app.window(handle=...)`内部会跳过process筛选，故适配器动作前另核wrapper PID/HWND、进程创建时间与exe
- `window_text()`在TextPattern异常时可能退回Name，故正文/路径只使用ValuePattern.CurrentValue，不把元素名称当正文
- `set_edit_text()`会将正文记入actions.log；适配器改用直接ValuePattern.SetValue，避免这个额外日志出口
- `type_keys()`是全局键盘，不因先检查前台窗口就变成绑定HWND；已移除，保存只用精确树内的ExpandCollapse.Expand和Invoke.Invoke
- `menu_select()`存在首项/模糊匹配与焦点切换，不使用；未知树或pattern不支持直接停止

源码：[EditWrapper](https://github.com/pywinauto/pywinauto/blob/ff95296e42c16beaf1f8b94252276cb664a4e47e/pywinauto/controls/uia_controls.py)、[UIAWrapper](https://github.com/pywinauto/pywinauto/blob/ff95296e42c16beaf1f8b94252276cb664a4e47e/pywinauto/controls/uiawrapper.py)、[findwindows](https://github.com/pywinauto/pywinauto/blob/ff95296e42c16beaf1f8b94252276cb664a4e47e/pywinauto/findwindows.py)。实际目标Notepad是否提供这些pattern仍NOT_RUN。
