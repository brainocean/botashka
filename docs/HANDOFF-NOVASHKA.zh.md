# Novashka 架构交接与实施蓝图（Hand-off to Novashka）

> **代号**：`Novashka`（诺娃什卡，源自 *Nova* + *Babashka* 亲缘后缀）
> **目标**：从零构建基于 **Bun + nbb + DataScript + Micro re-frame** 的第二代纯反应式 Coding Agent，完整继承 Botashka v1 的能力（Feature Parity），并根除第一代架构中的阻塞与概念缺陷。
> **适用受众**：接手新 Repo 构建的 Coding Agent（以 `gemini-3.8-flash` 为主模型）与系统工程师。**拿到本文档即可直接创建新项目并开工实现。**

---

## 目录
1. [Botashka v1 经验与教训（Signals & Anti-patterns）](#1-botashka-v1-经验与教训signals--anti-patterns)
2. [Novashka v2 核心技术栈与运行时](#2-novashka-v2-核心技术栈与运行时)
3. [核心领域模型与数据契约](#3-核心领域模型与数据契约)
4. [核心引擎：Micro re-frame（~150行代码骨架）](#4-核心引擎micro-re-frame150行代码骨架)
5. [DataScript 事实库与 Datalog 记忆模型](#5-datascript-事实库与-datalog-记忆模型)
6. [Feature Parity 对齐矩阵（与 v1 严格对齐）](#6-feature-parity-对齐矩阵与-v1-严格对齐)
7. [分阶段实施路线图与验收命令（Milestones）](#7-分阶段实施路线图与验收命令milestones)
8. [新项目文件目录树（Project Layout）](#8-新项目文件目录树project-layout)

---

## 1. Botashka v1 经验与教训（Signals & Anti-patterns）

### 1.1 必须继承的黄金资产（Golden Assets）
1. **纯数据驱动的工具规范**：
   * 工具元数据（Name, Description, Parameters, Returns）必须是纯数据，基于 **Malli** 进行运行时输入校验与 JSON Schema 生成。
   * 工具执行必须在沙箱环境内运行，并有严格的超时控制与错误捕获。
2. **System 1 (Clef) 与 System 2 (LLM) 双脑协同**：
   * 快思考决策模型（Clef/Jev，非自回归，30ms 响应，输出离散工具概率）；
   * 慢推理生成模型（Gemini Flash / Claude，自回归长文本与代码生成）；
   * 必须保留这一快慢路径交接与 Fallback 机制。
3. **可观测性投影**：
   * 保持 ASCII Execution Trace Graph 终端可视化能力；
   * 保持轻量级 Web 控制台（Zero-build、Scittle 原生 CLJS、实时 WebSocket 驱动）。
4. **Human-in-the-loop 敏感操作门禁**：
   * 破坏性操作（写文件、删目录、执行未知 Bash）必须进入审批拦截流程。

### 1.2 彻底抛弃的四大反模式（Anti-patterns to Avoid）
1. **彻底废除 `Run` 概念**：
   * **教训**：v1 将异步事件循环强行包装为 `(agent/run! ...)` 同步函数调用，导致等待审批时线程阻塞（`Thread/sleep` 或阻塞 Promise），且无法自然支持多轮追问与多任务。
   * **Novashka 方案**：系统是长期存活的反应式进程，只有 **Task（业务任务）** 与 **Event Stream（事件流）**。
2. **严禁在状态和命令中夹带函数闭包**：
   * **教训**：v1 曾在 `(:tools run)` 里保存 `:run (fn ...)` 闭包，在 `:decision/complete` 命令里夹带 `:decide-fn`，破坏了数据可序列化与回放能力。
   * **Novashka 方案**：State 和 Decision 必须 100% 是纯 EDN/JSON 数据，执行闭包一律隔离在 Shell 的 Effect Handlers 中。
3. **消除授权（Auth）与执行器的装配割裂**：
   * **教训**：v1 暴露底层的 `:command-executor!` 参数，导致自定义执行器时直接跳过了授权策略。
   * **Novashka 方案**：授权拦截作为微核心的 Interceptor 管道固有环节，不可被任何外部配置绕过。
4. **观察者（Observer）故障安全隔离**：
   * **教训**：前端 WebSocket 断开或日志打印报错会逆向抛出异常，导致 Agent 运行崩溃或误报为大模型网络错误。
   * **Novashka 方案**：所有观察者投影（Sinks）必须包裹在安全屏障内，吞吐异常不得影响主循环。

---

## 2. Novashka v2 核心技术栈与运行时

* **运行环境**：**Bun**（Zig 编写的高性能 JS/TS 运行时）
  * 冷启动时间：< 10ms
  * 进程管理器：Bun 原生 Shell（`Bun.$`），替代 `babashka.process`
  * HTTP / WebSocket 服务器：`Bun.serve`，替代 `org.httpkit.server`
* **编程语言**：**ClojureScript on nbb**（Node Babashka）
  * 解释器：SCI（Small Clojure Interpreter）
  * 依赖管理：`package.json`（npm 包）+ `nbb.edn`（CLJS 库）
* **记忆与事实数据库**：npm **`datascript`**
  * 内存不可变 Datalog 数据库，支持快照、分支推演与声明式查询。
* **状态与事件架构**：**Micro re-frame**（无 DOM 纯净事件循环，自建 ~150 行）。

---

## 3. 核心领域模型与数据契约

### 3.1 实体矩阵（Core Entities）

```
                     ┌───────────────────────────────────────────────┐
                     │               Agent (反应式进程)               │
                     │                                               │
                     │  - Event Log (DataScript 内存事实库)           │
                     │  - Active Tasks (当前进行中的业务任务)        │
                     │  - Workspace Lock (当前工作区写锁持有者)       │
                     └───────────────────────┬───────────────────────┘
                                             │
                       ┌─────────────────────┴─────────────────────┐
                       ▼                                           ▼
            [Task 1: 修复认证 Bug]                      [Task 2: 巡检目录]
            - Status: :awaiting-approval                - Status: :completed
            - Checklist: [X] [ ]                        - Turn: 1
```

### 3.2 数据格式定义（EDN Specifications）

#### 1. Event（客观事实）
```clojure
{:event/id      "evt-01J..."          ;; 单调/UUID
 :event/type    :tool/result          ;; 或 :user/prompt, :llm/responded 等
 :event/task-id "task-01J..."         ;; 关联的任务
 :event/at      "2026-11-19T10:00:00Z";; ISO 时间戳
 :event/data    {:tool "read_file"
                 :outcome :ok         ;; :ok 或 :error
                 :result "file content..."}}
```

#### 2. Task（任务状态）
```clojure
{:task/id           "task-01J..."
 :task/prompt       "读取 README.md 并说明标题"
 :task/status       :active           ;; :created, :active, :awaiting-approval, :completed, :failed
 :task/turn         1                 ;; 当前交互轮次
 :task/max-turns    15                ;; 预算熔断上限
 :task/created-at   "2026-11-19T10:00:00Z"
 :task/plan         [{:id "step-1" :text "检查目录" :done? true}
                     {:id "step-2" :text "读取内容" :done? false}]}
```

#### 3. Decision / Effect 描述（纯数据请示单）
由纯核返回，指示 Shell 执行的副作用：
```clojure
;; 调用工具意图
[:tool/execute {:task-id "task-01J..."
                :tool "read_file"
                :args {:path "README.md"}}]

;; 调用大模型推理意图
[:llm/complete {:task-id "task-01J..."
                :model :gemini-3.8-flash
                :context [...]}]

;; 请求人工审批意图
[:ui/request-approval {:task-id "task-01J..."
                       :action {:tool "write_file" :path "foo.clj"}
                       :reason "即将覆盖敏感文件"}]
```

---

## 4. 核心引擎：Micro re-frame（~150行代码骨架）

在新 Repo 的 `src/novashka/frame.cljs` 中实现此模块。零 React、零 DOM 依赖，提供声明式纯事件循环：

```clojure
(ns novashka.frame
  (:require [promesa.core :as p]))

;; 内部注册中心
(defonce ^:private event-handlers (atom {}))
(defonce ^:private fx-handlers (atom {}))
(defonce ^:private cofx-handlers (atom {}))
(defonce ^:private interceptors (atom []))

;; 全局认知状态 (包含 DataScript db 引用及运行时元数据)
(defonce app-db (atom {}))

;; 1. 注册 API
(defn reg-event-fx [event-id handler]
  (swap! event-handlers assoc event-id handler))

(defn reg-fx [fx-id handler]
  (swap! fx-handlers assoc fx-id handler))

(defn reg-cofx [cofx-id handler]
  (swap! cofx-handlers assoc cofx-id handler))

(defn reg-interceptor [interceptor]
  (swap! interceptors conj interceptor))

;; 2. 内置基础 Co-effects
(reg-cofx :db (fn [coeffects] (assoc coeffects :db @app-db)))
(reg-cofx :now (fn [coeffects] (assoc coeffects :now (js/Date.))))
(reg-cofx :uuid (fn [coeffects] (assoc coeffects :uuid (str (random-uuid)))))

;; 3. 副作用执行调度 (Shell)
(defn- run-effects! [effects dispatch!]
  (p/doseq [[fx-id payload] effects]
    (if-let [handler (get @fx-handlers fx-id)]
      (p/catch (p/do (handler payload dispatch!))
        (fn [err]
          (js/console.error (str "[FX ERROR] " fx-id) err)))
      (js/console.warn (str "[UNKNOWN FX] " fx-id)))))

;; 4. 事件派发主入口 (Dispatch)
(defn dispatch [event]
  (let [[event-id] event
        handler (get @event-handlers event-id)]
    (if-not handler
      (js/console.warn (str "[UNHANDLED EVENT] " event-id))
      ;; 构建共效应环境
      (let [cofx (reduce (fn [acc [id h]] (h acc))
                         {:event event}
                         @cofx-handlers)
            ;; 纯函数执行: 返回 {:db new-db :fx [...]}
            result (handler cofx event)]
        ;; 更新 State
        (when-let [new-db (:db result)]
          (reset! app-db new-db))
        ;; 触发 Effects
        (when-let [effects (:fx result)]
          (run-effects! effects dispatch))))))
```

---

## 5. DataScript 事实库与 Datalog 记忆模型

### 5.1 Schema 定义（`src/novashka/db.cljs`）
```clojure
(ns novashka.db
  (:require ["datascript" :as d]))

(def schema
  {;; 事件事实
   :event/id      {:db/unique :db.unique/identity}
   :event/task    {:db/valueType :db.type/ref}
   :event/type    {:db/index true}
   :event/at      {:db/index true}
   
   ;; 任务实体
   :task/id       {:db/unique :db.unique/identity}
   :task/status   {:db/index true}
   
   ;; 工具调用详情
   :tool/name     {:db/index true}
   :tool/outcome  {:db/index true}})

(defn create-db []
  (d/empty-db schema))
```

### 5.2 状态机演进与 Datalog 上下文投影
当事件到达时，用事务直接入库：
```clojure
(defn append-event [db event]
  (d/db-with db [(assoc event :db/id -1)]))
```

大模型上下文查询（纯函数 Projection）：
```clojure
(defn project-task-messages [db task-id]
  (let [q-str "[:find (pull ?e [:event/type :event/data :event/at])
                :in $ ?tid
                :where [?t :task/id ?tid]
                       [?e :event/task ?t]]"
        events (->> (d/q q-str db task-id)
                    (map first)
                    (sort-by :event/at))]
    (events->openai-messages events)))
```

---

## 6. Feature Parity 对齐矩阵（与 v1 严格对齐）

Novashka 必须保证在以下 6 个维度上完全复刻并超越 Botashka v1：

| 特性模块 | Botashka v1 现状 | Novashka v2 规范实现 |
|---|---|---|
| **1. 核心五工具** | `list_files`, `read_file`, `write_file`, `search_text`, `run_command` | 完全保持相同命名、参数 Schema（Malli）与返回结构。执行改用 `Bun.$` 驱动。 |
| **2. 工具参数校验** | Malli 校验输入，捕获结构化错误 `:invalid-arguments` | 使用相同的 Malli Schema，验证失败输出纯错误事件，不执行物理命令。 |
| **3. 模型客户端** | Gemini Flash / Claude 调用，SSE 流式读取 | 使用原生 `fetch` 与 `ReadableStream` 消费 SSE，通过 `[:ui/stream-token delta]` 派发非入账流。 |
| **4. 快慢决策协同** | System 1 Clef（只读快速反射）+ System 2 LLM | `clef-policy` 纯策略：置信度高直接产出 `[:tool/execute ...]`，否则退回 `[:llm/complete ...]`。 |
| **5. 安全人机审批** | 敏感工具判定，等待 300 秒，允许/拒绝 | 纯策略判定 `:ask` 产生 `[:ui/request-approval ...]`，非阻塞等待外部 `:user/approval-decision` 事件。 |
| **6. 交互界面** | CLI `--graph` ASCII Trace 图；Web Console (Scittle) | CLI 支持 `-g/--graph` 投影；Web 服务器使用 `Bun.serve` 原生 WebSocket 驱动 Scittle 前端。 |

---

## 7. 分阶段实施路线图与验收命令（Milestones）

每个阶段必须通过确定性测试后才推进到下一阶段。

### Phase 1: 运行时与事件核心引擎启动
* **目标**：建立项目骨架，验证 Bun + nbb 能够运行，并跑通 Micro re-frame 单测。
* **产出文件**：`package.json`, `nbb.edn`, `src/novashka/frame.cljs`, `test/novashka/frame_test.cljs`
* **验收命令**：
  ```sh
  bun test.cljs
  ```
* **通过标准**：`dispatch` 一个测试事件，能够正确更新 `app-db` 并触发已注册的 `:fx`。

### Phase 2: DataScript 记忆事实库集成
* **目标**：验证在 nbb 中完成 DataScript Schema 初始化、事务写入与 Datalog 查询。
* **产出文件**：`src/novashka/db.cljs`, `src/novashka/context.cljs`, `test/novashka/db_test.cljs`
* **验收用例**：写入 10 个测试事件，使用 Datalog 成功按 `:task-id` 和 `:tool/outcome` 抽取出结构化结果并生成 OpenAI 格式消息。

### Phase 3: 五大核心工具移植（基于 `Bun.$`）
* **目标**：移植 `tools/` 目录下的 5 个工具，使用 Malli 校验与 `Bun.$` 执行。
* **产出文件**：`src/novashka/tools/*.cljs`, `src/novashka/tools.cljs`
* **验收用例**：
  * 读取现有 `README.md`，返回文本；
  * 列出根目录，验证返回文件列表；
  * 执行 `run_command ["echo" "hello"]`，捕获 stdout。

### Phase 4: 模型适配器与决策流（双脑协同）
* **目标**：基于原生 `fetch` 实现大模型客户端与 Clef 客户端，注册为 `:fx`。
* **产出文件**：`src/novashka/llm.cljs`, `src/novashka/clef.cljs`, `src/novashka/policy.cljs`
* **验收用例**：
  * 模拟测试：给入 Mock 响应，验证 Event-Action 循环连续运转，正确完成问答；
  * 真实测试：运行单次提示词，验证真实 Gemini Flash API 返回正确结果。

### Phase 5: 授权拦截与审批流
* **目标**：实现针对写操作与命令执行的纯授权拦截器与外部审批唤醒。
* **产出文件**：`src/novashka/auth.cljs`
* **验收用例**：
  * 触发 `write_file` 时，自动进入 `:awaiting-approval`；
  * 未批准前物理文件不改变；派发批准事件后完成写入。

### Phase 6: CLI 交互与 Web 控制台（Feature Complete）
* **目标**：移植 ASCII Trace 渲染器与基于 `Bun.serve` 的 Web 控制台。
* **产出文件**：`src/novashka/cli.cljs`, `src/novashka/server.cljs`, `src/novashka/trace.cljs`
* **验收命令**：
  ```sh
  # CLI 模式
  bun run src/novashka/cli.cljs -g "Read README.md and state title"
  
  # Web 模式
  bun run src/novashka/server.cljs -p 7890
  ```

---

## 8. 新项目文件目录树（Project Layout）

在新 Repo 中直接建立如下标准目录：

```text
novashka/
├── package.json               # 管理 npm 依赖 (datascript 等)
├── nbb.edn                    # 管理 CLJS 依赖 (malli 等)
├── README.md
├── src/
│   └── novashka/
│       ├── core.cljs          # 统一主入口
│       ├── frame.cljs         # Micro re-frame 事件调度引擎 (~150 行)
│       ├── db.cljs            # DataScript Schema 与事务管理
│       ├── context.cljs       # 针对 LLM / Clef 的 Datalog 投影
│       ├── policy.cljs        # 纯动作决策策略 (System 1/2 仲裁)
│       ├── auth.cljs          # 纯安全授权判定与敏感工具规格
│       ├── llm.cljs           # 大模型 HTTP/SSE 适配器 (:fx)
│       ├── clef.cljs          # 快速决策模型客户端 (:fx)
│       ├── tools.cljs         # 工具注册表与 Malli 验证
│       ├── tools/
│       │   ├── list_files.cljs
│       │   ├── read_file.cljs
│       │   ├── write_file.cljs
│       │   ├── search_text.cljs
│       │   └── run_command.cljs
│       ├── trace.cljs         # ASCII Trace 轨迹图生成器
│       ├── server.cljs        # Bun.serve HTTP + WebSocket 服务
│       └── cli.cljs           # 命令行驱动入口
├── test/
│   └── novashka/
│       ├── frame_test.cljs
│       ├── db_test.cljs
│       ├── tools_test.cljs
│       └── integration_test.cljs
└── resources/
    └── public/                # 移植自 botashka v1 的 Web 前端静态资源
        ├── index.html
        ├── app.cljs           # Scittle 浏览器端交互逻辑
        └── botashka.css       # 或 novashka.css
```

---

## 9. 给接手 Coding Agent 的开工指令（Prompt Template）

在新仓库初始化完毕后，你可以将以下指令直接投喂给新 Agent：

> **“你好，请阅读本仓库中的 `docs/HANDOFF-NOVASHKA.zh.md`。本项目基于 Bun + nbb + DataScript 构建新一代反应式 Coding Agent `Novashka`。请严格遵循文档中的架构蓝图与契约，从 Phase 1 开始执行，首先建立 `package.json`、`nbb.edn` 以及 `src/novashka/frame.cljs`（Micro re-frame 引擎），并运行测试证明其可用性。”**
