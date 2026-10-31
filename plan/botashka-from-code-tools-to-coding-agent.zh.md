# Botashka 实施计划：从 code tools 到可用的本地 coding agent

## 目标

构建一个在单一明确 workspace 中工作的单用户 Clojure/Babashka coding agent：它拥有有界的 model/tool loop、自描述 Babashka tool artifacts、可检查的持久 run records，以及 approval-gated mutation workflow。

Pi 是设计证据，不是实现目标。详细分析见 [`docs/pi-capability-analysis.zh.md`](../docs/pi-capability-analysis.zh.md)。采用 Pi 的连贯 loop 与 durable event record 思想；不复制其 TypeScript package topology、TUI、extension host、无约束 OS-permission model 或 QuickJS code mode。

## 当前基线

已完成：

- `botashka.agent/run!` 是顺序、event-driven state machine；`model-driver` 是默认的 pure driver，亦可注入 deterministic driver。
- `botashka.model-client/complete!` 隔离当前 OpenAI-compatible model HTTP protocol。
- `tools/read-file.clj`、`tools/list-files.clj` 是独立 artifacts，遵守 EDN stdin/stdout contract。
- artifact 对 `{:command :describe}` 返回 tool name、description 与 JSON-shaped parameter schema。
- `botashka.tools/code-tool-registry` 发现 `tools/*.clj`，读取 metadata，并用 `botashka.tool-runtime` 以 `bb` subprocess 执行。
- agent 已能在文件缺失后列出 workspace，再读取正确文件。

当前缺口：metadata 与 arguments 尚未由 host 验证；tool process 没有 host-enforced deadline/output cap；run event log 只存在内存；没有 CLI、cancellation、明确 model-error terminal result、或持久化 run store；也没有 mutation policy、preview/approval、patch application 与 verification。

## v1 产品边界

给定 workspace 与 prompt，Botashka 通过已批准的 read-only code tools 检查 workspace、说明发现，并留下 append-only local run record，记录每个 host-observed decision 和 result。

### 不变量

1. model 只提出 tool call；host 负责 validate、authorize、execute、record。
2. tool 从 stdin 接收一个 EDN map，并向 stdout 写一个 EDN result map；诊断只能写 stderr。
3. tool input 包含 absolute canonical workspace root；workspace tools canonicalize 后拒绝 root 外路径。
4. model 不会因生成文本或 metadata 而获得 arbitrary shell、filesystem、process、network access。
5. terminal status 只能如实为 `:completed`、`:step-limit`、`:cancelled`、`:rejected`、`:provider-error` 或 `:failed`。
6. trace 记录 host facts；不保存无限 file contents、credentials 或冒充事实的 model claim。
7. mutation 串行执行，必须 preview 加明确 approval；denial 返回 tool result 且不改变工作树。

### 非目标

不做 Pi-compatible TUI/RPC、plugin/extension host、marketplace、多-provider catalog、arbitrary shell fallback、in-process `load-string`、MCP/OAuth、remote catalogs、session branch、automatic compaction、parallel dispatch、subagents 或 workflow graphs。

## 目标 modules 与 seams

| Module | Interface | 负责隐藏的复杂性 | 初始 adapter |
| --- | --- | --- | --- |
| `model-client` | `complete!(request) -> model response` | HTTP、credentials、timeout/error classification | 现有 OpenAI-compatible endpoint |
| `driver` | `run -> command-or-nil` | 下一步决策策略 | `model-driver`；未来 deterministic driver |
| `tool-runtime` | `describe(program)`、`invoke(program,input,limits)` | subprocess、EDN protocol、deadline、output limits、failure conversion | `bb` subprocess |
| `tool-registry` | `discover(tool-dir) -> registry`, `validate(call)`, `execute(call, context)` | metadata/argument/output validation、duplicate name、lookup | `tools/*.clj` |
| `policy` | `decide(proposed-call,run) -> :allow/:ask/:deny` | capability class、workspace restriction、approval state | read-only allow；其他 deny |
| `run` | `run(options) -> terminal-result` | event transition、command execution、limits、terminal transition | event-driven `botashka.agent/run!` |
| `trace-store` | `start!`、`append!`、`finish!` | run ID、atomic append、redaction、result file | 每个 run 一个目录 |
| `cli` | args/stdin -> terminal result/rendered output | workspace resolution、human confirmation、exit code | Babashka command |
| `mutation` | `propose`、`approve`、`apply`、`verify` | patch identity、preview、approved application、rollback | workspace 内 unified diff |

`provider`、tool artifacts 与 `tool-runtime` 已存在。至少出现两个有实际 start/stop dependency 的 durable resources 前，不引入 component manager。

## 交付顺序

### Phase 1 — 强化 code-tool protocol

**问题。** discovered tool 可提供任意 metadata 并输出任意大小 stdout；host 目前只识别 process/EDN decode failure。

**实施。**

1. 用 `clojure.spec.alpha` 定义 artifact description、invocation input、normal result、host-generated error result 的稳定 specs。
2. `describe!` 验证 name/description 非空、parameters 为 JSON object、无未知 contract field。
3. registry 将 malformed artifact 与 duplicate name 记录成 structured discovery failure，且不暴露给 model。
4. `tool-runtime` 增加 configurable timeout、bounded stdout/stderr；将 timeout、nonzero exit、malformed EDN、oversized output 转换为不同 structured error。
5. tool process 启动前验证 JSON arguments 是否符合 artifact declared input contract；先支持当前 tools 所用 schemas，不提前实现通用 JSON Schema engine。
6. 两个现有 tools 的 result shape 稳定后，再加入 optional declared output schema 及 host-side validation。

**验收。** 确定性检查证明 malformed metadata、duplicate names、invalid arguments、outside-workspace path、malformed output、timeout、output overflow 都只产生一个 structured tool result，不会使 run 崩溃；`read_file`、`list_files` 仍可发现和执行。

**非目标。** generated source safety proof。subprocess 只隔离 agent process state，不隔离用户机器。

### Phase 2 — 将 agent loop 变为真实的 run state machine

**问题。** `agent/run!` 混合 provider wire handling、tool dispatch、terminal state；provider failure 可能被误当作 malformed assistant message。

**实施。**

1. 定义 run options、normalized provider response、tool proposal/result、trace event、terminal result 的 data contracts。
2. provider adapter 返回 completion 或 classified provider failure；loop 结束为 `:provider-error`，不能 throw 或杜撰 final answer。
3. 在 proposed call 与 invocation 间插入 registry validation/execution 和 policy decision。
4. 保留 provider 所需的 assistant tool-call message 与 tool-result message pairing。
5. 在 turn cap 外增加 run deadline 和 cancellation signal；取消必须终止 active tool process，并确定性产出 `:cancelled` 或 deadline failure。
6. 继续保持 sequential calls；单一 trace sequence 才能使顺序与后续 approval 可测试。
7. 添加 fake provider seam 和确定性 tests：completion、unknown tool、bad JSON、rejection、tool/provider failure、step limit、deadline、cancellation。

**验收。** fake provider 不依赖网络即可驱动每个 terminal state；真实 smoke 通过 discovered tools 读取 `README.md` 并返回 title，得到完整 in-memory trace。

**非目标。** streaming rendering、queued/steering messages；Pi 需要它们是因 interactive CLI，Botashka 先需要可靠 terminal state machine。

### Phase 3 — 持久化可检查 run，并提供 CLI

**问题。** REPL-only in-memory trace 无法在失败后检查，也不能作为行为变化证据。

**实施。**

1. 每个 CLI run 创建 `runs/<run-id>/`，写 `request.edn`、append-only `trace.edn` 或 JSONL、最终 `result.edn`。
2. event 发生时原子追加；terminal result 只写一次；持久化前 redact credentials 并 truncate large tool payload。
3. 增加小型 Babashka CLI：prompt、workspace、model override、turn/deadline limits、run directory；显示 tool activity 与 final status。
4. 第一次 model request 前显示 canonical workspace 和 discovered read-only tools，并记录可见 session-level consent。
5. 提供 `--run <id-or-path>` 只读检查 stored trace；resume 延后。
6. 将 terminal status 映射为文档化 process exit codes。

**验收。** CLI 即使 provider/tool failure 也在执行中创建 run directory；rendered trace 与持久化顺序一致；inspection 不发 provider request。

### Phase 4 — 增加 read-only coding tools 与 scenario evaluation

**问题。** 两个 navigation tools 足以演示 loop，但不足以可靠地检查普通 repository。

**实施。**

1. 增加 `tools/search-text.clj`：literal query 与 workspace-relative path，返回有上限且稳定的 path、line range、excerpt；不先暴露 arbitrary regex/shell syntax。
2. 每个 tool 的 artifact 内保留简洁 operational contract：capability class、expected errors、maximum output、executable smoke input。
3. 建立 fake provider 驱动的固定 local scenario corpus：读已知 title、missing-file recovery、outside-root rejection、malformed arguments、turn/deadline limit、literal search 后读选中文件。
4. 用一个文档化 command 运行 corpus；只保存 deterministic fixtures/fake responses；real-provider smoke 单独运行。

**验收。** corpus 能捕获 broken provider-message pairing、tool-policy violation、trace order 错误、错误 terminal status；真实 CLI smoke 可经 `list_files`、`search_text`、`read_file` 回答 repository question。

### Phase 5 — 经 generated code tools 的受控 mutation

**问题。** coding agent 的价值在于能修改 repository；但 direct generated source/unrestricted writes 会隐藏意图，且 rollback/verification 薄弱。

**实施。**

1. 增加 `tools/propose-patch.clj`：接收 intended unified diff 和 original file identity，验证 targets 均在 normalized workspace 内，返回 canonical preview；绝不 mutation。
2. host 持有 approval token，绑定 run ID、canonical patch digest、workspace、expiration；model 不能创建或更改。
3. 增加 `tools/apply-patch.clj`，仅接受有效 token，且只能应用该 approved digest；拒绝 stale content、changed baseline、outside paths。
4. 所有 mutation 的 policy 均为 `:ask`；CLI 显示完整 diff、affected paths、verification command 后接受 yes/no。
5. approved application 后，只运行 user-approved command 或 narrow declared project check；trace 记录 command、exit code、bounded output、最终 diff。
6. model 创建新的 Babashka tool source 时，只能作为上述 workflow 中的不可信 proposed patch；进入 `tools/` 前必须通过 Phase 1 discovery validation 和 explicit human review/approval。

**验收。** rejected mutation 使 tree byte-for-byte 不变；approved fixture patch 只改 declared in-workspace files，baseline mismatch 被拒绝，verification result 被记录；人类可先审阅 proposed tool 的 source、metadata、smoke input。

**非目标。** arbitrary `bash`、任意 overwrite、自动 commit/push、unattended generated-tool execution。

### Phase 6 — 仅在证据支持时增加 context control 与 interoperability

当 traces 表明多步骤任务丢失进度时，增加 explicit task list；先做 bounded context selection，必须保留 system instruction、active task list、policy decisions、完整 tool-call/result pairs；再将 compaction 做成新的 append-only summary fact。只有存在具体 required server 时评估 MCP，并要求明确 trust、narrow capability config、connection lifecycle ownership、与 native tools 等价的 policy/approval。只有 native/MCP catalog 确实超 provider tool budget 时，才增加 host-controlled `search-tools` / `describe-tool`。

**验收。** context tests 证明 pairing/policy facts 在 selection/compaction 后仍存在；每个 MCP addition 有一个正当 user task 与确定性 connection/policy failure tests。

**非目标。** Pi code mode、session trees、generic extension loading、multi-agent delegation、parallel mutation、workflow engine；必须先证明 sequential run 无法表达需求。

## 下一实施切片

执行 **Phase 1 — 强化 code-tool protocol**。

理由：当前方向已明确为 self-contained Babashka artifacts，但尚缺 host-side contract enforcement。若不先补齐它，就不应继续增加 tools、CLI/persistence surface 或 model-authored artifacts。它也会建立后续 phases 所需的确定性 test seam。

```text
artifact metadata specs
→ bounded describe/invoke subprocess runtime
→ discovery / duplicate error handling
→ argument validation
→ deterministic hostile-input tests
→ real read/list tool smoke
```

## 从 Pi 得出的决策

| Pi 机制 | Botashka 决策 | 原因 |
| --- | --- | --- |
| Agent loop 与 provider boundary | 现在采用 | 与现有 `agent`/`provider` split 一致；补足 terminal/error transition。 |
| Append-only session/event data | 现在采用；先 linear per-run | 保留证据，不引入 session-tree complexity。 |
| Tool registry/discovery | 现在采用；local artifact protocol | 已与 `tools/*.clj` 对齐；metadata 紧邻 executable behavior。 |
| Code mode | 延后 | QuickJS、injected tools、dynamic discovery、parallel aggregation 解决的是后续 scale/context 问题。 |
| Extensions | 默认不照搬 | Pi extensions 有 full process permissions；Botashka 应使用 explicit artifacts、policy、review。 |
| MCP | 延后 | native artifact protocol 稳定后再接受 remote lifecycle/auth/trust complexity。 |
| TUI/RPC/multi-package platform | v1 不照搬 | CLI + persisted trace 对本地 Clojure tool 有更直接 leverage。 |
