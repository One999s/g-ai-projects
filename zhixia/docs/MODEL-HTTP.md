# 首个模型 HTTP 适配：使用与验收边界

当前实现：OpenAI-compatible Chat Completions、非流式、JSON-object窄子集。已通过本机HTTP fixture测试，真实厂商与模型均NOT_RUN。没有调用任何计费API。

## 结构

- chat_http.py：固定端点、Bearer内存凭据、单次HTTP、超时/响应上限、严格JSON/usage/模型及动作校验
- model_planner.py：持久模型选择、调用预留/结算/未知状态、显式摘要、原Kernel待审批计划
- model_cli.py：显式网络启用，无API key命令行参数
- examples/chat-config.example.json：公开配置模板，默认预算0、无真实端点，不能直接产生计费调用

核心仍零第三方运行依赖。旧FakeProvider和LiveProviderConfig仅为原Phase0合同；本轮真实HTTP实现走独立ChatConfig/ModelPlanner，不把其余厂商配置校验升级宣称为真实可用adapter。

## 不联网检查与离线回归

```sh
python -m unittest discover -s tests -v
python -m zhixia.model_cli --help
```

测试中启动的HTTP server仅绑定127.0.0.1，所有模型输出、usage和密钥均为fixture数据。这些测试的“HTTP成功”不是“真实模型成功”。

## 未来获得明确服务/预算授权后的操作方式

此说明不是本轮发起真实请求的授权。操作者需选择自己有权使用的具体端点、具体模型、核实报价/支持参数及允许传出的任务正文，准备公开配置；不得把API key值写进JSON。用受信运行环境/密钥管理设置配置中api_key_env所指的变量，不在聊天或CLI实参粘贴密钥。不要把敏感资料或凭据放入任务text，CLI正文还可能进入shell历史。

从项目`zhixia/`执行（以下public-config.json由操作者准备）：

```sh
python -m zhixia.model_cli --config public-config.json check
python -m zhixia.model_cli --config public-config.json status
python -m zhixia.model_cli --config public-config.json select configured-model-b
```

check只检查配置/凭据存在，不请求端点；select只改未来模型选择，已有计划保持不变。需要真实发出一次请求时必须显式加flag：

```sh
python -m zhixia.model_cli --config public-config.json plan --path note.txt --text "本次允许发送的正文" --enable-model-http
```

这会发送可见任务摘要、指定路径和全文到配置端点，可能计费。返回call_id及task_id，只是待审批提案，不写文件。之后仍须逐步：

```sh
python -m zhixia tick TASK_ID
python -m zhixia show TASK_ID
python -m zhixia approve APPROVAL_ID
python -m zhixia tick TASK_ID
python -m zhixia tick TASK_ID
```

所有命令须使用同一home（默认.runtime）；若指定其他home，两套CLI都要传相同路径。不能把HTTP提案直接用于Notepad数据库，桌面权限没有因此开放。

`--context-task EXISTING_TASK`只导出该任务显式摘要字段，仍为新规划创建新task；没有自动迁移旧审批/隐藏会话状态。

## 配置合同

- base_url固定`https://明确配置的host/v1`；无userinfo/query/fragment；不跟随redirect，不自动用系统代理
- loopback HTTP默认关闭，仅显式allow_loopback_http=true后允许localhost/127.0.0.1/::1；不是任何私网地址自动获准
- allowed_models逐项给公开模型名与输入/输出每token的micro-USD报价；示例数字只是占位，非厂商价格
- total_budget_microusd是持久预算上限；max_calls独立限制总调用数。配置变化需显式迁移，不能靠改配置重置历史
- input_token_reservation是预留计量；max_completion_tokens是请求输出上限；本地字节门控不是准确tokenizer。不能据此保证远端账单绝不会超过预留
- completion_limit_field明确为max_completion_tokens或旧max_tokens；请求不自动尝试两种参数
- 本子集要求响应model与所选具体标识相同，供应商alias展开尚未适配；不要承诺任意别名可用
- 不支持stream=true、函数调用、图像、自动工具执行或任意自然语言任务规划

## 取消、未知与账务

```sh
python -m zhixia.model_cli --config public-config.json cancel CALL_ID
python -m zhixia.model_cli --config public-config.json recover CALL_ID --worker-stopped
python -m zhixia.model_cli --config public-config.json status
```

recover只在旧worker已停止时使用。发送后结果未知时，完整预留继续占用并阻断新调用；不自动重试、不自动换模型、不捏造0 token。缺usage可保留已生成的待审批计划，但财务状态仍unknown。已知usage即使输出被拒绝也记账；超预留实际报告不截断，阻断后续调用。费用字段按配置报价计算，不是供应商账单凭证。

现无自动账单查询或解除未知费用的CLI，需后续凭实际供应商证据实现明确核对。不能通过删除账本/换home来声称原调用没有成本。当前账本是单用户本地策略，不是对恶意OS用户的计费或多租户强隔离。

## 官方合同依据（2026-10-10只读核验）

- [OpenAI Chat create](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)：usage可选；max_tokens已弃用且不适用于o-series；max_completion_tokens含reasoning tokens
- [JSON mode](https://developers.openai.com/api/docs/guides/structured-outputs#json-mode)：JSON语法不保证业务schema，提示中仍须要求JSON
- [Responses迁移](https://developers.openai.com/api/docs/guides/migrate-to-responses)：Responses与Chat字段/端点不同，Codex已审版本只支持Responses不证明本adapter兼容
- [Claude兼容限制](https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk)：兼容层明确忽略response_format；不能宣称本类覆盖Claude
- [Grok结构化输出](https://docs.x.ai/developers/model-capabilities/text/structured-outputs)：字段支持不等于全部模型/鉴权/计量验收

官方资料是滚动文档，未替代真实厂商按版本的授权验收。流式、app-server、其他厂商profile留待后续，不在本轮堆空接口。
