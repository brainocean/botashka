# 从 Obney 的事件建模借鉴到 Botashka：边界清晰的最小方案

## 结论

Obney 的公开技术材料把 **Grain** 定位为 event-sourced application framework，把 **Orc** 定位为协调 AI 与代码的 typed AI workflow engine；其核心主张是由系统强制组件连接规则和可交换的信息，而不是只依赖模型遵守文字指令。[Obney《Our Technology》](https://obney.ai/tech/) Grain 则将这一约束落实为 CQRS 与 Event Sourcing 的受限 DSL/统一架构。[Grain README](https://github.com/ObneyAI/grain)

**对 Botashka 的建议（非 Obney 的事实主张）：** 借用其中最小且已与仓库术语相容的部分——命令/事实/查询的责任分离、从 run event log 派生查询视图、以及受控的 tool registry；不要把一个顺序、短生命周期的本地 Babashka coding agent 直接升级成 Grain 的多租户 event store、后台 processor、投影缓存或 workflow runtime。

## 1. Grain 的命令、查询、事件、读模型与 processor 分工

下表是 Grain 公开文档所定义的模型；它描述的是 Grain 的应用架构事实，不是 Botashka 已经采用的实现。

| Grain 构件 | 官方定义/职责 | 对 Botashka 的可借用解释 |
| --- | --- | --- |
| Command（`defcommand`） | 写侧请求：执行业务决策和不变量，并产生零或多条 event。[Grain README](https://github.com/ObneyAI/grain) [Core Concepts：Commands](https://raw.githubusercontent.com/ObneyAI/grain/main/docs/core-concepts.md) | 将“开始一次 provider 请求”“执行已批准 tool”“取消 run”等作为意图；不能将意图伪装成已发生事实。 |
| Event（`defevent`、`->event`） | 不可变、记录于 event store 的发生事实；`defevent` 可注册 payload schema、文档和 history contract。[Grain README](https://github.com/ObneyAI/grain) | 用于 `:provider-response`、`:tool-result`、`:step-limit`、`:final-answer` 等 run 事实。 |
| Read model（`defreadmodel`） | 对 event stream 的纯 reducer：`(state, event) -> state`，将事实投影为可查询状态。[Core Concepts：Read Models / Projections](https://raw.githubusercontent.com/ObneyAI/grain/main/docs/core-concepts.md) | 从一个 run 的事件向量计算摘要、工具调用列表或最终状态；它不是另一份权威状态。 |
| Query（`defquery`） | 读侧：从 projection 读取，不引起状态变化。[Core Concepts：Queries](https://raw.githubusercontent.com/ObneyAI/grain/main/docs/core-concepts.md) | 为 REPL/未来 UI 提供纯查询，例如“此 run 的失败步骤”；查询不得调用 provider 或 tool。 |
| Todo processor（`defprocessor`） | 订阅关心的 event 并异步反应，通常执行 command；文档还区分 effect 的 before/after checkpoint 语义。[Grain README](https://github.com/ObneyAI/grain) [Core Concepts：Todo Processors](https://raw.githubusercontent.com/ObneyAI/grain/main/docs/core-concepts.md) | **现在不借用运行时机制。** 它适合需要异步重试、跨节点接管或持续后台自动化的系统，而非单一线性 run。 |
| Periodic task（`defperiodic`） | 按计划发出 trigger event，并由独立 processor 处理；多节点情形借 CAS 去重。[Core Concepts：Periodic Tasks](https://raw.githubusercontent.com/ObneyAI/grain/main/docs/core-concepts.md) | **现在拒绝。** 当前 agent 没有定时领域作业或集群去重需求。 |

Grain 的 Event Model 文档进一步把 command、event、read model、query、todo processor、periodic task、screen 和它们的连线登记为服务拓扑，并可在启动前把模型与 live registries 作校验。[Grain Event Model](https://raw.githubusercontent.com/ObneyAI/grain/main/docs/event-model.md) 这说明其“模型”不仅是事件格式，也包括受允许的组件关系。

### 对齐到 Botashka 的既有模型

Botashka 已定义 `Run event log` 为**一个 Agent run 的权威、有序、不可变执行事实序列**，并明确它不是 provider conversation、文本日志或 telemetry trace；`Observation projection` 只是从事实产生的非权威渲染/导出。[`CONTEXT.md`](../CONTEXT.md) 因此两者最自然的对应是：

```text
Botashka command / 外部输入
        -> 纯 run transition
        -> canonical run event log
        -> pure read-model-like summaries / queries
        -> terminal renderer 或未来 observation sink
```

这是**建议**：保持 run event log 为唯一事实源，令读取它的摘要和显示都是派生物。它与 Grain 的 event-to-projection 思路相容，但不要求把 Botashka 变为完整 CQRS 服务。

## 2. event-store 抽象：可学的接口边界，不应照搬的运行规模

Grain 的公开包说明列出可替换的 in-memory、SQLite 和 Postgres event-store 实现：`grain-core-v2` 含 in-memory store；SQLite 后端面向嵌入式单进程；Postgres 后端含多租户隔离、RLS 与 advisory locks。README 称可从 in-memory 起步，再切换 SQLite 或 Postgres。[Grain README：Packages 与 Multi-Tenancy](https://github.com/ObneyAI/grain) Grain 的 Core Concepts 还规定每次 store 操作都有 `:tenant-id`，read-model processor 负责增量投影、缓存和租户隔离。[Core Concepts](https://raw.githubusercontent.com/ObneyAI/grain/main/docs/core-concepts.md)

**可借用（建议）：** 让“append/run facts”和“如何保存或读取它们”分开。当前的 run-local immutable event vector 已经满足最小 event-store 的语义核：有序追加、可重放、可派生视图。未来真正需要跨 REPL 存活、恢复 run 或保留审计证据时，再以 `Run store` 作为显式生命周期组件引入文件/SQLite 等后端；仓库也以此作为引入时机。[`CONTEXT.md`](../CONTEXT.md)

**延后/拒绝（建议）：**

- 不提前加入 `:tenant-id`、多租户 RLS、distributed coordination、lease、CAS checkpoint 或 Postgres；它们解决的是 Grain 已明确支持的多实例部署问题，不是当前单 run 的问题。[Grain README](https://github.com/ObneyAI/grain)
- 不把 projection cache、LMDB L2、watermark、分段/分区投影带入当前 CLI。它们是 Grain read-model processor 的性能/重启优化，而非“从 event vector 得到摘要”的先决条件。[Core Concepts：Read Models / Projections](https://raw.githubusercontent.com/ObneyAI/grain/main/docs/core-concepts.md)
- 不声称内存 event vector 已提供崩溃恢复或外部 effect 的 exactly-once；它只提供当前进程内的规范事实。持久化协议应在真实需求出现时单独设计。

## 3. registry 与 code-agent tools：借“发现和验证”，不借“远程治理”

Grain 的 `grain-code-agent-tools` 被官方说明为 **dev-only nREPL tools**：coding agent 可把 live app 的 command、query、read model、processor、periodic trigger、schema、projection、event 与 runtime diagnostics 以 EDN 检查，并在调用 command/query 前用 schema registry 验证 payload。[Grain README：Code Agent Tools](https://github.com/ObneyAI/grain) 官方代码库的工具说明将推荐步骤列为 catalog 发现名称、`explain-schema` 查看形状、`validate` 验证候选 payload、验证成功后才 invoke；它也列出 `events`、`projection`、`diagnostics` 与 event-model validation 等操作。[Grain Code Agent Tools](https://raw.githubusercontent.com/ObneyAI/grain/main/docs/code-agent-tools.md)

**对 Botashka 的建议：**

1. 保持现有 `Tool registry` 的角色：它提供某 run 可用 capability 的 provider schema 和 host handler，**不**决定是否获准执行。[`CONTEXT.md`](../CONTEXT.md)
2. 当工具数或可调用领域面确实增长时，添加一个本地、只读的 catalog/describe/validate seam：列受控 tool 名称与 schema，解释参数形状，在 host 执行前验证。这是借用 Grain “让 agent 读取真实注册面而非猜源码”的收益。
3. 仍由 host 的授权、workspace 边界、超时和控制流决定执行；provider 的 tool call 只是提议，而 tool result 才是 host 已执行后的事实。[`CONTEXT.md`](../CONTEXT.md)

**现在拒绝：** 将 nREPL live-app inspector、应用 command/query registry 或全量 event-model boot validation 当作本地 coding agent 的必需依赖。Botashka 的工具注册表是 run input、agent run 是 ephemeral data/control flow，仓库明确把它们排除在当前 lifecycle-managed component 之外。[`CONTEXT.md`](../CONTEXT.md)

### 关于所给 slides 中的独立 Agent Registry

所给材料中的 [Agent Registry slides](https://agentoperations.github.io/agent-registry/presentation.html) 与其 [官方仓库](https://github.com/agentoperations/agent-registry) 说明：registry 保存 agent、skill、MCP server 的 metadata、evaluation signal、promotion history、BOM/dependency 与 provenance；二进制内容仍留在 OCI registry；生命周期示例为 `draft -> evaluated -> approved -> published`。slides 还将 standard document（identity）、registry（governance）和 OCI registry（content/payload）分开。[Agent Registry slides](https://agentoperations.github.io/agent-registry/presentation.html)

这是**独立项目的第一方材料**，仓库组织是 `agentoperations`；Obney 技术页直接链接的是 Grain 和 Orc，未把该 registry 明示为 Obney 组件。[Obney《Our Technology》](https://obney.ai/tech/) 因而不应把它写成 Obney 产品事实。

对 Botashka，以上 registry 的可借用点仅是**概念性**的 artifact identity/provenance 与受控 promotion；现在应拒绝 registry server、OCI 集成、外部 evaluation pipeline、审批状态机和跨团队发布治理。它们与一次本地 run 的 event log 是不同层次：前者治理可分发产物，后者记录本次执行事实。

## 4. Orc 的工作流与单顺序 agent 的分界

Obney 将 Orc 定位为 typed AI workflow engine；其公开 README 说明 workflow 用 behavior tree 组织 `llm`、`code`、`condition`、`delegate` 等 node，node 声明读写的 typed blackboard values，执行以 Grain immutable event 记录，并以 read model 供查询分析。[Orc README](https://github.com/ObneyAI/orc) 技术页的描述则是 Orc 定义步骤并在线程般的 workflow 中传递信息。[Obney《Our Technology》](https://obney.ai/tech/)

**建议：不要把 Orc/行为树移植为当前 Botashka 的 agent loop。** 当前已有清晰的顺序控制流；仓库原则也要求仅在 ordering、聚合、取消、错误与 trace 语义定义后才并行，并将 workflow engine 的引入条件限定在 specialization、routing、parallelism 或 rollback 已无法保持为可读顺序 run 时。[`CONTEXT.md`](../CONTEXT.md) 因此可借用的是“步骤显式、输入输出可检查、事实可追溯”的纪律，而非 delegate tree、共享 blackboard 或并发 workflow runtime。

## 分阶段建议（不含实现）

1. **现在：收敛事实边界。** 继续将单个 `Agent run` 的 immutable、有序 run event log 作为 canonical source；区分 command/tool proposal、host effect、tool result/event 与 terminal result。为诊断只从该 log 计算纯摘要。
2. **出现第二个本地消费者时：增加最小查询与 catalog seam。** 只在 REPL、renderer 或本地 UI 需要稳定读取时，定义纯 run query/read-model 函数；只在 tool 数增长且模型确实需要发现/校验时，公开本地 tool catalog、schema explain 与 validation。
3. **出现跨进程保存/恢复的明确需求时：设计 Run store。** 先定义 durable append、恢复、失败与外部 effect 的语义，再选择嵌入式后端；不要以“未来可扩展”为由预先部署 Grain 级多租户 store。
4. **只有顺序 run 不再可读时：评估 workflow orchestration。** 当有已定义的独立节点、取消/聚合/rollback 规则和真实 delegation 或并行需求后，才评估 Orc 类 typed workflow、processor 或 component lifecycle；在此之前保持单一顺序 agent。
5. **只有要分发和治理 agent/skill/MCP 产物时：评估外部 registry。** 将它与 run event log 保持分离，并以实际的发布、评估、审批和 provenance 需求为触发条件。