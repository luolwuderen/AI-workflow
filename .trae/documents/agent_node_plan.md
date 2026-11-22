# Agent 节点实现计划

## Repository Research

- 节点架构：后端 `NodeCatalog` 注册节点元数据（schema / defaults / outputs / executable），`WorkflowExecutor.executeNode` 对未内置分支的类型按 `NodeExecutor.type()` 查找执行器（`workflow-infra` 中的 `@Component` 实现），执行结果写入 `outputs` 并调度下游。
- 当前 `agent` 在 [NodeCatalog.java](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-core/src/main/java/com/acme/workflow/core/catalog/NodeCatalog.java#L41-L42) 中标记为 `unsupported`，仅含 `strategy` / `instruction` 两个占位字段。
- LLM 样板：[SpringAiLlmExecutor.java](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-infra/src/main/java/com/acme/workflow/infra/execution/SpringAiLlmExecutor.java) 用 `ChatModel` + `OpenAiChatOptions` 调用，消息从 config 渲染，结果返回 `{text}` 并发射 `LLM_DELTA`。
- 工具调用能力（Spring AI 2.1.0，依赖已在 `spring-ai-starter-model-openai`，无需新增依赖）：
  - `OpenAiChatOptions.Builder.toolCallbacks(List<ToolCallback>)` 把工具定义发送给模型。
  - `AssistantMessage.getToolCalls()` 返回 `List<AssistantMessage.ToolCall>`（`id/name/arguments`）。
  - 已确认 `ChatModel.call(Prompt)` **不自动执行工具循环**（无 `internalToolExecutionEnabled`），原样返回单次响应 → 适合手动循环。
  - `FunctionToolCallback.builder(name, fn).description(...).inputSchema(json).toolCallResultConverter(...).build()` 用于把内置工具适配为 `ToolCallback`；`call(String)` 执行并返回结果字符串。
  - 工具结果回填用 `ToolResponseMessage`（`ToolResponse(id, name, responseData)`）。
- 前端是 schema 驱动：[variables.ts](file:///Users/libaocheng/Desktop/CODE/agent/apps/web/src/modules/workflow/nodes/variables.ts) 对非动态表单节点自动从 `definition.outputs` 暴露下游变量；`NodeInspector` 用 `DynamicField` 渲染 configSchema。因此 `agent.text` / `agent.iterations` 会自动出现在变量选择器，节点目录与拖拽也自动出现，**前端无需改代码**（验收时确认即可）。
- 测试样板：后端 `OpenAiLlmIntegrationTest` 用 `com.sun.net.httpserver.HttpServer` 起随机端口 mock OpenAI，`@DynamicPropertySource` 指向它；`DataNodeExecutorsTest` 直接 `new Executor()` 单测；前端 `tests/e2e/workflow.spec.ts` 用 `createApp` + 图对象 API 建图后浏览器执行。
- 兼容策略（项目既定）：仅对 `matrix.json` 显式标记能力声明兼容，未知 DSL 字段一律拒绝，禁止静默丢弃。完整 Dify 插件工具属于第三期。

## 范围与设计决策（已与用户确认）

- 首版内置工具：仅 `calculator`（确定性算术，无外部依赖），预留 `AgentTool` SPI 供后续 code / http / 插件工具插拔。
- 策略：仅支持 `function_call`；`ReAct` 及其它策略在 DSL 导入与校验时精确拒绝。
- Dify 兼容：无损子集（`strategy=function_call`、`instruction`、`model`、内置工具名）；外部插件工具、context / 会话变量等显式拒绝。

### Agent 节点配置（config）
| 字段 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `model` | string | 是 | — | 模型名 |
| `instruction` | string | 是 | — | 系统提示词，支持 `{{节点.变量}}` 渲染 |
| `prompt` | string | 是 | — | 用户消息模板，支持变量渲染 |
| `strategy` | string | 否 | `function_call` | 仅允许 `function_call` |
| `tools` | string[] | 否 | `["calculator"]` | 启用的内置工具名 |
| `maxIterations` | int | 否 | 5 | 1–20 |
| `temperature` | number | 否 | 0.7 | 0–2 |
| `topP` | number | 否 | 1.0 | 0–1 |
| `maxTokens` | int | 否 | 2048 | ≥1 |

输出 `outputs`：`text`(string)、`iterations`(number)、`usage`(object)。

### 运行时语义
1. 渲染 `instruction` → `SystemMessage`，渲染 `prompt` → `UserMessage`。
2. 按 `tools` 从注入的 `List<AgentTool>` 选出启用工具，构建 `FunctionToolCallback` 列表；配置了未知工具名 → 抛 `NodeExecutionException`。
3. 循环（≤ `maxIterations`）：用 `toolCallbacks` 调 `chatModel.call` → 取 `AssistantMessage` 加入消息 → 若无 `tool_calls` 则 `text` 为终答并结束；否则对每个 call 执行对应 `tool.call(arguments)`，结果包成 `ToolResponseMessage` 回填，发射 `AGENT_TOOL_CALL` 事件，继续。
4. 达上限仍无终答：取最后一轮文本，返回并记录 `iterations`。
5. 首版**不流式**（循环内同步），不暴露 `stream` 字段。
6. 异常统一包装为 `NodeExecutionException`。

### calculator 工具
- 输入 JSON Schema：`{"type":"object","properties":{"expression":{"type":"string"}},"required":["expression"]}`。
- 用自实现的安全递归下降解析器（仅 `+ - * / %`、括号、一元负号、小数），拒绝除零与非法表达式，返回 JSON 字符串 `{"result":<number>,"expression":"..."}`。不使用脚本引擎，无注入风险。

## Files and Modules

后端：
- `server/workflow-core/.../catalog/NodeCatalog.java`：把 `agent` 从 `unsupported` 改为 `executable`，新增 `agentSchema()` 与默认值、输出声明。
- `server/workflow-core/.../validation/GraphValidator.java`：`validateConfig` 增加 `case "agent"`：校验 `model`/`instruction`/`prompt` 非空、`strategy` 仅 `function_call`、`maxIterations` 在 1–20。
- `server/workflow-runtime/.../agent/AgentTool.java`（新建）：SPI 接口 `name()/description()/inputSchema()/call(String)->String`。
- `server/workflow-infra/.../execution/agent/CalculatorAgentTool.java`（新建，`@Component`）：实现 `calculator`。
- `server/workflow-infra/.../execution/AgentExecutor.java`（新建，`@Component`，`implements NodeExecutor`，`type()="agent"`）：注入 `ChatModel` + `List<AgentTool>`，实现循环。
- `server/workflow-infra/.../dify/DifyDslAdapter.java`：`importNode`/`exportNode` 类型开关加入 `agent`，新增 `importAgent`/`exportAgent`，按无损子集精确拒绝不支持字段。
- `compatibility/matrix.json`：把 `agent` 行更新为 `executable:true, dslRoundTrip:true`。

前端：
- 无需改代码（schema 驱动自动生效）；验收时确认变量选择器出现 `agent.text`/`agent.iterations`、节点目录出现可执行 Agent。

测试：
- `server/workflow-infra/src/test/.../execution/AgentExecutorTest.java`（新建）：用 fake `ChatModel` 脚本化返回（tool_call → 终答），覆盖：工具调用循环并回填、`agent.text` 含计算结果、未知工具报错、达 `maxIterations` 终止、无工具时退化为普通回答。
- `server/platform-app/src/test/.../AgentIntegrationTest.java`（新建，或扩展现有集成测试模式）：mock OpenAI HTTP server 返回 tool_call 再终答，通过 MockMvc 跑完整 run，断言 `agent.text` 与事件流含 `AGENT_TOOL_CALL`。
- `server/workflow-infra/src/test/.../DifyDslAdapterTest.java`：新增 agent DSL 往返（function_call + calculator）与拒绝用例（ReAct 策略、外部插件工具、未知字段）。
- `tests/e2e/workflow.spec.ts`：新增 `agentGraph()`（user-input → agent → output）与测试，断言运行成功、输出含 calculator 结果、节点状态 succeeded、事件含 `AGENT_TOOL_CALL`。

## Implementation Steps

1. 新建 `AgentTool` SPI 接口（runtime 模块）。
2. 实现 `CalculatorAgentTool`（infra），含安全表达式解析器；先写 `CalculatorAgentToolTest` 覆盖表达式与错误。
3. 实现 `AgentExecutor`（infra）：消息构建、options 构建、手动工具循环、事件发射、输出组装。
4. 升级 `NodeCatalog` 的 `agent` 为 executable，补 schema/defaults/outputs。
5. `GraphValidator` 增加 agent 配置校验。
6. `DifyDslAdapter` 增加 agent 导入导出与精确拒绝。
7. 更新 `matrix.json` agent 行。
8. 后端单测 `AgentExecutorTest` + `CalculatorAgentToolTest`。
9. 后端集成测试 `AgentIntegrationTest`（mock OpenAI）。
10. DSL 测试补 agent 往返与拒绝。
11. 运行 `mvn -q -pl server/platform-app -am verify`，确认全绿。
12. 前端 `pnpm --filter @weave/web build && test && typecheck`，确认无回归（前端无代码改动，确认 schema 自动生效）。
13. 启动后端（dev profile，不禁用 chat），起一个 mock OpenAI server（8081，脚本化 tool_call 往返），运行 agent 定向与全量 Playwright，修复观察到的失败。
14. 全量 Playwright 通过后收尾报告。

## Dependencies and Considerations

- 不新增 Maven 依赖；工具调用能力由 `spring-ai-starter-model-openai` 2.1.0 提供。
- `AgentExecutor` 复用 `SpringAiLlmExecutor` 的消息/options 构建模式，避免两套不一致。
- `FunctionToolCallback` 的参数反序列化与结果序列化：用 `inputType(Map)` + 自定义 `ToolCallResultConverter` 原样透传 `tool.call` 的 JSON 字符串，避免双重包裹；实现第一步先用单测锁定该行为。
- `ChatModel.call` 不自动执行工具循环已通过类签名确认；若实现中发现实际自动循环，改为传 `toolChoice` 并在调用后只读取 `tool_calls`，或显式用 `ChatClient` 关闭内部执行——以单测结果为准，必要时调整。
- 前端零改动依赖 schema 驱动：若 `tools` 数组的 enum 在 `DynamicField` 中渲染不佳，不阻塞功能（E2E 走 API 建图），UI 打磨留待后续。
- E2E 需要一个返回 tool_call 的模型端点：提供 `tests/e2e/mock-openai.mjs`，验收时在 8081 启动（若 8081 已被占用则不启动，避免冲突）。

## Validation

- `mvn -q -pl server/platform-app -am verify`（含 AgentExecutor / Calculator / DSL / 集成测试）全绿。
- `pnpm --filter @weave/web build && test && typecheck` 无回归。
- mock 模型就位后：`pnpm exec playwright test --grep "Agent"` 定向通过，再 `pnpm test:e2e` 全量通过。
- 验收点：agent 调用 calculator 后 `agent.text` 含正确结果；下游 output 能读取 `agent.text`；事件流出现 `AGENT_TOOL_CALL`；非法 strategy / 未知工具在精确路径报错而非静默丢弃。

## Risks

- `FunctionToolCallback` 序列化细节与预期不符：先用最小单测锁定，必要时改为直接实现 `ToolCallback` 或调整 converter。
- `ChatModel.call` 实际自动执行工具：单测会立刻暴露，按 Considerations 调整。
- E2E 模型端点不可用：通过自带 mock server 规避；若 8081 被真实服务占用则 agent E2E 跳过并在报告中说明环境依赖。
- calculator 解析器边界（如大数、精度）：首版用 `double`，满足典型用例；复杂精度留待后续。
- Dify agent DSL 真实样例字段可能与预期不同：导入导出先以官方 `entities.py` / DSL 样例为准，不支持字段一律在精确路径拒绝，不静默丢弃。
