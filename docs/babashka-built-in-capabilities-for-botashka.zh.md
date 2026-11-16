# Babashka 原生内置能力挖掘与 Botashka 架构赋能指南

## 概述与核心前提

### 完全不依赖 JVM（Zero JVM Dependency）
《Babashka Book》所列出的全部内置库（Built-in namespaces），**均不需要安装 JDK/JVM，也不受 JVM 冷启动开销影响**。

Babashka 是基于 Oracle GraalVM Native Image 编译的自包含原生可执行文件（Self-contained native binary）。上述所有 Clojure/Java 库在构建 `bb` 二进制时，已经被 AOT 编译为目标平台的机器码（如 macOS arm64、Linux x86_64）。因此：
- **启动时间**：毫秒级（约 10~20ms）；
- **内存占用**：极低（单进程仅数十 MB）；
- **环境要求**：只要系统上有 `bb` 命令，全部功能开箱即用，无需配置 Java 环境或下载 Maven/JAR 依赖。

---

## 挖掘出的 7 大原生能力及在 Botashka 中的应用

### 1. 代码理解与 AST 精确修改：`rewrite-clj` 与 `edamame`
- **对应内置库**：
  - `rewrite-clj.parser` / `rewrite-clj.zip` / `rewrite-clj.paredit`
  - `edamame.core`（带元数据与位置信息的 Clojure 解析器）
- **过去做法与局限**：
  此前修改代码主要依赖 `write_file` 全量覆写或简单字符串匹配，容易破坏原有代码缩进、格式或删除上下文注释。
- **对 Botashka 的赋能**：
  - **语义级代码重构工具 (`refactor_clojure`)**：利用 Zipper 树在保持原有注释、空格、缩进 100% 不变的前提下，精确增删改特定函数（`defn`）、命名空间依赖（`ns`）或配置项。
  - **静态语法安全守门**：在写文件前后，直接调用内置解析器进行快速语法校验，拦截模型产生的未闭合括号或语法错误。

---

### 2. 网页文档阅读与无头抓取：`Jsoup` 与 `nextjournal.markdown`
- **对应内置库**：
  - `org.jsoup.Jsoup`（顶级 HTML 解析与 DOM 抽取库）
  - `nextjournal.markdown`（结构化 Markdown 语法树解析）
- **过去做法与局限**：
  Coding Agent 查阅外部文档需要依赖重量级外部无头浏览器（Headless Chrome / Playwright）或 curl，返回大量含垃圾样式的原始 HTML。
- **对 Botashka 的赋能**：
  - **原生 Web/Doc 阅读工具 (`fetch_web_doc`)**：直接用内置 `Jsoup/connect` 抓取技术文档，快速提取正文主体文本，毫秒级清理 `<script>`、`<style>` 和导航广告，返回干净紧凑的上下文给大模型。
  - **结构化需求与任务解析**：直接将 Markdown 格式的设计规范、PRD 解析为 Clojure 数据结构。

---

### 3. 零依赖轻量服务化与 SSE：`org.httpkit.server`
- **对应内置库**：
  - `org.httpkit.server`
  - `org.httpkit.client`
- **过去做法与局限**：
  考虑将 Botashka Core 与独立 UI（如 Web 前端、Go 编写的 Crush 终端）解耦时，曾担心为了提供 HTTP/SSE 服务而需要引入重量级 Web 框架或 JVM 依赖。
- **对 Botashka 的赋能**：
  - **极简事件流守护进程 (`bb serve`)**：利用 `http-kit`，仅需十几行代码即可启动一个支持 Server-Sent Events (SSE) 和 WebSocket 的轻量服务器。
  - 任何第三方 UI 或客户端只需 `POST /prompt` 提交任务，通过 `GET /events` (SSE) 监听流式事件，真正实现核心架构的 Functional Core 与交互外壳的彻底解耦。

---

### 4. 高级终端交互与自适应渲染：`JLine3` 与 `babashka.terminal`
- **对应内置库**：
  - `org.jline`（终端行编辑与交互库）
  - `babashka.terminal/tty?`
- **过去做法与局限**：
  `bb chat` 目前采用最基础的 `read-line`，不支持按方向键回溯历史输入，且不知道当前是否处于管道重定向中。
- **对 Botashka 的赋能**：
  - **终端历史持久化**：使用内置 JLine 启用输入历史（如 `~/.botashka_history`），在终端按 `↑`/`↓` 可直接翻看和修改上一次的 Prompt。
  - **环境自适应（TTY 探测）**：利用 `(terminal/tty? :stdout)`：交互式终端下输出 ANSI 彩色打字流；被脚本管道调用（如 `bb chat | jq`）时自动降级输出无色纯净的数据流。

---

### 5. 服务拉起与健康检查等待：`babashka.wait`
- **对应内置库**：
  - `babashka.wait/wait-for-port`
  - `babashka.wait/wait-for-path`
- **过去做法与局限**：
  Agent 在工作区启动外部开发服务器（如 `npm run dev`、`python -m http.server`）或等待构建产物时，常常写出容易超时的 shell 循环或固定 `sleep`。
- **对 Botashka 的赋能**：
  - **确定性端口就绪判定**：`(wait/wait-for-port "localhost" 8080 {:timeout 5000})`，非阻塞高效轮询。
  - **确定性文件落地等待**：`(wait/wait-for-path "dist/bundle.js" {:timeout 3000})`。

---

### 6. 数据驱动模式匹配：`clojure.core.match`
- **对应内置库**：
  - `clojure.core.match`
- **对 Botashka 的赋能**：
  - 在 `run.clj` 的事件处理（`apply-event`）和策略推断（`action-policy`）中，以高度声明式的方式进行结构模式解构，消除冗长的 `cond` / `if-let`。

---

### 7. 插件扩展与轻量持久化：`Pods` 机制
- **对应内置机制**：
  - `babashka.pods`（基于进程间 IPC 的双向 RPC 协议）
- **对 Botashka 的赋能**：
  - **本地 SQLite 向量/历史记忆库**：声明 `org.babashka/go-sqlite3` pod，无需 JVM JDBC，直接获得高速原生 SQLite 存储。
  - **文件系统监听与实时热重载**：声明 `org.babashka/fswatcher` pod，实时监听工作区文件变动，感知用户外部手动编辑。

---

## 落地演进规划推荐

1. **短期易得收益 (P1)**：
   - 为 `bb chat` 引入 `JLine` 历史记录与持久化文件；
   - 增加 `babashka.terminal/tty?` 判定以优化输出格式。
2. **工具链扩展收益 (P2)**：
   - 基于 `Jsoup` 封装内置 `fetch_web_doc` 工具，赋予 Agent 免依赖查阅在线文档能力。
3. **架构解耦收益 (P3)**：
   - 基于 `org.httpkit.server` 增加 `bb serve` 命令，对外提供标准的 Event SSE 端点。
4. **精确重构收益 (P4)**：
   - 基于 `rewrite-clj` 实现 Clojure 代码的 AST 精确修改工具。
