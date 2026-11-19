# Botashka 领域概念与实体重新设计（Domain Model Redesign）

> **状态**：方案建议稿（草案），供审阅。**未提交（Do not commit）**。
> **目标**：彻底废除 `Run` 历史包袱，基于 **持续反应式流（Reactive Stream）+ 任务（Task）+ 函数式核心（FC/IS）** 重构系统概念基石，作为后续修订 `CONTEXT.md` 的蓝本。

---

## 一、 为什么必须彻底告别 “Run”？

在过去的设计中，`Run` 被定义为“响应一次用户 Prompt 的有界执行尝试”。但在真实的工程落地和人机交互中，这一概念带来了持续的认知摩擦：

1. **违背反应式本质**：`Run` 暗示着一个在 CPU 上同步跑完的单次脚本，但现代 Coding Agent 的常态是**事件驱动与异步挂起**（等待大模型网络返回、等待人工审批 5 分钟、等待后台子进程完成、处理多轮追问）。
2. **割裂了长程会话与状态**：把用户的多轮追问强行拆成“多个独立的 Run”，导致状态继承、上下文压缩和工作区资源锁的归属极为尴尬。
3. **将 RPC 抽象强加于事件流**：`Run` 是为了让异步事件循环看起来像一个同步函数调用 `result = agent/run!` 而发明的妥协产物。

**核心结论**：我们不需要 `Run`。
Agent 的本质是一个**持续存活的反应式控制系统（Reactive Actor / System）**。它监听外部事件，演进认知状态，做出工具调用决策，施加外部影响。

---

## 二、 全新领域模型总览（The New Ontology）

系统由 **5 个核心概念** 构成，严格遵循 **FC/IS（函数式核心与命令式外壳）** 分层：

```
                    ┌────────────────────────────────────────────────────────┐
                    │               Functional Core (纯核 / 纯数据)           │
                    │                                                        │
                    │  1. Event Log (事实全集) ──► 2. State / Worldview (认知状态) │
                    │                                 │ (包含当前 Tasks)       │
                    │                                 ▼                      │
                    │                     3. Decision / Intent (动作意图)     │
                    │                        (统称为工具调用，含 LLM / Bash)   │
                    └─────────────────────────────┬──────────────────────────┘
                                                  │ (纯意图数据)
                                                  ▼
                    ┌────────────────────────────────────────────────────────┐
                    │               Imperative Shell (外壳 / 副作用)          │
                    │                                                        │
                    │  4. Effect Runners: 执行物理操作 (发 HTTP, 跑 Bash, 弹窗) │
                    │                     产生新的外部刺激                      │
                    │                                                        │
                    │  5. Ingestion & Loop: 监听外部物理世界 (用户输入, 定时器, │
                    │                       工具输出)，封装为 Event 送入纯核   │
                    └────────────────────────────────────────────────────────┘
```

---

## 三、 核心概念与实体详细定义

### 1. Agent（智能体系统 / 反应式进程）
* **定义**：长期存活、事件驱动的计算主体。它拥有唯一的运行环境、可用的工具能力（Capabilities）、事件分发循环与不可变的全局认知状态。
* **职责**：
  * 作为 Imperative Shell 的主载体，维持事件循环；
  * 管理外部物理资源连接（LLM 客户端、文件系统根目录、总线广播通道）；
  * 它不与任何单次具体任务绑定，理论上可以永久监听外部世界的事件。

### 2. Event & Event Log（事件与事实日志）
* **Event（事件 / 事实）**：客观物理世界中**已经发生、不可篡改的事实记录**。
  * **来源平等**：无论是用户敲下回车、大模型返回文字、Bash 工具执行结束、定时器滴答、还是人类在浏览器中点击了“批准”，在地位上都是平等的事件。
  * **数据契约**：单调递增的序号 `:seq`、发生时间戳 `:at`、事件类型 `:type`、携带的事实载荷 `:data`、关联的任务 `:task-id`（可选）。
* **Event Log（事实账本）**：按时间先后顺序严格追加的不可变事件全集。它是系统**唯一的真实数据源（Single Source of Truth）**与审计凭证。

### 3. Task（任务 / 业务目标）
* **定义**：由人类用户或系统主动确立的**高阶业务目标**（如“修复 issue #42”、“重构认证模块”、“巡检工作区”）。
* **属性与边界**：
  * **标识与目标**：全局唯一的 `:task-id`、人类意图描述、拆解出的计划/Checklist；
  * **生命周期状态机**：`:created` $\to$ `:active` $\leftrightarrow$ `:awaiting-approval` $\to$ `:completed` / `:failed`；
  * **预算与安全边界**：最大允许轮次（`:max-turns`）、超时时限、只读/写权限范围；
  * **资源排他性（锁）**：针对工作区（Workspace）的修改型任务持有写锁，同一工作区同一时刻只允许一个活跃的变更任务，天然避免物理竞态。

### 4. State / Worldview（认知状态 / 世界观）
* **定义**：纯核内部对当前系统与世界的**全部认知**，是一个纯粹不可变的 Clojure Map。
* **演进规则**：
  * **纯 Reducer**：`(evolve state event) -> new-state`；
  * 状态是事件日志从零开始重放（Replay）的必然确定性结果。
* **包含内容**：
  * 当前存在的任务集合 `{:tasks {"task-1" task-data}}`；
  * 当前活跃任务 ID `{:active-task-id "task-1"}`；
  * 注册的工具纯声明元数据 `{:tools-spec [...]}`（不含可执行代码）；
  * 派生索引与运行时缓存。

### 5. Decision / Intent（决策 / 动作意图）
* **定义**：纯核根据当前 State 做出的、准备对外部物理世界施加影响的**纯意图描述**。
* **泛化工具观（All actions are Tool Invocations）**：
  * 无论动作多么复杂，均统一抽象为对某个能力的调用意图：
    * 想写文件 $\to$ `{:tool :file/write, :path "...", :content "..."}`；
    * 想深度思考 $\to$ `{:tool :llm/generate, :context [...]}`；
    * 想快速反射 $\to$ `{:tool :decision-model/predict, :features [...]}`；
    * 想请求人类确认 $\to$ `{:tool :ui/request-approval, :reason "..."}`；
    * 暂无行动（等待外界刺激） $\to$ `nil`。
* **纯度保证**：它只是一张请示单，**不包含执行函数、不包含网络连接、不发生物理改变**。

### 6. Effect（副作用执行）
* **定义**：Imperative Shell 接收到纯核的 Decision 意图后，向物理世界施加的真实操作。
* **执行闭环**：
  * 外壳调用真实 API、拉起子进程、发送 WebSocket；
  * 操作完成后，外壳**必须将客观结果或捕获的异常包装为新的 Event**，交由事件网关送回纯核。

### 7. Context Projection（上下文投影 / 订阅）
* **定义**：针对特定受众（大模型、Clef、Web 界面、CLI 终端），从完整的 State/Event Log 中计算出的**纯函数视窗**。
* **核心原则**：
  * **Context 绝对不是 State 本身**！
  * 大模型有 Token 限制和 OpenAI 消息格式要求 $\to$ `(project-llm-context state task-id)`；
  * Clef 分类模型只需要紧凑的意图特征 $\to$ `(project-clef-context state task-id)`；
  * 终端只需要打印时间线 $\to$ `(project-trace-graph state task-id)`。
  * 对上下文进行压缩、修剪或滑动窗口折叠，**纯粹是更换投影算法，绝不破坏底层完整的事件账本**。

---

## 四、 新旧概念对照表（Glossary Mapping）

| 旧概念（`CONTEXT.md` 现状） | 存在的问题 | 新概念（重构后） | 改进理由 |
|---|---|---|---|
| **Agent run** | 强行将异步流包装为单次脚本运行；挂起与并发语义混乱 | **Task（任务）** 与 **Agent（系统）** 彻底分立 | Task 负责宏观目标与生命周期；Agent 负责长程反应式事件循环。彻底干掉 “Run”。 |
| **Run state** | 与单次 run 耦合，旧代码中混入了可执行闭包 | **State / Worldview（认知状态）** | 全局不可变数据快照，纯数据，无执行把柄，可通过事件流全量重放。 |
| **Run event log** | 依附于单个 run | **Event Log（事实账本）** | 长期连续的事实流，通过 `:task-id` 关联具体任务。 |
| **Command** | 概念含糊，与外部 Linux 命令容易混淆 | **Decision / Tool Call Intent（决策意图）** | 明确所有动作本质上都是工具调用意图（包括调用大模型）。 |
| **Command executor** | 庞大杂糅的适配器，容易绕过授权 | **Effect Runners（效应执行器集）** | 细粒度的外壳执行器（按工具类型分发），外壳统一强制执行授权检查。 |
| **Conversation** | 容易被误当成底层状态来修改 | **LLM Context Projection（模型视窗投影）** | 明确它是从 State 投射给大模型看的派生数据，不是状态本身。 |

---

## 五、 典型交互场景在全新模型下的运转演示

### 场景 1：用户提问并自动调用工具（标准问答）
1. 用户在终端敲入 Prompt $\to$ 外壳产生 `{:type :user/prompt, :task-id "t1", :text "查看 README"}` 事件并投递；
2. 纯核：
   * 创建任务 `t1`，状态为 `:active`；
   * 决策：调用大模型推理，产出意图 `{:effect :call-llm, :task-id "t1", :context (project-llm state "t1")}`；
3. 外壳调用大模型 HTTP 接口，模型返回“提议调用 `read_file`”；
4. 外壳将大模型响应封装为 `{:type :llm/responded, :task-id "t1", :tool-calls [...]}` 事件并投递；
5. 纯核：接收事件，推演状态，产出新意图 `{:effect :run-tool, :tool "read_file", :args {:path "README.md"}}`；
6. 外壳拉起 Babashka 执行工具，产出 `{:type :tool/result, :task-id "t1", :data "..."}` 事件；
7. 纯核：再次决策调用大模型总结结果；
8. 大模型返回最终文本回答 $\to$ 纯核将 `t1` 状态标记为 `:completed`。

### 场景 2：破坏性操作与人机长程审批（等待 5 分钟）
1. 模型提议删除目录 `rm -rf /tmp/test`；
2. 纯核授权检查：匹配到敏感工具 $\to$ 决策意图为 `{:effect :request-approval, :task-id "t1", :action ...}`，同时状态标记为 `{:status :awaiting-approval}`；
3. 外壳向 Web 端发送 WebSocket 审批卡片；
4. **在此期间：系统完全不阻塞任何线程，自然处于静默状态**；此时系统可以并发处理其他只读请求；
5. 5 分钟后，用户在 Web 端点击“批准” $\to$ 外部输入网关收到事件 `{:type :user/approved, :task-id "t1"}`；
6. 纯核状态被唤醒，继续发出真正的工具执行意图。
