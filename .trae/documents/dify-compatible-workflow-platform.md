# Dify 兼容工作流平台实施计划

## Context

当前工作区为空，需要从零建设支持 Workflow 与 Chatflow 的完整工作流平台。目标是：Vue 页面拖拽编排，节点能力、配置和执行语义对齐 Dify，并最终兼容 Dify YAML DSL 与插件生态；后端采用 Java / Spring Boot / Spring AI，支持严格内网离线部署。

完整平台分期交付，每期验收后再推进。第一期完成“编辑器 + 核心执行”闭环，不把静态节点目录宣称为全部节点已兼容。

## 技术决策

- **前端**：Vue 3、TypeScript、Vite、Pinia、Vue Flow、CodeMirror、Vitest、Playwright。
- **后端**：Java 21、Spring Boot、Spring AI 2.x，根 Maven 聚合，先采用模块化单体，避免过早微服务化。
- **数据与基础设施**：PostgreSQL 保存草稿和运行状态；SSE 推送执行事件；官方 `dify-sandbox` 执行 Python / JavaScript；后续接入官方 `dify-plugin-daemon`。
- **唯一事实源**：自有版本化 `GraphDocument`，画布 UI 数据与执行配置分离。Dify YAML、Vue Flow 数据均通过适配器转换，不让第三方格式渗入执行内核。
- **节点 Schema 驱动**：后端发布节点 catalog、JSON Schema 和 UI Schema；前端目录、动态配置表单和校验共同消费，避免前后端节点定义漂移。
- **兼容基线**：冻结一个 Dify 版本、对应 DSL 样例、插件守护进程版本和插件 SDK 版本，记录兼容矩阵；不以“文档名称相同”作为兼容结论。

## 目标节点范围

- 开始：User Input；Schedule / Plugin / Webhook Trigger（仅 Workflow）。
- AI：LLM、Knowledge Retrieval、Agent、Question Classifier、Parameter Extractor。
- 控制：If / ELIF / Else、Human Input、Iteration、Loop、Exit Loop、Variable Aggregator。
- 数据：Code、Template、Document Extractor、Variable Assigner、HTTP Request、List Operator。
- 输出与扩展：Output（Workflow）、Answer（Chatflow）、Tool；以及 Iteration / Loop 内部子流程节点。
- 通用语义：串行与并行、上游变量引用、环境变量、会话变量、流式输出、重试与错误分支、草稿 / 发布 / 历史恢复。

## 工程结构

```text
apps/web/src/
  modules/workflow/{canvas,nodes,inspector,store,api}
  modules/chat/
  shared/
server/
  platform-app/       # 启动、REST/SSE、数据库迁移
  workflow-core/      # GraphDocument、节点 Schema、校验、执行 SPI
  workflow-runtime/   # 调度、变量作用域、状态、检查点、事件
  workflow-infra/     # PostgreSQL、Spring AI、Sandbox、Dify 适配
deploy/compose/
tests/{dsl-golden,e2e}/
compatibility/        # Dify 版本、节点能力矩阵、兼容记录
```

## 第一期：编辑器 + 核心真实执行

### 1. 工程与兼容基线

- 创建前端、Maven 多模块后端和本地 Compose 环境，提供统一启动命令。
- 在 `compatibility/` 固定 Dify 目标版本，建立节点能力矩阵：目录可见、可配置、可校验、可执行、DSL 往返分别记录。
- 保存核心节点的官方 YAML golden samples；不支持的内容必须显式报错，禁止静默丢弃。

### 2. 图模型与节点目录

- 在 `workflow-core` 定义 `GraphDocument`、节点、边、分支句柄、变量类型、选择器、节点 SPI 和校验结果。
- 在 `platform-app/src/main/resources/node-catalog/v1/` 定义全部目标节点的静态配置 Schema、输出 Schema、应用类型限制与执行支持状态。
- 校验开始节点互斥、Workflow / Chatflow 输出节点差异、非法连接、必填配置、变量引用、重复 Output 名称和不可运行节点。

### 3. Vue 工作流编辑器

关键文件：`apps/web/src/modules/workflow/WorkflowEditor.vue`，以及 `canvas/`、`nodes/`、`inspector/`、`store/` 下组件。

- 实现高密度专业工作台：节点目录 / 搜索、画布、右侧配置检查器、底部运行日志；使用清晰的浅色画布、深色导航和单一强调色，不复制 Dify 品牌。
- 支持拖入、移动、连线、框选、复制粘贴、删除、缩放、小地图、适应视图、自动布局、撤销重做和快捷键。
- 使用统一动态表单渲染全部节点静态配置，支持条件显隐、分支配置、键值列表、代码 / 提示词编辑和变量选择器。
- 自动保存草稿并带乐观锁；刷新恢复；校验错误可定位到节点和配置项。
- 非本期可执行节点可编辑保存，但运行时明确列出未支持原因。

### 4. 核心 API 与持久化

- 提供应用、草稿、节点 catalog、运行、事件和 SSE API。
- 建立应用草稿、运行、节点运行、运行事件表；运行保存固定图快照。
- `workflow-infra` 实现核心子集 Dify YAML 导入导出；遇到未支持节点、字段或版本时返回带路径的错误。

### 5. 核心执行闭环

关键文件：`workflow-runtime/.../WorkflowExecutor.java`、`workflow-infra/.../dify/DifyDslAdapter.java`。

- **User Input**：校验文本、数字、选择、布尔、JSON 输入；文件输入 Schema 可配置，文件运行能力后续与对象存储一起交付。
- **LLM**：Spring AI 连接可配置的内网 OpenAI 兼容端点，支持消息、变量替换、参数、结构化输出和流式事件。
- **If-Else**：实现 IF / ELIF / ELSE、AND / OR、文档列出的比较操作及未走路径跳过。
- **Code**：通过官方 Sandbox 真实执行 Python / JavaScript，校验声明输出；API / Worker JVM 不直接运行用户代码。
- **Output**：Workflow 中可选、允许多个，汇总已执行输出；没有 Output 时返回空结果。
- **Answer**：Chatflow 流式返回文本和变量替换结果。
- 第一期调度范围限定为确定性串行路径与条件分支；并行、循环、人工暂停恢复在后续阶段实现。
- 保存运行和节点状态、输入输出、耗时与异常，支持 SSE 心跳和事件续传；非幂等节点不做隐式自动重试。

### 6. 第一期验收

- 单元测试：图校验、条件矩阵、变量替换、Output 汇总、YAML 核心子集往返。
- 集成测试：PostgreSQL、官方 Sandbox、模拟 OpenAI 兼容服务、SSE 断线续传；模拟服务仅用于契约测试，不替代真实内网模型验收。
- Playwright：拖建并刷新恢复 Workflow / Chatflow；运行真假分支、两种 Code 语言、Output 和流式 Answer；错误能定位节点。
- 浏览器检查桌面布局、配置面板、空状态、长文本、运行状态及基础键盘操作。
- 构建、前端测试、后端测试和端到端测试全部通过后，再确认进入第二期。

## 后续阶段

### 第二期：完整控制与数据节点

并行 fork/join 与跳过分支汇合、Iteration / Loop 作用域帧、Human Input 暂停恢复、HTTP、Template、变量节点、List Operator、Document Extractor；扩展 DSL golden tests。

### 第三期：Dify 插件兼容

接入官方 `plugin-daemon`，实现 Model / Tool / Agent Strategy / Extension / Datasource / Trigger 插件管理、调用和反向调用适配；覆盖 Tool、Agent、Plugin Trigger。使用真实官方插件做协议回归，并隔离插件版本与权限。

### 第四期：知识库与模型生态

对象存储、文档解析、切分、嵌入、重排、pgvector 检索、元数据过滤、召回评估；接入本地模型服务和模型插件，完善 Knowledge Retrieval。

### 第五期：完整平台与离线交付

发布 / 历史 / 回滚、Schedule / Webhook Trigger、WebApp 与 Service API、会话管理、RBAC、审计、密钥管理、监控告警、备份恢复、容量压测、离线索引 / 镜像 / 依赖仓库和签名 SBOM。

## 安全约束

- Sandbox 非 root、禁网、只读文件系统，限制 CPU、内存、进程、执行时间和输出大小。
- HTTP / Webhook 防 SSRF，限制内网目标、重定向、DNS 重绑定、响应大小和超时。
- 密钥加密保存且日志脱敏；插件与代码执行均视为不可信边界。
- 所有运行副作用按至少一次执行设计，传递幂等键；非幂等 HTTP / Tool 默认不自动重试。

## 参考文档

- [Dify Key Concepts](https://docs.dify.ai/en/use-dify/getting-started/key-concepts)
- [Workflow & Chatflow](https://docs.dify.ai/en/use-dify/build/workflow-chatflow)
- [Orchestration Logic](https://docs.dify.ai/en/use-dify/build/orchestrate-node)
- [Dify Nodes](https://docs.dify.ai/en/use-dify/nodes)
- [Version Control](https://docs.dify.ai/en/use-dify/build/version-control)
- [Plugin Daemon](https://github.com/langgenius/dify-plugin-daemon)
- [Dify Sandbox](https://github.com/langgenius/dify-sandbox)
