# Botashka 纯内存 run event log：Clojure 设计研究

## 结论摘要

**现在应把一个 Agent run 的 event log 做成纯内存的不可变数据，并把 I/O 留在显式的命令式外壳。** 这不是把“持久化”推迟为模糊的以后再说，而是先固定可重放、可测试且不依赖存储介质的事实模型；未来文件、数据库或消息发布者只能消费这些事实，不能成为其定义者。该方向与仓库已定义的 run event log（“一个 run 的规范性、有序、不可变执行事实序列”）及“pure core, thin effect shell”原则一致。[`CONTEXT.md`](../CONTEXT.md)

## 1. 为什么事件日志先是值，而不是 I/O

Clojure 的集合是**不可变且持久化（persistent）**的；所谓“修改”会产生一个新集合，新旧版本通过结构共享高效复用，因此集合可安全地在多个线程间共享。这里的 persistent 是持久数据结构的版本语义，**不是**写入磁盘。[Clojure Data Structures](https://clojure.org/reference/data_structures)

因此，一个小型 run 可把日志表示为按发生顺序的向量，事件表示为不可变 map：

```clojure
{:run-id run-id
 :seq    next-seq
 :type   :tool-result
 :at     occurred-at
 :data   summary}
```

追加是纯值变换：`(conj events event)` 返回含有新事件的新向量，而先前的 `events` 仍可用于诊断、断言或重放。向量的 `conj` 在尾部加入元素；Clojure 的集合操作返回新逻辑值而非就地修改。[Clojure Data Structures：Collections 与 Vectors](https://clojure.org/reference/data_structures)

这让“记录事实”和“把事实显示/保存出去”成为不同问题：前者决定 run 的历史和可测试语义，后者只是一个消费者。仓库也明确将文本日志、外部 telemetry 与 UI 定义为 event 的非权威 observation projection，且其失败不得改变 run 的控制流或终态。[`CONTEXT.md`](../CONTEXT.md)

## 2. 纯转移的形状：状态 + 输入 → 新状态 + 事实/效果描述

Clojure 的状态模型将 identity 与 immutable value 分离：值本身不改变；随时间变化的是一个 identity 所关联的值。[Values and Change](https://clojure.org/about/state) 对 run 而言，适合把转移明确写成普通函数，例如：

```clojure
(defn append-event [run event]
  (let [seq (count (:events run))
        event (assoc event :run-id (:id run) :seq seq)]
    (update run :events conj event)))

;; 需要外部工作的决策也只是数据：
{:run next-run
 :events [event]
 :effects [{:effect :provider/request :request request}]}
```

上例中的具体字段是 Botashka 的应用设计，而不是 Clojure 的强制 API；关键是转移的输入和输出均为数据。Clojure 的 transducer 文档同样把 reducing function 表示为“累计结果 + 新输入 → 新累计结果”，并强调转换可与输入/输出来源解耦。[Clojure Transducers](https://clojure.org/reference/transducers) 对当前小日志，普通 `reduce`、`map`、`filter` 已足够；只有需要可组合的过滤、映射、截断或聚合投影时，才引入 transducer，避免创建不必要的中间集合。[Clojure Transducers](https://clojure.org/reference/transducers)

“命令—事件—效果”的三分法应作为**应用架构约定**：命令表达请求，纯转移产生新 run/事件和效果描述，效果解释器才调用 provider、工具、文件或终端。Clojure 官方资料支持其中的不可变值和副作用隔离，但并未规定此架构名称或事件格式。[Values and Change](https://clojure.org/about/state) [Clojure Atoms](https://clojure.org/reference/atoms)

re-frame 的第一方文档给出了很直接的同类实践：`reg-event-fx` handler 返回 effects map，即副作用的**描述**；随后由注册的 effect handler 执行。该文档也说明 effect handler 的返回值会被忽略，并建议 effect handler 尽量简单，因为副作用代码较难彻底测试。[re-frame Effects](https://day8.github.io/re-frame/Effects/) Botashka 不需要引入 re-frame；可借其边界思想，令 effect runner 是薄适配器，而非让 run 转移直接 I/O。

## 3. Atom 是进程内协调容器，不是领域模型或日志存储

官方将 Atom 定义为管理“共享、同步、独立状态”的引用类型：`swap!` 原子地把函数施加于当前值；Atom 适合无需与其他状态位置协调的同步更新。[Clojure Atoms](https://clojure.org/reference/atoms) 因而在需要并发读取或追加时，可以把**整个 coherent run snapshot**（例如 `{:events [...] :status ...}`）放在一个 Atom 中：

```clojure
(def run* (atom initial-run))
(swap! run* append-event event) ; append-event 必须保持纯粹
```

Atom 的职责到此为止：它安装不可变快照并协调进程内竞争；领域状态仍是 `@run*` 取得的 map，事件日志仍是该 map 中的向量。不要让 Atom、本地 Var 或隐藏全局引用成为“run 的定义”或跨 run 的权威存储；这也符合仓库“state has an owner and scope”的规则。[`CONTEXT.md`](../CONTEXT.md)

更重要的是，`swap!` 内部可在 CAS 竞争后重试所给函数，因此官方明确要求该函数无副作用。[Clojure Atoms](https://clojure.org/reference/atoms) 所以 `swap!` 的转移函数**不得**写文件、发 HTTP、调用 tool、打印一次性通知或发布外部消息，否则同一效果可能执行多次。若当前 run 天生是单线程线性执行，甚至可仅在线性局部值中累积 run；只有需要多个线程安全地读取/更新同一 run snapshot 时才增加 Atom。

若一次业务动作必须协调多个独立内存位置，Ref/STM 提供多 Ref 更新的 atomic、consistent、isolated 事务语义。[Clojure Refs and Transactions](https://clojure.org/reference/refs) 但它不是小型单 run event log 的默认升级：一份 run map 已能把日志、终态和派生计数置于单一状态位置。并且 STM 冲突会重试，官方明确要求避免在事务内 I/O 或其他副作用。[Clojure Refs and Transactions](https://clojure.org/reference/refs)

## 4. 事件事实、命令与效果如何切开

建议使用以下责任边界：

| 数据/边界 | 含义 | 是否可纯粹计算 |
| --- | --- | --- |
| 命令 | 例如“请求 provider”“执行已批准的 tool”“取消 run”；它表达意图，不等同于已发生。 | 是 |
| run state | 该 run 的会话进度、终态和事件向量。 | 是 |
| run event | 已发生的规范性事实，如 `:provider-response`、`:tool-result`、`:step-limit`。 | 是 |
| effect description | 对 provider、tool、文件、终端或未来 publisher 的工作说明。 | 是 |
| effect interpreter | 真正执行网络、进程、文件、终端或异步发布的 adapter。 | 否 |

上述命名是建议；仓库术语已区分 `Tool call`（provider 的提议）和 `Tool result`（host 已执行的结果），并要求 provider adapter 只拥有 HTTP 细节、不拥有 agent control flow。[`CONTEXT.md`](../CONTEXT.md) 对纯转移而言，只有 effect 完成后返回的外部结果才可作为下一条事实的输入；转移不应预先把“写盘成功”或“tool 已执行”伪造为 event。

re-frame 还说明，effect map 的 `:fx` 可表示**有序**效果列表，并且从 v1.1.0 起 `:db` 先执行、`:fx` 内顺序执行；这说明一旦效果之间存在顺序依赖，顺序应成为明确数据，而不是依赖 map 遍历或偶然的调用顺序。[re-frame Effects：Order Of Effects](https://day8.github.io/re-frame/Effects/) 对 Botashka，当前应只保留 agent 已有的线性控制流；不要为了“纯事件”提前引入通道、并行 publisher 或通用 effect runtime。

## 5. 同步持久化：内存原子性不等于文件原子性

Atom 的“无 race condition”保证针对其进程内状态更新。[Clojure Atoms](https://clojure.org/reference/atoms) Ref 的事务原子性也描述的是 Ref world 中的内存位置；官方特别警告事务可能重试，故要避免 I/O。[Clojure Refs and Transactions](https://clojure.org/reference/refs) 因此不能把 `swap!` 或 `dosync` 宣称为“事件已同步落盘”的原子提交：它们没有承诺把内存状态、文件内容、目录项和机器崩溃恢复合成一个事务。

若未来确有同步持久化需求，必须先定义它的语义，而不是把 `spit` 塞进 append：

1. **写前/写后语义：** provider/tool 的不可逆效果与事件记录何者先发生；进程在两者之间崩溃时如何识别未完成工作；
2. **提交单位：** 一条 event、一个 run snapshot，还是一个带校验的 append-only segment；
3. **失败策略：** 磁盘写失败是否使 run 终止、降级为内存模式，或仅产生额外事实；这必须由 host 明确决定；
4. **恢复与幂等：** 重启后如何读取部分写入、去重外部效果，以及何时可宣布 durable；
5. **资源生命周期：** 打开、flush、关闭和错误传播由 run store/component 拥有，而不是 pure agent transition 拥有。

这些是跨资源协议问题，不是 Atom/STM 自动提供的能力。仓库也把 `Run store` 列为仅在 traces 需要跨 REPL 存活或 run 需要恢复时才引入的 lifecycle component，并把 `Event publisher` 留到需要向 TUI、Phoenix、OTLP 等流式输出时。[`CONTEXT.md`](../CONTEXT.md)

## 6. 对小型 Babashka agent 的具体模式

1. **以 run-local 的不可变 map 为权威。** 初始化时建立 `{:id ... :events [] :status :running ...}`；每个可观察的结果经一个纯函数追加为带单调 `:seq` 的事件。向量天然保留发生顺序；不可变旧快照可以安全交给 renderer 或断言。[Clojure Data Structures](https://clojure.org/reference/data_structures)
2. **把时钟、ID 与外部结果作为输入注入。** 纯转移不直接读当前时间、生成随机 ID 或调用 provider；imperative shell 取得这些值后传入。这样同一初始 run 与同一输入会得到相同输出，适合 REPL 复现与单元测试。这是由不可变值/纯计算导出的设计选择，而非 Clojure API 的单独保证。[Values and Change](https://clojure.org/about/state)
3. **按需以一个 Atom 协调该 run。** 多个观察者/生产者确实共享同一 run 时，用一个 Atom 承载完整状态，并仅将纯函数传给 `swap!`；不要让 event append 的 CAS 重试重放 I/O。[Clojure Atoms](https://clojure.org/reference/atoms)
4. **把 renderer 与未来持久化器做成消费者。** terminal 输出从事件或终态渲染；未来 `append-to-file!`、数据库 store、publisher 均在效果边界消费已构造事件。当前 observation projection 的失败不能倒灌到 agent 的控制流。[`CONTEXT.md`](../CONTEXT.md)
5. **只在确有需求时增加派生投影。** 查询/摘要可用普通序列函数或 transducer 从 event vector 计算；transducer 独立于输入/输出源，适合复用转换，但不是并发协调或 durability 机制。[Clojure Transducers](https://clojure.org/reference/transducers)

## 推荐与非目标

**推荐（当前 Botashka run event log 重构）：** 将 `Agent run` 的 canonical log 固定为 run-local、内存中的不可变 event vector；用纯 transition 函数将命令和外部结果转为新 run state、规范事件及必要的 effect description。保持现有线性 agent 控制流；仅在同一 run 需要并发协调时，以**一个** Atom 原子安装完整不可变 run snapshot。所有 provider/tool/终端 I/O，以及未来的文件 store 或 event publisher，都放在显式 adapter 中，并在外部效果完成后把结果再输入纯核心。这样符合 Clojure 对 persistent collections、Atom 重试安全和 STM 副作用边界的保证，也符合 Botashka 的数据优先、事实优先架构。[Clojure Data Structures](https://clojure.org/reference/data_structures) [Clojure Atoms](https://clojure.org/reference/atoms) [Clojure Refs and Transactions](https://clojure.org/reference/refs) [`CONTEXT.md`](../CONTEXT.md)

**非目标（现在不做）：**

- 不把 Atom、Ref 或 `dosync` 误作文件/数据库持久化或崩溃一致性协议；
- 不在 `swap!`/事务函数中同步写文件、调用 provider、执行 tool 或发布通知；
- 不为未来存储预先引入数据库、队列、常驻 publisher、组件管理器、异步 agent 或 event-sourcing framework；
- 不把 provider conversation、文本日志、terminal renderer 或 telemetry projection 作为 canonical run event log；
- 不承诺跨进程恢复、重放外部副作用、审计保留期或 exactly-once 持久化；这些只在真实的 run store/恢复需求出现时，连同明确的写入与恢复协议一起设计。
