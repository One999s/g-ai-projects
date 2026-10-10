# 成熟组件复用与许可证审计

审计时间：2026-10-10 UTC。通过官方仓库元数据/固定 SHA 文件及官方文档只读检查，没有安装或运行上游组件。这是工程筛查，不是完整法律意见、完整依赖 SBOM 或安全审计。下列为当次读取锚点，后续升级应重新固定与检查。

## Codex

固定官方 main：`806d9732c974bc8a51b8317c1bd8985544fe627c`。

- [LICENSE](https://github.com/openai/codex/blob/806d9732c974bc8a51b8317c1bd8985544fe627c/LICENSE)：Apache-2.0
- [NOTICE](https://github.com/openai/codex/blob/806d9732c974bc8a51b8317c1bd8985544fe627c/NOTICE)：OpenAI 与 Ratatui/MIT 等归属
- [真实 loop](https://github.com/openai/codex/blob/806d9732c974bc8a51b8317c1bd8985544fe627c/codex-rs/core/src/session/turn.rs)：采样、工具结果、后续 turn；旧 `core/src/codex.rs` 路径已不适用
- [工具编排](https://github.com/openai/codex/blob/806d9732c974bc8a51b8317c1bd8985544fe627c/codex-rs/core/src/tools/orchestrator.rs)：approval → sandbox → attempt → 受规则约束的 retry
- [MCP](https://github.com/openai/codex/blob/806d9732c974bc8a51b8317c1bd8985544fe627c/codex-rs/codex-mcp/src/lib.rs)：已经拆分；不沿用旧 MCP 源码路径
- [app-server wire](https://github.com/openai/codex/blob/806d9732c974bc8a51b8317c1bd8985544fe627c/codex-rs/app-server-protocol/src/protocol/common.rs)：初始化、线程/turn、审批请求、工具调用和中断
- [模型 provider](https://github.com/openai/codex/blob/806d9732c974bc8a51b8317c1bd8985544fe627c/codex-rs/model-provider-info/src/lib.rs)：此版本 WireApi 仅 Responses，已移除旧 chat / ollama-chat；不能把任意兼容 API 当作即插即用
- [恢复](https://github.com/openai/codex/blob/806d9732c974bc8a51b8317c1bd8985544fe627c/codex-rs/core/src/session/rollout_reconstruction.rs)：重建历史/checkpoint 不等于恢复外部事务
- [runtime reset 修复](https://github.com/openai/codex/commit/806d9732c974bc8a51b8317c1bd8985544fe627c)：内存值和 running cells 可丢失，旧工具可能已经生效；知夏必须持有独立副作用账本，未知结果先核对，不能盲目重放

[官方 app-server 文档](https://developers.openai.com/codex/app-server/) 是滚动文档，访问时明确 app-server 命令和 WebSocket 实验性、不支持生产 workload；网页仍有固定源码已不存在的 thread/rollback，故按安装版本生成 schema，做契约测试。默认本地 stdio，不启 experimentalApi、不监听公网。不要直接依赖内部 Rust crate。

Windows 原生支持已存在，[官方 Windows 文档](https://developers.openai.com/codex/windows/) 推荐 Windows 11、近期 Windows 10 为 best effort；不是“只能 WSL”。[MXC 说明](https://github.com/openai/codex/blob/806d9732c974bc8a51b8317c1bd8985544fe627c/codex-rs/mxc-sandbox/README.md) 要实际能力探测，不能仅凭 OS 版本。完整桌面执行器、托管浏览器或远端服务端不能因为有 Computer Use 配置就判定已完整开源。

决策：独立进程/协议 adapter 优先；只有协议、MCP、配置确实不能满足已验证核心能力且有维护预算时才考虑最小 fork。本轮无复制、分发、安装 Codex 代码。

## Windows / 浏览器组件

| 组件 | 固定读取版本与许可 | 适用边界 / 决策 |
| --- | --- | --- |
| Microsoft UFO | [a795552d976c4c019d7c2f778a0effb5cef7de6b / MIT](https://github.com/microsoft/UFO/blob/a795552d976c4c019d7c2f778a0effb5cef7de6b/LICENSE) | 当前主仓已发展至 UFO³；单 Windows UFO² 路线采用 UIA/Win32/COM。固定 README/requirements 确认 Windows≥10、Python≥3.10、pywinauto==0.6.8，PiP 仍 coming soon。参考执行接口，不能称已有可直接复用的强沙箱 |
| Playwright | [d9f2fd3e2232ace8e317a84eda6cb86e25bcf49c / Apache-2.0](https://github.com/microsoft/playwright/blob/d9f2fd3e2232ace8e317a84eda6cb86e25bcf49c/LICENSE) | 推荐第二个 BrowserAdapter；DOM locator/auto-wait，结构化动作不必依赖 LLM。不能操控原生记事本 |
| Browser Use | [c75e8476e26d18b7617643bc2ae082fae8eae431 / MIT](https://github.com/browser-use/browser-use/blob/c75e8476e26d18b7617643bc2ae082fae8eae431/LICENSE) | 固定 pyproject 版本 0.13.11、Python≥3.11。其 Bash 集成要求 /bin/bash，Windows 需 WSL，不是原生桌面能力。作为后续可选规划层，不能绕内核审批 |
| OpenAdapt | [7ae7ebc02048f41dd820ad826d3e78c4dcce2cd2 / MIT](https://github.com/OpenAdaptAI/OpenAdapt/blob/7ae7ebc02048f41dd820ad826d3e78c4dcce2cd2/LICENSE) | 读取的 README 为 launcher，compiler/runtime 在 openadapt-flow；后者及传递依赖未审，不引入 |
| pywinauto | [官方许可证 / BSD-3-Clause](https://raw.githubusercontent.com/pywinauto/pywinauto/master/LICENSE) | 本轮只核滚动 master 许可及 0.6.8 文档，未固定包下载/依赖；进入 Phase1 必须锁定版本、校验分发与完整依赖后安装 |

额外官方依据（滚动文档，访问时间同上）：

- [pywinauto 入门](https://pywinauto.readthedocs.io/en/latest/getting_started.html)：`backend='uia'`、Notepad 示例、控件检查和等待；旧示例不保证新版 Win11 控件树
- [UIA 安全](https://learn.microsoft.com/en-us/windows/win32/winauto/uiauto-securityoverview)：普通同完整性应用无需管理员，不能绕 UAC 或给默认 uiAccess
- [Playwright locators](https://playwright.dev/docs/locators)
- [Browser Use 参数](https://docs.browser-use.com/open-source/customize/browser/all-parameters)：文档默认含剪贴板/通知权限、扩展、下载。未来明确 permissions=[]、禁默认扩展与下载、独立 profile 和域名 allowlist；此处是文档证据，未逐行审固定源码实现

## 分发门槛

MIT：保留版权及许可；Apache-2.0：附许可、保留适用 NOTICE/归属、标记改动；BSD-3-Clause：保留条件/免责声明、不得借名背书。上游主仓许可不覆盖全部传递依赖、浏览器二进制、模型权重、商标、云服务或账户使用权。

Phase0 不 vendoring 上游源码，也不为项目擅自选择新开源许可。真正打包前必须决定本项目许可、生成 SBOM、检查每一分发件及其 NOTICE，锁定版本与哈希后再做升级回归。
