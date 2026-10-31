# Botashka 工作指南

## 目的

Botashka 是一个从零构建 coding agent 的学习型项目。目标不只是实现功能，更要让每个设计选择、数据契约、effect seam 和验证方式都可理解、可检查、可演进。

优先级：

```text
可读性与架构简单性
→ 正确性与可验证性
→ 数据驱动、不可变、函数式设计
→ 功能数量、抽象通用性、性能优化
```

## 开始前

1. 先读 [`CONTEXT.md`](CONTEXT.md)。其中的领域术语是代码、测试和文档的共同语言。
2. 需要了解产品阶段、非目标或后续顺序时，读 [`plan/botashka-from-code-tools-to-coding-agent.zh.md`](plan/botashka-from-code-tools-to-coding-agent.zh.md)。
3. 修改某个领域前，先读 `CONTEXT.md` 中对应的 domain 定义，并读该领域的最接近 design/plan 文档。
4. 修改现有代码前，读完整目标文件及其直接依赖；修改公开符号前，查全部 references。

## 设计原则

### Functional core, imperative shell

采用 [Functional Core, Imperative Shell](https://functional-architecture.org/functional_core_imperative_shell/)：

- **Functional core**：输入 data，返回 data；无 I/O、无隐藏可变状态、无时间/随机数/环境读取。解析、验证、归一化、选择、路由、渲染和状态转换优先放在这里。
- **Imperative shell**：只在命名清晰的 seam 读取输入、调用 provider、访问 filesystem、启动 subprocess、持久化、输出终端或发布 observation。
- core 不调用 shell。shell 获取外部输入，调用 core，然后执行 core 决定的 effect。
- 当 effect 使 domain logic 难测时，把 effect 的输入/输出表达为 immutable data，并把执行留在 shell。

不把纯 helper 为了“少一个函数”塞进 effectful orchestration。一个有清楚名字的小函数，只要能解释意图、隔离规则或缩短调用点认知负担，就值得存在。

### Data first

- 跨 module、process、provider 和 tool 的信息使用 EDN maps/vectors；优先设计 data contract，再写控制流。
- 数据不得被原地修改；每次状态变化返回新值。run-scoped atom 只由明确 owner 协调，不作为隐式全局 application state。
- 不使用 namespace Vars、REPL history 或 dynamic binding 保存 application state。
- effect boundary 的输入、输出、错误和限制必须是可检查 data，而不是散落的字符串或异常副作用。

### Deep modules, small interfaces

- module 要隐藏实现复杂度，调用者只需要理解小而稳定的 interface。
- 不为假想的第二个实现创建 abstraction；真实变化轴出现后再建立 seam。
- 不保留旧 alias、兼容 wrapper 或 parallel path。完成 cutover 后迁移全部 callers，删除 obsolete code。
- 公共 names 直接描述领域意图。避免 `util`、`helper`、`process`、`handle` 等无法说明行为的名称。

## 代码形状

- 一函数一职责；先写 public function，再按调用顺序放置其 private helpers。
- 当一个 expression 同时包含业务决定和 effect，拆出命名 pure function 表达业务决定。
- 函数名应回答“这段代码决定/转换什么”，而非“它怎样实现”。例如 `provider-response-data` 优于 `make-map`。
- 参数传入依赖；不要在 core 内部读取 environment、当前时间、filesystem 或创建 client。需要时间/ID 时注入 clock/ID generator。
- 只对真正的 effect 使用 `!` 后缀；pure transformation 不使用 `!`。
- macro 只移除明确、重复的横切样板，例如 effect lifecycle。macro 不得隐式捕获 locals、arguments、result 或 secrets，也不得创建隐藏 state。


## 验证

- 改动前先建立覆盖真实 seam 的快速反馈循环；bug fix 先复现，再修复，再运行原场景。
- 先写失败的确定性测试，再实现最小修复；测试经 public interface 观察行为，不能 pin private implementation 或 forwarding。
- 纯 core 用 fixture data 测试；provider/tool/process 等 shell 用 fake adapter 或真实最小 smoke 分别验证。
- 每个显著行为改动都必须运行：相关 deterministic checks，及一个实际 changed-path smoke。记录实际执行过的命令和结果，不编造验证。
- 当前测试 classpath 已在 `deps.edn` 声明。单 namespace 测试可用：

  ```sh
  clojure -M -e "(require 'namespace-test) (clojure.test/run-tests 'namespace-test)"
  ```

  按实际 namespace 替换 `namespace-test`。

## 变更纪律

- 编辑前先读完整相关文件；修改 exported symbol 前先查全部 references。
- 一次变更只解决一个明确问题。不要顺便加入 retry、telemetry、框架、并发、配置层或“未来扩展点”。
- 更新所有受影响的 callers、tests、文档与 REPL probe；删除已过时的代码和注释。
- 只 commit 本次工作实际修改的文件；显式暂存路径。不要提交运行生成的 run records、payload artifacts、caches 或 credentials。
- 设计抉择满足“难以逆转、缺少上下文会令人意外、存在真实权衡”时，在 `docs/adr/` 记录 ADR；其他决定放在最接近的 design/plan 文档。
