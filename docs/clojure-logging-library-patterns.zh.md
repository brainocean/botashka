# Clojure 日志库模式：调用、上下文、捕获与输出

## 范围与结论

本文只考察三个项目自己的 README、API 文档和源代码：[`clojure.tools.logging`](https://github.com/clojure/tools.logging)、[Taoensso Timbre](https://github.com/taoensso/timbre) 与 [mulog](https://github.com/BrunoBonacci/mulog)。这里的「日志」是为了诊断、观测或输出而产生的记录；它**不是** event sourcing，也不是 Botashka 的状态模型。

三者给出的共同工程教训是：**把领域事实的形成，与日志调用、内存测试捕获及 I/O sink/appender/publisher 分开。** 它们的 API 都允许过滤、缓冲、异步、格式化失败或下游失败；因此都不应被当作一次 agent run 的权威历史。

| 库 | 调用点主要模型 | 下游扩展/分派 | I/O 默认语义 | 结构化上下文 |
| --- | --- | --- | --- | --- |
| `clojure.tools.logging` | 宏检查 level 后向后端写文本消息 | `LoggerFactory` → `Logger`；appender 属于选定后端 | 通常直接调用后端；STM 中选定 level 可经 agent | 核心 API 没有 context fields |
| Timbre | 宏过滤后构造 log-data map，交给 appender | appender 配置 map 的 `:fn` | 默认同步；`:async? true` 时 `send-off` | 动态 `*context*`、单次 `^:meta` |
| mulog | 宏产生事件并入 ring buffer | `PPublisher` 与按 `:type` 的 factory | 调用点入队；分派和 publisher 异步 | global、thread-local 与行内属性 |

以下「纯」严格指没有可观察副作用的函数。三个库的正常 logging call 都**不是纯函数**：它可能写后端、改变缓冲/agent 状态或安排异步工作。它们至多在某些过滤条件下避免求值或避免最终 I/O；不能以此把日志调用用于决定领域状态、terminal result 或重放依据。

## 1. `clojure.tools.logging`：后端无关的 facade

### 调用点与分派

`log`、`debug`、`info` 等是宏。官方 README 明确说明，只有当指定 level 已启用时，消息参数才会求值，底层实现才会调用；`log` API 也称其「only if the specified level is enabled」才求值和记录。[README：Usage](https://github.com/clojure/tools.logging#usage)；[API：`log`](https://clojure.github.io/tools.logging/#clojure.tools.logging/log)。这是一项**禁用日志时的延迟求值优化**，而不是纯度保证：启用后会进入 logger 写入路径。

核心扩展边界有两个 protocol：

```clojure
(Logger/enabled? logger level)
(Logger/write! logger level throwable message)
(LoggerFactory/get-logger factory logger-ns)
```

`*logger-factory*` 在加载时由系统属性或 `find-factory` 选择；官方 README 列出的自动候选依次包括 SLF4J、Commons Logging、Log4J 2、Log4J 与 `java.util.logging`。[`Logger`](https://clojure.github.io/tools.logging/#clojure.tools.logging.impl/Logger)、[`LoggerFactory`](https://clojure.github.io/tools.logging/#clojure.tools.logging.impl/LoggerFactory)、[选择实现](https://github.com/clojure/tools.logging#selecting-a-logging-implementation)、[实现源码](https://github.com/clojure/tools.logging/blob/master/src/main/clojure/clojure/tools/logging/impl.clj)。

该项目**没有自己的 appender/sink protocol**。格式、目的地、轮转和后端 appender 配置均明确属于实际 logging implementation，超出 facade 范围。[README：Configuration](https://github.com/clojure/tools.logging#configuration)。所以它的分层是 `call-site macro → LoggerFactory → Logger.write! → backend-owned appenders`，而不是 Botashka 可以直接依赖的统一 I/O 抽象。

### 同步、异步、测试与失败

正常情况下 facade 直接调用 `Logger.write!`，不会承诺后端 I/O 非阻塞。不过在运行中的 STM transaction 内，对 `*tx-agent-levels*` 所选 level（默认 `#{:info :warn}`）会通过 `*logging-agent*` 延后；只有 transaction commit 后才写，retry/abort 的该类记录会丢弃。其他 level 可能在 retry 中重复写入。[`log*`](https://clojure.github.io/tools.logging/#clojure.tools.logging/log*)、[`*tx-agent-levels*`](https://clojure.github.io/tools.logging/#clojure.tools.logging/*tx-agent-levels)。这是条件性的 agent dispatch，不是端到端投递保证。

第一方测试 namespace 提供 `with-log`、`logged?` 与 atom-backed `atomic-log`；捕获条目是 `LogEntry [logger-ns level throwable message]`，可替换 factory 后断言内存中的结构化条目。[测试 API](https://clojure.github.io/tools.logging/#clojure.tools.logging.test)、[测试实现](https://github.com/clojure/tools.logging/blob/master/src/main/clojure/clojure/tools/logging/test.clj)。这是测试替身，不是持久、可重放的 production event log。

核心写入契约只携带 logger namespace、level、throwable、message；没有 map/context/MDC/JSON 字段 API。`ex-info` 数据是否显示亦取决于后端如何渲染 throwable。[README：FAQ](https://github.com/clojure/tools.logging#faq)。配置的 factory 无法解析、或找不到可用实现时会抛异常；`log-capture!` 若把 stdout/stderr 重定向回一个本身写 stdout 的实现，文档警告会造成 `StackOverflowException`。[核心源码](https://github.com/clojure/tools.logging/blob/master/src/main/clojure/clojure/tools/logging.clj)、[`log-capture!`](https://clojure.github.io/tools.logging/#clojure.tools.logging/log-capture!)。因此不能假定 logging failure 被吞掉，也不能把 message 渲染当成稳定数据协议。

## 2. Timbre：结构化 log-data 与可配置 appender

### 调用点、appender 与 dispatch

Timbre 的 level 宏将日志参数包装为 `delay`；通过 runtime level/namespace filter 后才解引用。它也支持 compile-time level/namespace elision，官方 README 将此称为「zero overhead compile-time level/ns elision」。[README](https://github.com/taoensso/timbre/blob/master/README.md)、[API：`log!`/配置](https://taoensso.github.io/timbre/taoensso.timbre.html)、[核心源码](https://github.com/taoensso/timbre/blob/master/src/taoensso/timbre.cljc)。所以被过滤的普通 log call 可不求值参数；通过过滤的调用仍会构造数据并触发 appender，故并不纯。

Timbre 的 appender 不是 Java interface，而是配置 map；关键是 `{:enabled? ... :fn (fn [data] ... )}`。`data` 是完整 log-data，而非既定字符串。它含 level、namespace/source location、异常、动态 context、原始 `:vargs`、惰性 `:output_` 等；全局 middleware 先运行，然后逐 appender 进行启用、level/namespace、rate-limit 过滤，再调用 appender `:fn`。[README：appenders](https://github.com/taoensso/timbre/blob/master/README.md)、[API：`*config*` 的 appenders 与 log data](https://taoensso.github.io/timbre/taoensso.timbre.html)、[分派源码](https://github.com/taoensso/timbre/blob/master/src/taoensso/timbre.cljc)。

这使 appender 适合作为**观察数据的消费者**：格式化由 `output-fn` 决定，I/O 副作用由 `:fn` 承担；两者不应反向定义业务事件。

### I/O、捕获、context 与失败

默认 appender 直接在 log 调用路径执行 `apfn data`，因此慢文件/网络 appender 会阻塞调用者。仅在 Clojure 中，将 appender 配为 `:async? true` 才通过每个 appender 的 agent `send-off` 分派；官方提示异步场景退出前考虑 `shutdown-agents`。[API：`:async?`](https://taoensso.github.io/timbre/taoensso.timbre.html)、[核心源码](https://github.com/taoensso/timbre/blob/master/src/taoensso/timbre.cljc)。内置 `spit-appender` 则是文件 I/O appender，可设置文件名、append 和 locking。[appender API](https://taoensso.github.io/timbre/taoensso.timbre.appenders.core.html)。

官方测试以一个 `volatile!` 的 `capturing-appender` 保存完整 `data`，临时装入配置，再断言 level/context/exception/output 等字段。[测试源码](https://github.com/taoensso/timbre/blob/master/test/taoensso/timbre_tests.cljc)。这表明可测试的是 appender 收到的数据，不表示该内存记录有权威性、顺序/持久化/背压语义。

`with-context` 将任意动态 context 交给范围内 appender；`with-context+` 合并嵌套 context。首个带 `^:meta` 的 map 作为单次 `:?meta` 进入 log-data，异常也可由其中的 `:err` 指定。[API：`*context*`、`with-context`、`:?meta`](https://taoensso.github.io/timbre/taoensso.timbre.html)。这是观测关联数据，不能隐式成为 Agent run 状态；尤其不能用动态绑定代替显式的 `run-id` 与事件 envelope。

Timbre 对非法 level 抛错；消息/输出函数的失败会以带上下文的 Timbre error 抛出。同步 appender 的异常默认允许传播；源码说明这是为了尽早暴露错误而不是静默丢弃。相反 `:async?` 改变了调用方观察失败的时机。`spy` 会记录被包裹表达式的异常后重新抛出，而 `log-errors` 则是显式「记录并吞掉」的包装宏。[核心源码](https://github.com/taoensso/timbre/blob/master/src/taoensso/timbre.cljc)、[API：`shutdown-appenders!`](https://taoensso.github.io/timbre/taoensso.timbre.html)。这再次说明日志失败策略必须由 Botashka 自己在 observation 边界规定，不能让库默认决定 run 的结果。

## 3. mulog：异步的结构化事件 publisher 管线

### 调用点、publisher 与异步分派

mulog 的 `(μ/log event-name :key value ...)` 从一开始就记录 event name 与键值数据，而非文本消息；宏加入 `:mulog/namespace`，随后 `log*` 将 event 入 logger 的 ring buffer 并返回 `nil`。其源码明确说异步过程会把 buffer 内容送给 registered publishers。[README](https://github.com/BrunoBonacci/mulog/blob/master/README.md)、[`log` 宏](https://github.com/BrunoBonacci/mulog/blob/master/mulog-core/src/com/brunobonacci/mulog.clj)、[`log*` 与默认 logger](https://github.com/BrunoBonacci/mulog/blob/master/mulog-core/src/com/brunobonacci/mulog/core.clj)。因此它避免最终 I/O 出现在调用线程，但 `μ/log` 仍会改变日志缓冲状态，不能称为纯。

mulog 的下游抽象是 `PPublisher`，不是传统 appender：

```clojure
(agent-buffer [publisher])
(publish-delay [publisher])
(publish [publisher buffer])
```

`publisher-factory` multimethod 按配置 `:type` 构造 console、simple-file、multi、custom、inline 等 publisher；`start-publisher!` 返回停止函数。[publisher protocol/factory 源码](https://github.com/BrunoBonacci/mulog/blob/master/mulog-core/src/com/brunobonacci/mulog/publisher.clj)、[`start-publisher!`](https://github.com/BrunoBonacci/mulog/blob/master/mulog-core/src/com/brunobonacci/mulog.clj)。核心 dispatcher 会从 logger buffer 读取，再把 event 送入每个 publisher 的 agent buffer；publisher 的 `publish` 通过 `send-off` 执行。[核心 dispatch 源码](https://github.com/BrunoBonacci/mulog/blob/master/mulog-core/src/com/brunobonacci/mulog/core.clj)。一个 event 可被多个 publisher 消费，因而它天然是 fan-out 观察管线，而不是单一事实存储。

内置 console 与 simple-file publisher 分别按延迟周期 publish；simple-file 的 `publish` 写 EDN 行到 writer。也就是说：**入队、调度、实际 I/O 分属不同阶段**，并且调用返回不等于已写盘或已送达外部系统。[publisher 源码](https://github.com/BrunoBonacci/mulog/blob/master/mulog-core/src/com/brunobonacci/mulog/publisher.clj)。

### 测试、context 与失败

项目测试源码的 `TestPublisher` 实现 `PPublisher`，`with-processing-publisher`/`with-test-publisher` 使用 atom-backed outbox，等待异步处理后检查已发布 event。[测试 publisher 源码](https://github.com/BrunoBonacci/mulog/blob/master/mulog-core/test/com/brunobonacci/mulog/test_publisher.clj)。这不是可同步替换 Botashka canonical log 的机制：测试必须等待异步发布，已经揭示了生产投递与断言之间的时间边界。

mulog event 可合并三层数据：`set-global-context!` 的进程级 context、`with-context` 的当前线程/词法范围 context，以及 `μ/log` 的 inline 属性。[核心 API](https://github.com/BrunoBonacci/mulog/blob/master/mulog-core/src/com/brunobonacci/mulog.clj)。官方也特别说明 local context **不会自动跨异步线程**；应显式取 `local-context` 并在新线程用 `with-context` 恢复。[context transfer 文档](https://github.com/BrunoBonacci/mulog/blob/master/doc/transfer-context.md)。因此 run 关联必须写入每个 canonical event 的显式字段，不能仅押注 thread-local logging context。

publisher 的 `publish` 抛异常时，核心先记录 `:mulog/publisher-error`，再重新抛出；动态加载/初始化非法 publisher 也会抛出带上下文的异常。[核心 dispatch 源码](https://github.com/BrunoBonacci/mulog/blob/master/mulog-core/src/com/brunobonacci/mulog/core.clj)、[publisher 加载源码](https://github.com/BrunoBonacci/mulog/blob/master/mulog-core/src/com/brunobonacci/mulog/publisher.clj)。`μ/trace` 记录 `:mulog/outcome :ok` 或 `:error`、异常与 duration，但记录 error 后会重新抛出业务异常。[`trace` 源码](https://github.com/BrunoBonacci/mulog/blob/master/mulog-core/src/com/brunobonacci/mulog.clj)。所以「结构化 event」不等于「可靠审计事实」：缓冲容量、异步时序、publisher 失败和进程退出仍是该库的观测输送语义。

## 4. 对 Botashka 的切分

Botashka 已将 **Run event log** 定义为「一个 agent run 的 canonical ordered sequence of immutable execution facts」；它拥有 run、单调序号、发生时间、事件类型和事件专属数据。**Observation projection** 则是非权威渲染/导出，其失败不得改变 agent control flow 或 terminal result。[`CONTEXT.md`](../CONTEXT.md)。这正好回答三类相似但不能混同的东西：

| 东西 | 谁拥有、何时形成 | 允许的失败语义 | 是否能决定/重建 run |
| --- | --- | --- | --- |
| canonical run event log | run orchestration 在真实执行边界形成；按 run/seq 追加 | 追加失败是事实记录边界的明确 run-level policy，不能伪装成「仅日志」 | 是；它是本地审计 evidence 的权威顺序 |
| observation logging | event 形成后由 renderer/sink 消费 | 必须隔离；失败只生成可控本地诊断，不能改 terminal result | 否；可能过滤、采样、格式化、异步、丢失或重复 |
| 库内存捕获 | 单元测试临时替换 logger/appender/publisher | 测试期断言工具 | 否；只验证某观察路径接收到什么 |

不要把 mulog 的 event map 或 Timbre 的 log-data 直接命名为 run event。它们允许全局/动态/thread-local context、库注入的字段、过滤和多个消费者；而 canonical event 必须由领域代码显式定义 envelope 与 schema。也不要从 `clojure.tools.logging` 的 message、Timbre 的 `:output_` 或文件 publisher 的文本反解析状态；它们都是可替换的 presentation/I/O 格式。

## 明确建议

**Botashka 应把 `botashka.log` 设计为 canonical run event log 的薄、同步且显式的追加边界；把人类日志和任何 Timbre/mulog/tools.logging 接入放在其后的 observation sink。**

1. 在 agent/provider/tool 的真实事实边界先构造不可变 run event：至少含 `:run-id`、单调 `:seq`、`:occurred-at`、`:type`、allowlisted projection data，及受 policy 约束的本地 evidence/reference；由 run owner 追加，不能由 appender callback 或动态 context 补齐。
2. 追加成功后才将 redacted、allowlisted summary 交给零或多个 observation sink。sink 可同步渲染终端，也可在未来采用 Timbre appender 或 mulog publisher；它们的 I/O、flush、缓冲、超时和失败均不得回写 agent 状态或改变 terminal result。
3. 测试分开写：直接断言 canonical event vector/store 的顺序和内容；另以库提供的内存 capture（tools.logging 的 `with-log`、Timbre 自定义 capture appender、mulog `TestPublisher`）断言某个 projection。不要以 stdout 文本或异步 publisher outbox 作为 run 事实测试。
4. 不在当前核心依赖隐藏 global logger、dynamic context 或 agent buffer 来承载 run 状态。若以后确有持久化/多消费者需求，再以 `Run store`、`Event publisher` 这类显式生命周期组件实现；它们消费事实，而不取代事实。

这保留三类库各自擅长的诊断、结构化日志与 I/O fan-out，同时维护 Botashka 所需的事实优先、可审计且不被观测故障干扰的 Agent run 语义。
