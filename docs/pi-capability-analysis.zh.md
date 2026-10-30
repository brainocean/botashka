# 面向 Botashka 的 Pi 能力分析

本文基于本地 Pi 仓库源码和文档，区分可复用的交互/运行时思想与 Pi 特有的 TypeScript/Node 产品表面。

## 主要包与分层

Pi 是 TypeScript monorepo；面向用户的产品是 `@earendil-works/pi-coding-agent`。它通过独立 package 组合能力，而不是把所有行为塞进 CLI。其包表包含 `pi-ai`（多 provider LLM 接口）、`pi-agent-core`（tool calling 和状态运行时）、`pi-coding-agent`（交互式 coding CLI）、`pi-tui`、`pi-durable`、`pi-telemetry` 与 `chord`；workspace 还构建 `pi-codemode`、`pi-mcp` 与 `pi-env`。来源：`/Users/I067908/Documents/code/ai-ref/pi/README.md:72-86`、`/Users/I067908/Documents/code/ai-ref/pi/package.json:5-17`。

| Pi 包/层 | 职责 | 对 Botashka 的意义 |
| --- | --- | --- |
| `pi-ai` | 在统一接口后规整多 provider 模型与流式输出。来源：`README.md:78-83`。 | 保留狭窄 provider seam；只实现 Botashka 实际需要的 provider。 |
| `pi-agent-core` | 保存 transcript、模型、tools、流式状态、消息队列与 tool-calling loop。来源：`README.md:81-83`、`packages/agent/src/agent.ts:188-203`。 | 最有价值的概念 seam：将近乎纯粹的 loop/state core 与 Babashka UI、tool adapters 分开。 |
| `pi-coding-agent` | 提供 `pi` CLI、内置 coding tools、session 管理、TUI/RPC/print interfaces、resources 与 extensions。来源：`packages/coding-agent/package.json:2-21`、`docs/how-pi-works.md:33-45`。 | 复用工作流思想，不复制大型一体化终端产品。 |
| `pi-codemode` | 在 QuickJS VM 中运行模型写出的 JavaScript；唯一能力来自注入 tools，只有选择后的输出回到模型。来源：`packages/codemode/README.md:1-5`。 | 是未来聚合/过滤 tool result 的选择，不是 Clojure agent 的前置条件。 |
| `pi-mcp` | 独立 MCP client，包含 OAuth entry point 与测试 helpers。来源：`packages/mcp/package.json:1-24`。 | 有具体 server 需求后才引入 MCP interoperability。 |
| 支撑层 | `pi-tui` 提供差分终端渲染；`pi-durable`、`pi-env`、`pi-telemetry`、`chord` 分别提供 durable runtime、环境、telemetry 与 plugin/RPC。来源：`README.md:77-85`、`package.json:16-17`。 | 不把其架构广度引入单用户 Babashka 工具。 |

## Coding-agent 用户工作流

Pi 从工作目录启动；该目录限定文件、instruction、config discovery，并决定默认 session 分组。用户通过 `/login` 认证后提交任务。来源：`README.md:45-53`、`packages/coding-agent/docs/usage.md:1-13`。终端 transcript 显示 prompt、response、tool call、result 和 error；status 显示目录、session、模型、context usage、usage 与 cost。来源：`usage.md:7-10`。

交互语义是刻意设计的：`Enter` 发送 prompt；`Alt+Enter` 在当前任务后排队；运行中普通消息在本 turn/tool calls 后引导下一个 model response；`Escape` 中断并将排队消息放回 editor。来源：`usage.md:29-40`。用户也可切换模型/thinking level，创建、恢复、命名和检查 session，用 `/tree`、`/fork`、`/clone` 分支，并 compact history。来源：`usage.md:44-66`。`!command` 执行本地 shell command 并把输出加入 conversation；`!!command` 则不加入 model context。

Pi 有意不逐个确认 tool call；文档要求用户检查 command 和 changed files，并把不可信或无人值守工作放进 sandbox。来源：`usage.md:21-27`。

## Agent loop、状态、模型与 tool runtime

Pi 的 loop 很紧凑：向 active branch 追加提交消息；由 system prompt、branch history、tool definitions 与 model settings 组装请求；流式读取并记录 provider response；执行和记录 tool results；只有 tool results 或 queued messages 需要时才发起下一次请求。来源：`packages/coding-agent/docs/how-pi-works.md:7-13`。

实现中，loop 持有 `AgentMessage`，仅在 LLM boundary 转成 provider `Message[]`。来源：`packages/agent/src/agent-loop.ts:1-4`。`Agent` 持有 transcript、lifecycle events、tool execution 和独立的 steering/follow-up queues；默认一次处理一条消息，默认并行执行 tools。来源：`packages/agent/src/agent.ts:149-180`、`:188-203`、`:238-262`。tool calls 可串行或并行，before/after hooks 可以 block 或 transform call。

loop 有明确 request/turn seams：`prepareRequest` 可以在每次 provider call 前替换 context、model 或 thinking level；`prepareNextTurn` 可以在下一 turn 前做同样处理。来源：`packages/agent/src/types.ts:160-189`。provider failure 是返回 event stream 的一部分，而不是从 stream contract 中抛出。来源：`types.ts:19-37`。

**对 Botashka 的结论。** durable transcript 与显式 run status 应当是 data；tools 在小型 capability map 后运行；只有 model-call boundary 知道 provider wire format。无需复制 Pi 的 TypeScript event hierarchy。此结论是从 `agent-loop.ts:172-281` 和 `agent.ts:188-262` 作出的工程推论。

## Code mode 与 tool 创建

Pi 常规 extensibility 路径是 in-process TypeScript extension。extension 能注册 LLM-callable tools、commands、event handlers、UI、renderers、providers、MCP servers 和重启后仍存在的 state；project-local extension 只在 project trust 后加载。来源：`packages/coding-agent/docs/extensions.md:3-19`、`:123-153`。

`codemode` 是另一机制：模型传入 raw JavaScript，它作为 async function 在 QuickJS sandbox 中运行；sandbox 没有 Node APIs、filesystem、network 或 timers。它可调用 injected tools 和 non-chat models，只有脚本输出回到模型，因此能并行调用并在 context 增长前过滤结果。来源：`packages/coding-agent/docs/codemode.md:1-18`。tool discovery 是动态的：脚本可查询 `ALL_TOOLS`、按相关性 search、获取单个 declaration，从而不在 initial prompt 放入大量/MCP tools。来源：`codemode.md:20-35`、`:48-52`。

该 sandbox 是 capability-limited，不是天然安全：injected tools 仍是 external effect 的通道；已发起的 tool call 不会因取消而回滚。来源：`packages/codemode/README.md:47-62`、`codemode.md:15-18`。

## Extensions、MCP 与 session persistence

Pi 会在加载 project settings/resources 前先决定 project trust；启用的 tool 使用 Pi process 的 OS permissions。来源：`how-pi-works.md:47-49`。因此 extension 拥有完整 process permissions，可以读取 prompt、files、credentials 和 session history。来源：`extensions.md:3-9`。

MCP server 可在 user scope `~/.pi/agent/mcp.json` 或在信任后 project scope `.pi/mcp.json` 配置，支持 stdio 与 streamable HTTP。来源：`packages/coding-agent/docs/mcp.md:1-32`。MCP tools 可 direct、经 tool search deferred、仅在 codemode 可用（默认）或 hidden；这是 Pi 控制 prompt size 与 tool discovery 的机制。来源：`mcp.md:191-202`。

session 是 append-only JSONL tree：entry 有 ID 和 parent ID，current leaf 决定 active branch；compaction 只在重建 model context 时替换旧材料，原始 entries 保留。来源：`how-pi-works.md:25-31`、`session-format.md:1-5`、`:123-147`。默认自动保存到 `~/.pi/agent/sessions/`，按 working directory 分组；`--no-session` 显式表示 ephemeral。来源：`sessions.md:5-18`、`:48-54`。

## 测试与 evals

Pi 运行普通 workspace tests，另有 MCP conformance test command。来源：`package.json:40-42`。coding-agent 和 agent core 都使用 Vitest；仓库可见 agent-loop、agent、end-to-end tests。来源：`packages/coding-agent/package.json:31-41`、`packages/agent/package.json:1`、`packages/agent/test/agent-loop.test.ts:1`。

专门的 eval package 使用 `vitest-evals`：host smoke tests、显式 docs audit、以及 Docker-isolated 的 `without_docs`/`with_docs` 成对 documentation-lift 比较。每个 case/variant/model/run 组合均被规划，每个 arm 在 fresh container 执行；任何 pair blocked 时不报告总体 lift。来源：`packages/evals/README.md:1-20`、`:71-105`。

## 给小型单用户 Clojure/Babashka Botashka 的建议

### 现在采用

- **可见 transcript 与事件流。** 显示 user text、assistant text、tool start/result/error、current model 与 cancellation state。Pi 证明这能使本地自主行为可检查。来源：`usage.md:7-10`。
- **data-first loop。** `transcript → provider request → assistant message → validated tool calls → tool results → repeat`；provider conversion 只在一个 seam，tool execution 在小 registry 后。来源：`how-pi-works.md:7-23`、`agent-loop.ts:1-4`。
- **显式中断/排队语义。** 丰富 UI 之前，先支持 stop、一个 pending steering message 和一个 follow-up queue。来源：`usage.md:29-40`。
- **append-only 本地 JSONL session records。** 先做线性记录，包含 message/tool/model metadata；compaction 新增 summary record，不破坏性重写。Pi 的 tree 证明保留历史的价值，但线性 v1 已满足单用户需要。来源：`session-format.md:1-5`、`:123-147`。
- **先做围绕 loop transition 和 tool validation 的确定性测试，再做 LLM eval。** Pi 将常规 tests 和 behavior evals 并存。来源：`package.json:40-42`、`packages/evals/README.md:109-129`。

### 延后

- **MCP client、OAuth 与 tool-exposure policy。** Botashka 先稳定 native tool protocol，并出现真实 MCP-server 需求后再加入；Pi 的 MCP surface 还包括 user/project config、remote auth、background connections、多种 exposure modes。来源：`mcp.md:30-100`、`:191-202`。
- **branch/fork session trees 与自动 LLM compaction。** 保留 append-only format，只有用户需要 parallel exploration 或 context pressure 确实需要 summary 时才做。来源：`sessions.md:20-46`、`session-format.md:123-162`。
- **Codemode/模型编写 orchestration。** 它解决 parallel fan-out、tool discovery、context filtering，但会增加第二种语言/runtime、sandbox lifecycle、memory/time limits 和 cancellation caveats。来源：`codemode.md:1-18`、`:224-230`。
- **Docker-based comparative LLM evals。** 先采用小型 task corpus 和可重复的 manual/provider checks；Pi 的 paired-container system 属于成熟 documentation/product eval program。来源：`packages/evals/README.md:71-105`。

### 不要照搬

- **Pi 宽广的 package topology、TUI framework、RPC surface、telemetry、durable runtime 和 plugin host。** 它们服务于可分发的通用 TypeScript platform，而不是小型单用户 Babashka tool。来源：`README.md:72-86`、`package.json:16-17`。
- **将不受限的 in-process extension execution 作为 Botashka 默认。** Pi 明确让 extension 获得 process permissions 并依赖 trust；Botashka 应使用固定 native tool registry，对 destructive tools 要求显式确认/policy。来源：`extensions.md:3-9`、`README.md:88-96`。
- **默认“不逐个确认 tool call”。** 仅当用户明确接受 Pi 的 OS-level trust model 时才合适；Botashka 的 write 和 process execution 应保留用户控制，除非另行加入 unattended mode。来源：`usage.md:21-27`。
