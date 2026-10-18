# Shared local Babashka nREPL development environment

**Decision.** **Adopt one developer-owned, local-only Babashka nREPL server for
interactive development**, with both the user and the coding assistant attaching
as nREPL clients. This is a development convenience, not an application runtime,
a background service, or a remote collaboration endpoint.

This note labels direct source statements as **Fact** and the proposed Botashka
practice as **Recommendation**. The repository is presently early-stage, so the
commands and `bb.edn` shape below are a proposed first setup—not files to add or
commands to run as part of this research.

## Why this fits the intended workflow

**Fact — live, focused feedback.** *Running with Scissors* defines the REPL as
“Read, Eval, Print, Loop,” advocates working inside a running program for a fast
feedback loop, and says to load only the namespaces and vars needed for the job
at hand ([slides 8, 19–23, 56][scissors]). Its example explicitly binds a
connection and database value as REPL state; later it recommends rich comment
blocks for sample data, sample invocations, and a durable history of the
development process ([slides 23, 30–32][scissors]).

**Fact — Babashka supplies the server and client connection path.** Babashka
supports an nREPL server via `bb nrepl-server`; it also supports `bb repl
--connect`, which reads `.nrepl-port` by default. The server command writes that
file and removes it when it exits ([Babashka book, nREPL][bb-nrepl]). The
Babashka documentation lists editor clients including Calva, CIDER, and Conjure
([same source][bb-nrepl]).

**Recommendation.** One process provides a concrete runtime in which the user
can explore and the assistant can evaluate small, explicitly requested forms.
That directly supports the presentation's “precision cut” workflow without
turning every small question into a cold process start. Do not start a second
server per client: both clients should discover and attach to the one
project-local server.

## Topology and state boundaries

```text
user's editor / terminal ─┐
                          ├─ nREPL client connections ─> one `bb` process
coding assistant ─────────┘                              (project cwd)
                                                           ├─ namespaces / Vars
                                                           ├─ loaded code
                                                           └─ resources and mutable objects
```

**Fact — each client session has its own dynamic context.** nREPL persistent
sessions, made with `clone`, retain dynamic bindings such as `*ns*`, `*1`, `*e*`,
and `*out*` across evaluations; they have a dedicated thread and remain until
`close`. Requests without a session get a one-message ephemeral session, whose
binding changes are not retained. nREPL advises persistent sessions for
user-facing evaluation and separate user and tooling sessions so `*1` and `*e*`
do not interfere ([nREPL: Building Clients][nrepl-sessions]). Requests in one
session run serially according to the protocol specification ([nREPL protocol
specification][nrepl-spec]).

**Fact — all clients can reach one server process.** The nREPL protocol is a
socket protocol whose `eval` operation runs submitted code; nREPL documents that
multiple clients can receive copies of forwarded system output ([nREPL protocol
specification][nrepl-spec]; [nREPL: Misc Functionality][nrepl-output]).

**Inference — session isolation is not process isolation.** Separate nREPL
sessions protect dynamic REPL bindings and serialize work *within a session*, but
they do not create separate Babashka processes. Therefore namespace mappings and
Vars loaded or redefined in the one process, plus reachable mutable objects,
open resources, and side effects, are shared runtime state. This follows from
nREPL's per-session scope being explicitly dynamic bindings, while the proposed
topology deliberately has one process. Treat any assistant evaluation as able to
observe or change that shared runtime.

**Recommendation — operating rules.**

- Give the user and assistant different persistent sessions. The assistant must
  not reuse the user's session ID or rely on its `*ns*`, `*1`, or `*e*`.
- Use the shared process only for deterministic, narrow exploration: load a
  namespace, call a pure function with explicit example data, inspect a value,
  or run an explicitly requested reset. Do not use it as hidden persistent
  memory or as the sole record of a change.
- Do not issue concurrent state-changing evaluations from user and assistant.
  Agree who is evaluating a reload/reset; wait for its result before the next
  state-changing form.
- Avoid mutable top-level state and irrevocable side effects in ordinary
  namespace loading. Make lifecycle-bearing resources explicit (create, close,
  reset) rather than relying on a `def` evaluated during ad-hoc reload.

## Reload and reset workflow

**Fact — reloading is explicit.** The Babashka book documents `require` with
`:reload` when using a declared replacement version of a built-in library
([Babashka book: libraries][bb-reload]). The presentation recommends loading what
is needed and putting repeatable setup/sample invocations in rich comment blocks
([slides 20, 23, 30–32][scissors]).

**Recommendation — a repeatable manual cycle.**

1. Save the namespace source.
2. In the agreed client session, evaluate `(require 'botashka.some-namespace
   :reload)` (or the editor's equivalent load-file operation) for the namespace
   under change. Reload direct dependencies intentionally rather than assuming a
   transitive graph is fresh.
3. Re-establish only explicit development fixtures through a namespace-level
   `reset!`/`start!` function once the project has one; it must close and replace
   resources it owns. Until such a function exists, restart the nREPL process to
   obtain a known-clean runtime rather than accumulating manual repairs.
4. Run the desired focused expression. If state, loaded dependencies, or resource
   ownership is uncertain, stop the server and start a new one; reconnect both
   clients.

This deliberately distinguishes source reload from runtime reset. Reloading a
namespace can redefine code while leaving values/resources created by earlier
evaluations reachable; a reset function or process restart establishes the
lifecycle boundary. The presentation's warning that REPL plus imperative code
makes “faster spaghetti” supports keeping that boundary explicit ([slide
17][scissors]).

## Security and lifecycle

**Fact — binding has security consequences.** Babashka binds its nREPL server to
`127.0.0.1` by default; `0.0.0.0` accepts other hosts. Its server API also
accepts `:host`, `:port`, or a Unix `:socket` ([Babashka book, nREPL][bb-nrepl]).
The official nREPL server guide warns that a public address is an “epic security
hole”: connections have no authentication or authorization by default, and a
client can run code or modify application behavior. It recommends care with
public host/port binding and notes that local Unix sockets are constrained by
access to their parent directory ([nREPL Server][nrepl-server]).

**Recommendation — local TCP only for the first setup.** Bind explicitly to
`127.0.0.1`, never `0.0.0.0`, a LAN address, port forwarding, or a checked-in
remote-server configuration. Do not place secrets in evaluated forms, REPL
history, or rich comments. A Unix-domain socket may be evaluated later if client
support and local permissions warrant it; it is not needed for the initial
low-friction setup.

**Recommendation — ownership and shutdown.** The user starts and stops the
server in a terminal they own; the coding assistant only connects after the user
has made the endpoint available and only performs requested evaluation. Stop it
at the end of the development session, before changing branches/worktrees, and
whenever reset certainty is lost. The resulting `.nrepl-port` is runtime
coordination data, not source configuration; keep it untracked.

## Reproducibility

**Fact.** Babashka resolves its configuration from `bb.edn` next to the invoked
file or in the current directory; `--config` can replace it and `--classpath`
overrides its classpath ([Babashka book: usage][bb-usage]).

**Recommendation.** Once implementation starts, commit the project `bb.edn`
that declares the classpath and a named nREPL task. Pin any dependencies in that
configuration, record the required Babashka version in the project onboarding
material, and start from the repository root. Do not depend on a developer's
personal `~/.bb.edn`, editor-only startup code, or a random port. A fixed local
port gives both approved clients one discoverable endpoint; `.nrepl-port` still
lets standard clients discover it.

## Minimal first setup (proposal only)

The smallest useful first setup needs no application code and does **not** embed
an nREPL server in Botashka.

1. Install a team-agreed Babashka version and invoke it from the repository root.
2. Start the sole server in a developer-owned terminal:

   ```sh
   bb nrepl-server 127.0.0.1:1667
   ```

   This uses the documented command and explicit loopback binding. Babashka's
   documented default port is `1667`; the explicit address makes the security
   boundary visible ([Babashka book, nREPL][bb-nrepl]).
3. Connect a terminal client with `bb repl --connect`, which discovers
   `.nrepl-port`, or configure the user’s editor and the assistant's nREPL client
   for `127.0.0.1:1667` ([Babashka book, nREPL][bb-nrepl]). Each client creates
   and retains its own persistent session.
4. At session end, terminate the server from its owner terminal. Confirm that
   `.nrepl-port` disappears as Babashka documents, then start fresh next time.

When the repository needs repeatable startup, replace step 2 with a committed
`bb.edn` task conceptually equivalent to this (shown for review, **not to be
added yet**):

```clojure
{:tasks
 {nrepl
  {:requires ([babashka.nrepl.server :as nrepl])
   :task (do
           (nrepl/start-server! {:host "127.0.0.1" :port 1667})
           (deref (promise)))}}}
```

Use this task only after deciding where `.nrepl-port` lifecycle management lives;
the built-in `bb nrepl-server` command already manages that file and is the
better first command. The documented `start-server!` options support the shown
`:host` and `:port` settings ([Babashka book, nREPL][bb-nrepl]).

## Sources

- [Running with Scissors: Live Coding with Data — supplied PDF][scissors]
  (Stuart Halloway; cited by slide number above).
- [Babashka book — nREPL][bb-nrepl] and [usage/configuration][bb-usage]
  (official Babashka documentation).
- [nREPL: Building Clients][nrepl-sessions], [nREPL Protocol
  Specification][nrepl-spec], [nREPL Server][nrepl-server], and [nREPL: Misc
  Functionality][nrepl-output] (official nREPL documentation).

[scissors]: file:///Users/I067908/Downloads/RunningWithScissors.pdf
[bb-nrepl]: https://book.babashka.org/#_nrepl
[bb-reload]: https://book.babashka.org/#_libraries
[bb-usage]: https://book.babashka.org/#usage
[nrepl-sessions]: https://nrepl.org/nrepl/building_clients.html#sessions
[nrepl-spec]: https://spec.nrepl.org/
[nrepl-server]: https://nrepl.org/nrepl/usage/server.html
[nrepl-output]: https://nrepl.org/nrepl/usage/misc.html
