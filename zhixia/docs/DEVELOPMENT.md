# 开发规范

## 代码与分支

- 项目根是 `zhixia/`。独立分支开发，不修改其他项目、main、权限、可见性或历史
- 小步普通提交；提交前 `git diff --check`、unittest、CLI smoke；推送后核远端 SHA。禁止强推
- 保留成熟组件原始边界，依赖新增必须有固定版本/来源/许可、必要锁文件与安全验收，不为测试便利引入未知软件
- Python 3.11+；标准库优先、类型标注、结构化异常；不要捕获失败后返回假成功
- 不提交数据库、.env、密钥、个人数据、node_modules、模型缓存、录音或备份。核心没有外部 runtime dependencies；可选Windows候选组合须使用独立固定版本/哈希文件，不混入核心依赖

## 必须保持的不变量

1. 任何副作用之前先提交 operation intent；审批消费与领取操作同一 SQLite 事务
2. 审批绑定动作、任务、步骤、工作区和策略版本；修改参数、过期、已消费均不可复用
3. 未知结果不自动重放；恢复先核对，取消不宣称回滚
4. 工具与参数 fail-closed；模型不能通过路径、shell 或未知 adapter 绕审批
5. 验证实际结果而非返回码；为成功、拒绝、过期、崩溃、重复执行和取消编写测试
6. API 密钥/隐藏推理不进入状态或日志；异常仅保存类型，不存可能含秘密的原始错误
7. 多进程只通过持久账本领取工作；`recover` 目前是操作员确认旧进程退出后才允许的维护操作，不自动抢占活跃 worker
8. 协作式路径策略不宣传为 OS 强沙箱；真实 desktop 权限必须先完成进程隔离/Windows 验收

## 本地检查

```sh
cd zhixia
python -m unittest discover -s tests -v
python -m compileall -q zhixia tests
python -m zhixia --help
```

从仓库根再运行 `git diff --check`。不要只看测试退出码，确认测试数量非零。当前没有新增 GitHub Actions workflow（本轮变更限于项目目录）；Windows CI/GUI runner 与打包签名归后续 Issue。可运行 CLI 不等于构建/安装包完成。

## 演进流程

- Schema 变更先设计迁移/备份/回滚，未知 schema 版本拒绝打开，不能静默覆盖
- 新 adapter 先离线 schema/fixture、错误/限流/取消测试，再在明确授权环境做有限 live 验收
- 高风险工具必须独立政策与审批，不复用低风险读权限
- 发布前提供测试矩阵、未验项目、升级说明、依赖 SBOM、许可证/NOTICE 和默认权限清单
