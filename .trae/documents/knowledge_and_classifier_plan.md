# 知识检索与问题分类器实现计划

## Context

平台目前 16 个节点可执行，`knowledge-retrieval` 与 `question-classifier` 仍为 `unsupported`（见 [NodeCatalog.java](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-core/src/main/java/com/acme/workflow/core/catalog/NodeCatalog.java#L33-L50)）。本计划落地这两个 AI 节点：

- **question-classifier**：调用现有 `ChatModel` 将输入归入若干类别，按类别走不同出边，并与 Dify DSL 双向往返。
- **knowledge-retrieval**：平台级知识库 + OpenAI embedding + H2 存向量 + Java 内余弦检索（已确认选择）。按 Dify 无数据集对应关系，**不做 DSL 往返**，导入/导出仍在精确路径拒绝。

已确认的决策：
1. 检索采用向量方案（embedding），不使用关键词。
2. DSL 策略：分类器往返；检索拒绝（`dslRoundTrip=false`，导出沿用现有"不支持导出"错误）。

现有可复用能力：
- 分支输出与边激活：[WorkflowExecutor.java#L260-L267](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-runtime/src/main/java/com/acme/workflow/runtime/WorkflowExecutor.java#L260-L267) 已按 `output.branch` 与 `sourceHandle` 匹配，只需把类型判断从仅 `if-else` 扩展为含 `question-classifier`。
- 分支边校验：[GraphValidator.java#L273-L283](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-core/src/main/java/com/acme/workflow/core/validation/GraphValidator.java#L273-L283)。
- 前端分支端口：[branches.ts](file:///Users/libaocheng/Desktop/CODE/agent/apps/web/src/modules/workflow/nodes/branches.ts#L24-L34) 已支持 `QUESTION_CLASSIFIER` 按 `config.classes` 生成 handle，无需改。
- LLM 调用与选项构造参考：[SpringAiLlmExecutor.java](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-infra/src/main/java/com/acme/workflow/infra/execution/SpringAiLlmExecutor.java)。
- 实体/Repository/迁移约定：`workflow-infra/.../persistence` + `platform-app/src/main/resources/db/migration`；无 Lombok、手写 getter、`TEXT` 列。
- Embedding 自动配置：`spring-ai-starter-model-openai` 默认创建 `EmbeddingModel` Bean（`spring.ai.model.embedding` 默认 openai）；`EmbeddingModel.embed(String)` 与 `embed(List<String>)` 可用。

---

## Phase 1：question-classifier

### 后端

1. **目录与默认值**（[NodeCatalog.java](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-core/src/main/java/com/acme/workflow/core/catalog/NodeCatalog.java)）
   - 改为 `executable`。
   - schema：`model`(string, minLength 1)、`query`(string, minLength 1, 标题"输入（支持 {{节点.变量}}）")、`classes`(array, items `{id,name}`, minItems 1)、`instruction`(string, 可选)、`temperature`(0-2, 0.2)、`topP`(0-1, 1.0)、`maxTokens`(≥1, 1024)。
   - defaults：`model=""`、`query="{{start.text}}"`、`classes=[{id:"1",name:"类别 1"}]`、`instruction=""`。
   - outputs：`branch`(string)、`class_name`(string)、`usage`(object)。

2. **执行器** `server/workflow-infra/.../execution/QuestionClassifierExecutor.java`
   - 注入 `ChatModel`；渲染 `query`；构造一条 `UserMessage`，内容包含 instruction、类别清单（id + 名称）与"只输出类别 id"的约束。
   - `ChatModel.call(new Prompt(messages, OpenAiChatOptions...))`；解析策略依次：去空白/引号/代码围栏 → 忽略大小写匹配 id → 匹配 name → 失败抛 `NodeExecutionException`。
   - 输出 `{branch: <class id>, class_name, usage}`；`branch` 缺失或不在 `classes` 中时直接失败，不静默兜底。

3. **分支激活**（[WorkflowExecutor.java](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-runtime/src/main/java/com/acme/workflow/runtime/WorkflowExecutor.java#L260-L267)）
   - `isActive` 中分支节点判断改为 `if-else` 或 `question-classifier`；其余逻辑不变。

4. **校验**（[GraphValidator.java](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-core/src/main/java/com/acme/workflow/core/validation/GraphValidator.java)）
   - `validateConfig` 增加 `case "question-classifier"`：`model`/`query` 必填、`classes` 非空且 `id` 唯一非空、`name` 非空。
   - `validateBranch` 按源节点类型区分：分类器 handle 必须等于某个 `classes[].id`（不允许 `else`），错误码沿用 `BRANCH_HANDLE`。

5. **Dify DSL**（[DifyDslAdapter.java](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-infra/src/main/java/com/acme/workflow/infra/dify/DifyDslAdapter.java)）
   - 加入 import/export 类型分发。
   - `importQuestionClassifier`：`allowed` 集合 `desc/title/type/selected/query_variable_selector/model/classes/instruction/memory/vision`；`query_variable_selector` 必须为两段数组并转为 `{{node.var}}`；`classes` 每项仅允许 `{id,name}`；`memory` 仅允许全空，否则在该路径拒绝；`vision.enabled=true` 拒绝；model 复用 LLM 的 provider/mode/`completion_params` 映射。
   - `exportQuestionClassifier`：`query` 必须是单个 `{{a.b}}` 才能还原为 `query_variable_selector`，否则在 `$.nodes[id=...].config.query` 拒绝；classes/model 反向输出。

6. **矩阵**：`compatibility/matrix.json` 中该行 `executable=true`、`dslRoundTrip=true`（[CompatibilityMatrixTest.java](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-infra/src/test/java/com/acme/workflow/infra/CompatibilityMatrixTest.java) 会强校验）。

### 测试
- `QuestionClassifierExecutorTest`：fake `ChatModel`（参考 `AgentExecutorTest` 的 `ScriptedChatModel`）覆盖：id 命中、名称命中、无法解析时报错、branch 不在 classes 时报错。
- `DifyDslAdapterTest`：新增分类器 fixture 往返；拒绝 `memory` 非空、未知 class 字段、非单一 selector 的导出。
- `WorkflowExecutorTest`：分类器按 `classes[].id` 走对应分支（可选，若时间紧由 E2E 覆盖）。
- `GraphValidatorTest`：把原先把 question-classifier 当作 unsupported 的用例改为 `tool` 节点。

---

## Phase 2：knowledge-retrieval（向量检索）

### 数据模型与迁移
新增 `server/platform-app/src/main/resources/db/migration/V2__knowledge_base.sql`：

- `workflow_knowledge_base(id PK, name NOT NULL, description TEXT, dimensions INT NOT NULL, created_at, updated_at)`
- `workflow_knowledge_document(id PK, knowledge_base_id FK, name NOT NULL, content TEXT NOT NULL, chunk_count INT NOT NULL, created_at)`
- `workflow_knowledge_chunk(id PK, knowledge_base_id FK, document_id FK, chunk_index INT, content TEXT NOT NULL, embedding_json TEXT NOT NULL, dimensions INT NOT NULL, created_at, UNIQUE(document_id, chunk_index))`
- 索引：document(knowledge_base_id, created_at)、chunk(knowledge_base_id)。

不做 `status/error` 字段：入库同步完成 embedding，失败整体回滚。

### 后端组件（workflow-infra）
- `persistence/KnowledgeBaseEntity`、`KnowledgeDocumentEntity`、`KnowledgeChunkEntity` + 三个 Repository（派生查询，风格同 `AppRepository`）。
- `knowledge/TextChunker`：按空行分段，累计至约 800 字符，超长段硬切；块间保留少量重叠。
- `knowledge/VectorCodec`：`float[]` ↔ JSON（`ObjectMapper`）。
- `knowledge/KnowledgeIngestionService`：切分 → 每批 64 条 `embeddingModel.embed(List<String>)` → 落库；embedding 失败抛业务异常，不写库。
- `knowledge/KnowledgeQueryService.search(kbIds, query, topK, threshold)`：加载候选 chunk（上限 5000，超出报错）→ 对 query 单条 embed → 余弦排序 → 阈值过滤 → 返回命中列表。
- `execution/KnowledgeRetrievalExecutor`：渲染 `query` 模板，调用 QueryService，输出：
  ```json
  {"result":[{"content":"...","score":0.83,"documentId":"...","documentName":"...","chunkIndex":0}],
   "context":"按得分拼接的文本","count":3}
  ```
  未命中返回空数组与空字符串，不报错。维度不一致时报明确错误。

### 目录、校验、矩阵
- [NodeCatalog.java](file:///Users/libaocheng/Desktop/CODE/agent/server/workflow-core/src/main/java/com/acme/workflow/core/catalog/NodeCatalog.java#L33-L35) 改为 `executable`；schema：`datasetIds`(array of string, minItems 1)、`query`(string, minLength 1)、`topK`(1-20, 默认 4)、`scoreThreshold`(0-1, 默认 0.0)；defaults `datasetIds=[]`、`query="{{start.text}}"`；outputs `result`/`context`/`count`。
- `GraphValidator`：`datasetIds` 非空、`query` 非空、`topK`/`scoreThreshold` 范围。
- `matrix.json`：`executable=true`，`dslRoundTrip` 保持 `false`（导出仍按"不支持导出"拒绝，导入仍精确拒绝）。

### 平台 API（platform-app）
新增 `api/KnowledgeBaseController`（`@RequestMapping("/api/v1/knowledge-bases")`，风格同 `WorkflowController`，不引入统一包装）：
- `GET /`、`POST /`、`DELETE /{id}`
- `GET /{id}/documents`、`POST /{id}/documents`（`{name, content}` 文本入库）、`DELETE /{id}/documents/{documentId}`
- 新增 `service/KnowledgeBaseService` 编排 CRUD 与入库；删除按 chunk → document → base 顺序显式删除。
- [RuntimeConfiguration.java](file:///Users/libaocheng/Desktop/CODE/agent/server/platform-app/src/main/java/com/acme/platform/service/RuntimeConfiguration.java#L22) CORS 允许方法补 `DELETE`。
- `application.yml` 增加 `spring.ai.openai.embedding.model`（默认 `text-embedding-3-small`）与 `dimensions`（默认 1536）。

### 前端
- [types.ts](file:///Users/libaocheng/Desktop/CODE/agent/apps/web/src/modules/workflow/api/types.ts)：新增 `KnowledgeBase`、`KnowledgeDocument`。
- [client.ts](file:///Users/libaocheng/Desktop/CODE/agent/apps/web/src/modules/workflow/api/client.ts)：新增 `knowledgeApi`，复用现有 `request()`。
- 新建 `modules/knowledge/KnowledgeBasePanel.vue`：右侧抽屉，知识库列表/创建/删除、文档列表/文本入库/删除；每项提供"加入当前节点"把 id 写入 `config.datasetIds`。
- [WorkflowEditor.vue](file:///Users/libaocheng/Desktop/CODE/agent/apps/web/src/modules/workflow/WorkflowEditor.vue)：工具栏加"知识库"入口。
- `datasetIds` 字段先用现有数组控件；抽屉负责选择，避免在 `DynamicField` 引入 API 依赖（保持其纯展示）。
- 分类器表单零改动：`classes` 走现有 array-objects，分支端口由 `branches.ts` 自动生成。

---

## 验证

1. `mvn -q -pl server/platform-app -am verify`（checkstyle + 全部单测/集成测试）。
2. 新增 `KnowledgeIntegrationTest`（`@SpringBootTest` + MockMvc + 本地 `HttpServer`）：
   - mock `/v1/embeddings`（按 `input` 数量返回同长度向量）；
   - 建知识库 → 入库两段文本 → 运行 graph（start → knowledge-retrieval → output），断言 `context` 只含相关段落、`topK` 生效、`/v1/embeddings` 被调用。
3. `pnpm --filter @weave/web test && pnpm --filter @weave/web build`。
4. E2E：
   - 扩展 [mock-openai.mjs](file:///Users/libaocheng/Desktop/CODE/agent/tests/e2e/mock-openai.mjs)：新增 `/v1/embeddings`；分类器请求返回 `"1"`（按请求中"只输出类别 id"的约定识别）。
   - 新增 `E2E Classifier <ts>`、`E2E Retrieval <ts>` 两条用例，并同步 [global-teardown.ts](file:///Users/libaocheng/Desktop/CODE/agent/tests/e2e/global-teardown.ts#L4) 的清理正则。
   - 先 `--grep "E2E (Classifier|Retrieval)"` 定向，再全量 `pnpm test:e2e`。
5. `compatibility/matrix.json` 与 `CompatibilityMatrixTest` 保持一致。

## 风险与边界

- **规模**：H2 全量加载候选向量，仅适合演示/小规模；设置候选上限并在文档中标注后续可迁移 pgvector。
- **Embedding 依赖**：开发默认指向本地 mock（8081），手动使用检索前需启动 `tests/e2e/mock-openai.mjs`；否则入库会返回明确的 502 类错误。
- **分类器确定性**：依赖模型输出 id/name；解析失败直接失败而非猜测，符合"不静默降级"原则。
- **DSL 边界**：检索节点不进入 DSL；分类器往返仅覆盖 `query_variable_selector/model/classes/instruction`，`memory` 非空与 `vision` 启用一律在精确路径拒绝。
