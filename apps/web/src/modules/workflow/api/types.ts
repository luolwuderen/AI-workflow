export interface KnowledgeBase {
  id: string
  name: string
  description: string
  createdAt: string
  updatedAt: string
}

export interface KnowledgeDocument {
  id: string
  knowledgeBaseId: string
  name: string
  chunkCount: number
  createdAt: string
}

export interface KnowledgeDocumentDetail extends KnowledgeDocument {
  content: string
}

export interface KnowledgeHit {
  knowledgeBaseId: string
  documentId: string
  documentName: string
  content: string
  chunkIndex: number
  score: number
}

export type AppType = 'WORKFLOW' | 'CHATFLOW'
export type NodeConfig = Record<string, unknown>

export interface JsonSchema {
  type?: string | string[]
  title?: string
  description?: string
  default?: unknown
  enum?: unknown[]
  required?: string[]
  properties?: Record<string, JsonSchema>
  items?: JsonSchema
  additionalProperties?: JsonSchema | boolean
  minimum?: number
  maximum?: number
  minLength?: number
  maxLength?: number
  format?: string
  [key: string]: unknown
}

export type UISchema = Record<string, unknown>

export interface NodeOutput {
  name?: string
  path?: string
  type?: string
  title?: string
  description?: string
  [key: string]: unknown
}

export interface CatalogNode {
  type: string
  title: string
  description: string
  category: string
  appTypes: AppType[]
  executable: boolean
  supportMessage?: string
  configSchema: JsonSchema
  uiSchema: UISchema
  defaults: NodeConfig
  outputs: Array<NodeOutput | string> | Record<string, unknown>
}

export interface GraphNode {
  id: string
  type: string
  title: string
  position: { x: number; y: number }
  config: NodeConfig
  /** 所属作用域容器（Iteration / Loop）的节点 id；顶层节点为空 */
  parentId?: string
}

export interface GraphEdge {
  id: string
  source: string
  target: string
  sourceHandle?: string
  targetHandle?: string
}

export interface GraphDocument {
  version: 1
  appType: AppType
  nodes: GraphNode[]
  edges: GraphEdge[]
  viewport?: { x: number; y: number; zoom: number }
}

export interface WorkflowApp {
  id: string
  name: string
  appType: AppType
  graph: GraphDocument
  version: number
  updatedAt: string
}

export interface ValidationIssue {
  nodeId?: string
  field?: string
  path?: string
  code?: string
  message: string
  severity?: 'ERROR' | 'WARNING' | 'error' | 'warning'
}

export type RunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'SUCCEEDED' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | string
export type NodeRunStatus = 'PENDING' | 'RUNNING' | 'WAITING' | 'SUCCEEDED' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | string

export interface NodeRun {
  id?: string
  nodeId: string
  nodeType?: string
  title?: string
  status: NodeRunStatus
  inputs?: unknown
  outputs?: unknown
  error?: string
  durationMs?: number
  startedAt?: string
  finishedAt?: string
}

export interface WorkflowRun {
  id: string
  status: RunStatus
  outputs?: unknown
  answer?: string
  nodeRuns?: NodeRun[] | Record<string, NodeRun>
  error?: string
  createdAt?: string
  startedAt?: string
  finishedAt?: string
}

export interface RunEvent {
  id?: string | number
  sequence?: number
  type: string
  nodeId?: string
  status?: string
  message?: string
  timestamp?: string
  durationMs?: number
  data?: unknown
  payload?: unknown
  error?: string
}
