# Botashka domain glossary

## Core terms

- **Agent run** — one bounded attempt to answer a user prompt. It owns the provider conversation, ordered run event log, terminal status, and any run-scoped cancellation state.
- **Conversation** — the provider-compatible sequence of user, assistant, and tool-result messages derived from canonical run events. It is protocol state, not the run event log.
- **Run event log** — the canonical ordered sequence of immutable execution facts for one agent run. It is local audit evidence and the input to pure run-state/read-model derivation; it is not a filesystem snapshot, text log stream, or OpenTelemetry trace.
- **Run event** — one canonical fact in a run event log. Its envelope identifies the owning run, monotonic sequence, occurrence time, event type, event-specific data, and optional local evidence.
- **Command** — a pure description of the one external action currently permitted by derived run state. A command is intent, not a fact; the shell records an observed result event only after it executes the action.
- **Evidence payload** — bounded local diagnostic material attached to a run event or stored as a payload artifact: for example a prompt, tool arguments, tool result, final answer, or redacted exception detail. The default policy retains prompts, final answers, and structured tool arguments; it retains file content and tool stdout/stderr only as summaries unless capture is explicitly enabled. It MUST NOT automatically enter an external projection.
- **Observation projection** — a non-authoritative rendering or export of run events, such as terminal output, OpenTelemetry spans/logs/metrics, or a UI update. A projection uses an allowlisted, redacted summary; its failure MUST NOT alter an agent run's control flow or terminal result.
- **Observation sink** — an adapter that consumes run events to produce one observation projection. It does not own agent state or make execution decisions.
- **Tool call** — the provider's proposal to invoke a named host capability with JSON arguments. It is not execution.
- **Tool result** — the host's structured result or structured failure for one tool call, associated by the provider call ID.
- **Tool registry** — the available named capabilities for a run. A registry entry supplies provider schema and a host handler. The registry does not decide whether a capability is allowed.
- **Workspace** — the filesystem root against which workspace tools resolve paths.
- **Provider adapter** — the module that translates Botashka's request data to and from one model-provider protocol. It owns provider HTTP details, never agent control flow.
- **Terminal result** — the final output of an agent run. Its status is `:completed` or a host-enforced terminal condition such as `:step-limit`.

## Domains

- **Run orchestration** — agent run state transitions, conversation progression, stopping conditions, cancellation, and event emission. Current namespace: `botashka.agent`.
- **Provider integration** — model request/response protocol adapters, streaming, model metadata, usage, and provider failure classification. Current namespace: `botashka.provider`.
- **Tool execution** — capability schemas, argument decoding, handler invocation, output shaping, tool deadlines, and execution failures. Current namespace: `botashka.tools`.
- **Workspace access** — workspace-relative file navigation, searching, reading, later patches and verification. It is a tool-execution subdomain, not run orchestration.
- **Context management** — selection, bounding, compaction, and preservation of conversation facts across turns or resumptions.
- **Task control** — explicit task state, retry/recovery policy, user cancellation, pause/resume, and later plans/checklists.
- **Run observation** — durable run-event evidence, redacted projection rendering, event streaming, metrics, external observability exporters, and later TUI projection. It consumes execution facts; it does not control agent decisions.
- **Mutation workflow** — proposed patch, preview, approval, apply, verification, and rollback for changes to a workspace.
- **Interaction** — REPL exploration now; later TUI and human approval surfaces. It presents run state and submits user intent; it does not implement provider or tool protocols.
- **Tool discovery** — later selection of a bounded candidate set from large local/MCP tool catalogs.
- **Workflow orchestration** — later explicit graphs, delegation, ownership, and shared state when one sequential agent run is no longer sufficient.

## Component lifecycle

A **component** is a runtime resource with explicit start/stop ownership, configuration, and dependencies. Pure data transformations and pure handlers are not components.

Current runtime modules are deliberately not lifecycle-managed components: the provider adapter is a pure request function, the tool registry is run input, and an agent run is ephemeral data plus control flow.

Components appear only when state or resources survive one function invocation:

| Component | Domain | Dependencies | Start / stop responsibility | Introduce when |
| --- | --- | --- | --- | --- |
| Provider client | Provider integration | provider configuration, credential source, HTTP resources | Construct/start shared client; close it on system stop if the client owns resources | connection pooling, streaming, or multiple provider adapters make per-call construction inappropriate |
| Tool runtime | Tool execution | workspace, process runner, timeout scheduler | Allocate/close persistent external resources used by tools | tools own subprocesses, watchers, or durable handles |
| Run store | Run observation | filesystem/database, serializer | Open storage; flush/close on stop | traces must survive the REPL process or runs must resume |
| Event publisher | Run observation | run store and optional exporter configuration | Start output/export workers; drain/stop predictably | traces must be streamed to a TUI, Phoenix, OTLP, or another consumer while a run executes |
| Context store | Context management | run store, summarizer/provider adapter | Load/save snapshots; release storage resources | context must resume across runs or sessions |
| Task controller | Task control | context store, event publisher | Restore/persist task state; cancel outstanding work on stop | pause/resume, user cancellation, or durable plans are needed |
| Mutation executor | Mutation workflow | workspace, diff/patch engine, verifier | Create temporary workspace resources; clean them on stop | first approved edit/patch workflow |
| TUI runtime | Interaction | event publisher, task controller | Own terminal session; restore terminal on stop | REPL no longer supplies sufficient interactive visibility/control |
| Connector manager | Tool discovery | MCP/connector configuration, provider client | Connect/disconnect external servers and refresh catalogs | a justified external connector is added |
| Workflow engine | Workflow orchestration | task controller, event publisher, component system | Start/stop graph workers and drain work | specialization, routing, parallelism, or rollback cannot remain a readable sequential run |

## Architectural principles

- **Data first.** Requests, tool schemas, tool calls, tool results, traces, terminal results, component configuration, and component dependencies are immutable maps/vectors. Functions transform values; only explicit lifecycle edges perform effects.
- **Facts over logs.** A trace records structured execution facts. Text logs and external telemetry are projections of those facts.
- **State has an owner and scope.** Run state belongs to one agent run; shared resources belong to a named component; durable facts belong to a store. Do not use namespace Vars, REPL history, or hidden global atoms as application state.
- **Pure core, thin effect shell.** Parse/normalize/route/select/render functions stay pure and REPL-evaluable. Provider calls, filesystem access, processes, persistence, and UI live at named effect seams.
- **Immutable handoff.** Concurrent workers exchange immutable commands, events, and snapshots. Atoms hold a single coherent state when synchronous coordination is enough; queues/channels carry asynchronous work; neither replaces explicit ownership.
- **Deterministic lifecycle.** Components declare dependencies. Start in dependency order; stop in reverse order. A reload/reset reconstructs application state without restarting the REPL.
- **Specs at data boundaries.** Add `clojure.spec.alpha` for public request/result/event/configuration data once its shape is stable through REPL exploration. Specs validate and explain data; they do not substitute for authorization or execution policy.
- **Parallel only when semantics permit.** Parallelize independent read-only tool calls or independent workflow nodes only after their ordering, result aggregation, cancellation, error, and trace semantics are defined. Mutation remains serial until proven otherwise.
- **One component manager, chosen late.** The candidates are Component/reloaded.repl, Integrant/integrant-repl, and Mount. Select one when two or more lifecycle-managed components with real dependencies exist. Do not add a manager merely to wrap pure functions or a one-off atom.
