# Botashka 的 run event log 与 OpenTelemetry：保守集成研究

## 结论（建议）

**建议：先把现有运行记录收敛为唯一的、结构化的本地 run event log；现在不在 Babashka 进程内接入完整 OpenTelemetry SDK。** event log 保留受配额和访问策略约束的 RCA evidence；每个 observation projection 只消费 allowlisted、redacted summary。事实流稳定后，才增加可选、单向的 `observation` 投影接口：未来 JVM Clojure 宿主可在其后实现 OTLP traces/metrics exporter。这样既不会把观测协议变成 agent 控制流，也不预设 TUI、持久化 store 或 HTTP server。

这是与仓库词汇一致的切分：`Agent run` 已拥有有序 run event log；`Run observation` 消费事实，不能反向决定 agent 行为（见 [`CONTEXT.md`](../CONTEXT.md)）。OpenTelemetry（OTel）是可替换的**外部投影**，不是 event log 的权威存储，也不是 event schema 的来源。按 OTel 术语，当前线性 event log 不是 trace：OTel trace 是由有 parent/child 关系、开始/结束时间和 status 的 spans 组成的因果图。

## 1. 三种信号：各自回答什么问题

| 信号 | OTel 中的含义与适用问题 | Botashka 当前对应物 | 现在的处理 |
| --- | --- | --- | --- |
| **日志（logs）** | 带时间戳的离散记录；适合可读诊断、错误细节和运行文本。OTel Logs 数据模型可携带 trace/span context，用于关联，但日志本身不是调用树。 | 终端输出、异常文字、必要的 redacted 摘要。 | 保持人可读；从 run event 渲染。不要把日志解析回状态。 |
| **追踪（traces）** | 一个 trace 由因果相关的 spans 组成；span 有开始/结束时间、状态、属性和 events，回答“一次 run/调用经过了哪些边界、耗时和失败在哪里”。 | 一次 `Agent run` 及其 provider/tool 子操作。 | 保留 run event log 的有序事实；将来可按 run 建 root span、按外部操作建 child span。 |
| **指标（metrics）** | 随时间聚合的数值时间序列，回答“发生多少、耗时分布、当前多少”，而非某一 run 的完整过程。 | run 总数、终态计数、provider/tool 耗时直方图、in-flight 数。 | 仅在已有长期运行的 JVM exporter 或 scrape endpoint 时导出；不要以每个 run 的 ID、prompt、路径等作标签。 |

OTel 的规范明确将 traces、metrics、logs（以及 profiles）作为不同信号，并以共同 `Context` 关联；语义约定还分别规定资源、trace、metric、log/event 的命名。[OTel overview](https://opentelemetry.io/docs/specs/otel/overview/)、[Logs specification](https://opentelemetry.io/docs/specs/otel/logs/)、[semantic conventions overview](https://opentelemetry.io/docs/specs/semconv/)。

**建议：事实、RCA evidence 与投影分离。** 例如一次 `:tool-result` 是不可变 run event。其受控本地 evidence 可以保存 arguments/result 或指向有配额的 payload artifact，供 RCA 读取；日志、span 和 metrics 只接收 allowlisted summary。三者都不能替代原始 evidence：日志缺少可靠结构，span 会被采样/导出失败，metrics 天生丢失逐次细节。

## 2. 运行时可行性：Babashka 与 JVM Clojure 不能等同

### Babashka

Babashka 由 SCI 解释 Clojure 子集、编译为 GraalVM native binary；其 `bb.edn :deps` 只能加入纯 Clojure 库，不能加入 Java 库，因为 native image 的 Java classpath 是封闭的。[Babashka book：Clojure libraries](https://book.babashka.org/#_clojure_libraries)。因此依赖 OpenTelemetry Java API/SDK/OTLP exporter 的 Clojure 包，即使 API 外观是 Clojure，也**不能假设可在当前 `bb` 内加载**。

这排除的是本进程 Java SDK 路径，不排除以下两件事：

1. Botashka 继续生成自己的 EDN/JSON 结构化事件；
2. 由独立 JVM 进程、collector、或未来 JVM Clojure 入口消费/转换这些事件。

**建议：不要为绕过该限制在现在引入常驻 JVM sidecar、HTTP server 或自制 OTLP 编码器。** 它们会改变当前小型 CLI 的生命周期、故障模式和部署面，却没有已证实的消费者。

### JVM Clojure（未来可选宿主）

JVM Clojure 可以正常使用 Java interop，因此可以采用 OTel Java API/SDK 与 exporter；OTel Java 文档区分 API（库/应用可调用的 instrumentation interface）和 SDK（provider、processor、exporter 等实现），SDK 负责创建和导出 telemetry。[OpenTelemetry Java manual instrumentation](https://opentelemetry.io/docs/languages/java/instrumentation/)。

社区库 [`clj-otel`](https://github.com/steffan-westcott/clj-otel) 的 README 将自身定义为“为 Clojure libraries/applications 添加 OTel 的 idiomatic Clojure API”；它是**第三方库的第一方文档**，不是 OTel 官方语言实现保证。**建议：只有 Botashka 实际以 JVM Clojure 运行、并已决定要导出 OTLP 时，才评估其版本/依赖/维护状态；不要把它列为 Babashka 的运行时依赖。**

OTel SDK 规范要求每个进程有一个 SDK，且 SDK 的 TracerProvider/MeterProvider 用于创建相应对象；应用应在启动时配置 provider/exporter，而不是每次 run 临时全局初始化。[OTel SDK specification](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/)、[Java SDK configuration](https://opentelemetry.io/docs/languages/java/configuration/)。这也说明未来 exporter 应是显式启动/停止的 `Event publisher` 组件，而不是塞入 `agent/run!` 或纯函数。

## 3. Phoenix 与 Prometheus 各消费什么

### Phoenix：消费 Elixir telemetry events，不是 Botashka trace 文件

Phoenix 的 [`:telemetry` guide](https://hexdocs.pm/phoenix/telemetry.html) 说明 Phoenix 在应用生命周期多个阶段发出 Telemetry events，handler 可以据此聚合 metrics 或把数据交给 reporter；Phoenix 应用模板还带 Telemetry supervisor/metrics 定义。Phoenix 因而是**另一个 BEAM 进程中的观测生产者/消费者生态**，并不会读取 Babashka 的 EDN，也不会自动理解 Botashka 的 event type。

**建议：不要现在“为 Phoenix 集成”而让 Botashka 依赖 Elixir/Phoenix 协议。** 若将来有 Phoenix Web/UI：

- Phoenix 自己对 HTTP、LiveView、DB 等发出其 Telemetry events；
- Botashka 通过明确的跨进程边界（例如已存在的 API、消息或 OTLP collector）提供 run 状态/telemetry；
- 两边共享稳定的 `service.name`、环境和 W3C trace context，才能在后端关联，而不是让 Phoenix 直接成为 Botashka trace store。

### Prometheus：拉取聚合 metrics，不消费 trace 或日志

Prometheus 将 metrics 定义为数值测量随时间形成的 time series；典型采集模型是从 HTTP `/metrics` 暴露端点抓取。其 exporter 指南强调选择 counter、gauge、histogram、summary 等适当类型，并避免无界 label 值。[Prometheus overview](https://prometheus.io/docs/introduction/overview/)、[metric types](https://prometheus.io/docs/concepts/metric_types/)、[writing exporters](https://prometheus.io/docs/instrumenting/writing_exporters/)。OpenMetrics/Prometheus exposition format 是 metrics 的文本暴露格式，不是 trace transport。[OpenMetrics 1.0 specification](https://prometheus.io/docs/specs/om/open_metrics_spec/)。

**建议：现在不新增 `/metrics` server。** 单次 Babashka CLI 一般在 Prometheus 抓取之前已退出；暴露端点不会产生有用序列。将来仅当存在长寿命 JVM service/sidecar 且运维确实抓取时，投影如下低基数指标：

- `botashka_agent_runs_total{status}`：counter；`status` 是封闭枚举，如 `completed`、`step_limit`、`provider_error`；
- `botashka_agent_run_duration_seconds`：histogram；
- `botashka_tool_calls_total{tool_name,outcome}`：counter，前提是 `tool_name` 是受 registry 控制的有限集合；
- `botashka_tool_duration_seconds{tool_name}`：histogram；
- `botashka_agent_runs_in_flight`：gauge（仅适合长寿命宿主）。

绝不以 `run.id`、trace/span ID、用户 prompt、文件路径、模型原文、异常文本或任意 tool 参数作为 Prometheus label；它们的基数或敏感性不受控。不要以 metrics 记录一次 tool result。

## 4. trace 模型、语义属性与传播

### 领域 trace 先行，OTel span 随后投影

**建议的最小映射：** 一个 `Agent run` 映射到一个 root span `botashka.agent.run`；只有跨真正 effect 边界、且需要耗时/失败归因时才建立 child span：`botashka.provider.request`、`botashka.tool.execute`。`user-prompt`、`provider-response`、`tool-call`、`tool-result`、`final-answer`、`step-limit` 仍是领域 trace event；投影时可成为 span event，或在其所属操作结束时归纳为属性。不要对每一个纯 map 变换、token 或终端渲染建立 span。

OTel trace API 将 span 表示为操作，span 可有 attributes、events、status 和 links；语义约定要求使用稳定的通用字段并在适用时采用领域专用约定。[OTel tracing API](https://opentelemetry.io/docs/specs/otel/trace/api/)、[general trace semantic conventions](https://opentelemetry.io/docs/specs/semconv/general/trace/)。

### 必需的最小元数据

下列字段由 Botashka 事实流保存；未来映射 OTel 时采用相应标准字段，避免把一切塞入字符串日志。`run.id` 是内部相关 ID，不等于 OTel `trace_id`。

| 范围 | 必需/建议字段 | OTel 映射与限制 |
| --- | --- | --- |
| 资源（所有投影） | `service.name=botashka`、`service.version`（发布版本或 commit）、`deployment.environment.name`（仅配置时） | `service.name` 是 Resource semantic convention 的核心标识；用资源属性，不要每个 span 重复。见 [resource conventions](https://opentelemetry.io/docs/specs/semconv/resource/)。 |
| run root | `run.id`、`run.sequence`、终态、总步数、deadline/取消结果 | 自定义属性应使用稳定、带命名空间的名称，如 `botashka.run.id`、`botashka.run.status`；`run.id` 只用于 trace/log 关联，**不**作 metric label。|
| provider child | provider 名称、模型标识、请求/响应耗时、重试次数、成功/失败分类、可安全统计的 token usage | 采用 [GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/) 中当时稳定且适用的 `gen_ai.*` 字段；不得把 prompt、completion、API key 或原始 headers 作为属性。 |
| tool child | registry 的受控 tool 名、调用 ID、持续时间、结果分类、timeout/cancel 标记 | 可用稳定自定义 `botashka.tool.name` / `botashka.tool.outcome`；工具不是自动等同于 HTTP/DB/RPC。只有真跨 HTTP/process 边界时才加对应标准语义字段，并执行脱敏。 |
| 错误 | error kind/class、稳定 code、是否可重试、redacted message/digest | OTel 对 error status/exception recording 有规范；记录错误不等于导出秘密或完整 tool output。[recording exceptions](https://opentelemetry.io/docs/specs/otel/trace/exceptions/)。 |

**建议：投影只记录 allowlist 属性；本地 evidence 才可在 policy 下保留原始材料。** prompt、LLM completion、工具参数、文件内容、access token、Authorization header、环境变量和完整 stdout/stderr 默认不得进入 span attribute、log body 或 metric label。RCA 所需的 prompt、arguments、result、final answer 应留在 run-local evidence：小 payload 可受总量/单项上限直接存入 event 的 `:evidence`；大 payload 写入受访问控制的 payload artifact，event 只存 `:digest`、`:characters`、`:truncated?` 与 artifact reference。超限必须显式标记，不能静默丢失。

### 上下文传播

OTel Context 是跨信号/跨边界关联的机制；W3C Trace Context 定义 `traceparent`（必要）与可选 `tracestate` HTTP header 的格式和处理规则。[OTel context propagation](https://opentelemetry.io/docs/concepts/context-propagation/)、[W3C Trace Context](https://www.w3.org/TR/trace-context/)。

**建议的边界规则：**

1. 在一次 run 内，父/子关系由显式 `run-context` 值传递；不要使用隐藏全局状态决定父 span。
2. 未来 HTTP 调用若已有可信的入站 `traceparent`，按 OTel propagator 提取它并使 run root 成为其 child；若是可信出站 HTTP，注入当前 context。对 tool subprocess，只有其协议明确支持时才经环境变量/输入显式传递，不要猜测地污染所有子进程。
3. `baggage` 是跨边界应用元数据，不是任意 trace payload；只传播经过 allowlist 的低敏感、低基数键。OTel 明确将 baggage 作为 Context 的一部分，并提示需注意数据泄露。[Baggage specification](https://opentelemetry.io/docs/specs/otel/baggage/api/)。
4. 目前没有 HTTP server 或远程服务时，不实现 propagator；本地 `run.id` 足以关联本进程的事实流。未来的 Phoenix/OTLP 边界才是采用 W3C headers 的触发条件。

## 5. 最小 adapter/bridge 设计（建议）

只在现有 `append-event!`/execution-trace 的**一个**出口后增加接口，不改变 `botashka.agent` 的状态机和返回值。用数据而非 OTel 对象穿过核心：

```clojure
;; 领域事实已先写入 run event log；观察端失败不得改变 run 的终态。
(defprotocol ObservationSink
  (observe! [sink event])
  (close-run! [sink terminal-result]))

;; nil/local 实现：仅现有 run-event-log/terminal renderer。
;; future JVM implementation：按 event 投影 root/child spans、logs、metrics，批量导出。
```

接口名称和函数形状可按既有命名调整；关键是不泄漏 SDK 的 `Span`、`Context`、exporter 或线程池到 agent/provider/tool contracts。

**推荐的事件处理顺序：**

1. agent 形成完整 run event：`data` 是 projection-safe summary；`evidence` 由 run-local policy 控制；两者均含 `:run-id`、单调 `:seq`、时间和 `:type`；
2. event-log owner 追加它，保持本地事实的顺序和失败语义；
3. synchronous optional sink 只接收 allowlisted projection，或由未来 publisher 有界异步消费；sink failure 只形成本地诊断，不阻断 agent；
4. run 结束时以 terminal result 关闭 projection；未来 SDK component 在应用停止时 flush/shutdown。

默认采用字段分级 evidence policy：prompt、final answer、structured tool arguments 在配额内保留；`read_file` content 和 tool stdout/stderr 仅保留摘要/digest，除非人类显式开启 capture。capture 是 host 的 run option，不是模型可设置的 tool argument。

## 6. 现在明确不做

**建议延后下列项目，直到有可验证的运行时需求：**

- 不在 Babashka 中加载 OTel Java SDK、Java agent 或依赖它们的 exporter；
- 不增加 OTLP collector、sidecar、常驻 JVM、HTTP `/metrics`、Phoenix endpoint 或数据库，只为“未来可观测性”；
- 不把 OTel span/trace ID 作为领域 run ID，或用 OTel 替换 append-only run event log；
- 不建立 dashboard、alert、全链路 sampling policy、tail sampling、日志聚合或 OTel trace store；
- 不自动捕获 prompt、completion、文件内容、tool I/O、环境变量或 secrets；
- 不为每一内部函数、每个 token 或每个 event 创建 span，不用 run ID 等高基数值作 Prometheus label；
- 不为了 exporter 而引入 TUI、持久化 session store、并发执行或 HTTP server。

## 7. 分阶段的可验收切入点（建议）

1. **先收敛（当前）：** 固定 run event 的 envelope（run ID、序号、时间、type、redacted projection data、terminal status），使所有现有执行路径从同一 log 出口发出事实；继续只输出本地 run event log/日志。
2. **只加 seam：** 当确有第二个消费者时，接入上述 no-op/local `ObservationSink`，并证明 sink 失败不会改变 agent 返回值或事件顺序。
3. **JVM/OTLP 触发后：** 仅在 Botashka 有 JVM Clojure 长寿命宿主或明确 collector 时，配置一次 OTel SDK，导出 root/child spans 和四个低基数指标；以 OTel Java 文档/当时稳定 semconv 校验字段。
4. **Phoenix/Prometheus 触发后：** Phoenix 服务通过已有进程边界接收/传播 context；Prometheus 仅抓取长寿命服务的聚合 metrics。分别验收 OTel trace 关联和 metrics 序列，而不是要求二者读取原始 CLI run event log。

这种顺序保留了 OTel 的互操作性，却让当前 Babashka 工具先解决它已经拥有的、最有价值的可观测性：可信、可关联、脱敏且不干扰执行的运行事实。