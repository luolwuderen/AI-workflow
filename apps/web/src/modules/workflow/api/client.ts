import type {
  AppType,
  GraphDocument,
  KnowledgeBase,
  KnowledgeDocument,
  KnowledgeDocumentDetail,
  KnowledgeHit,
  RunEvent,
  ValidationIssue,
  WorkflowApp,
  WorkflowRun,
  CatalogNode,
} from './types'

const API_ROOT = '/api/v1'

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly details?: unknown,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function errorMessage(response: Response): Promise<{ message: string; details?: unknown }> {
  const text = await response.text()
  if (!text) return { message: `请求失败（${response.status}）` }
  try {
    const body = JSON.parse(text) as { message?: string; error?: string; detail?: string }
    return {
      message: body.message ?? body.detail ?? body.error ?? `请求失败（${response.status}）`,
      details: body,
    }
  } catch {
    return { message: text, details: text }
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_ROOT}${path}`, init)
  if (!response.ok) {
    const error = await errorMessage(response)
    throw new ApiError(error.message, response.status, error.details)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

function jsonHeaders(init?: RequestInit): RequestInit {
  return {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  }
}

type RunPayload = WorkflowRun & {
  nodes?: Array<{
    nodeId: string
    status: string
    title?: string
    inputs?: unknown
    outputs?: unknown
    error?: string
    durationMs?: number
    durationMillis?: number
    startedAt?: string
    finishedAt?: string
  }>
}

function normalizeRun(run: RunPayload): WorkflowRun {
  const nodeRuns = run.nodeRuns ?? run.nodes?.map((node) => ({
    ...node,
    durationMs: node.durationMs ?? node.durationMillis,
  }))
  const outputs = run.outputs
  const answer = run.answer ?? (typeof outputs === 'object' && outputs !== null && !Array.isArray(outputs)
    ? String((outputs as Record<string, unknown>).answer ?? '') || undefined
    : undefined)
  return { ...run, nodeRuns, answer }
}

export const workflowApi = {
  catalog: () => request<CatalogNode[]>('/catalog'),
  listApps: () => request<WorkflowApp[]>('/apps'),
  getApp: (id: string) => request<WorkflowApp>(`/apps/${encodeURIComponent(id)}`),
  createApp: (name: string, appType: AppType, graph: GraphDocument) =>
    request<WorkflowApp>('/apps', jsonHeaders({
      method: 'POST',
      body: JSON.stringify({ name, appType, graph }),
    })),
  saveDraft: (id: string, graph: GraphDocument, expectedVersion: number) =>
    request<WorkflowApp>(`/apps/${encodeURIComponent(id)}/draft`, jsonHeaders({
      method: 'PUT',
      body: JSON.stringify({ graph, expectedVersion }),
    })),
  validate: (id: string, graph: GraphDocument) =>
    request<ValidationIssue[] | { issues: ValidationIssue[] }>(`/apps/${encodeURIComponent(id)}/validate`, jsonHeaders({
      method: 'POST',
      body: JSON.stringify({ graph }),
    })),
  createRun: (appId: string, inputs: Record<string, unknown>) =>
    request<RunPayload>(`/apps/${encodeURIComponent(appId)}/runs`, jsonHeaders({
      method: 'POST',
      body: JSON.stringify({ inputs }),
    })).then(normalizeRun),
  getRun: (runId: string) => request<RunPayload>(`/runs/${encodeURIComponent(runId)}`).then(normalizeRun),
  resumeRun: (runId: string, nodeId: string, inputs: Record<string, unknown>) =>
    request<RunPayload>(`/runs/${encodeURIComponent(runId)}/nodes/${encodeURIComponent(nodeId)}/resume`, jsonHeaders({
      method: 'POST',
      body: JSON.stringify({ inputs }),
    })).then(normalizeRun),
  importDify: (yaml: string) =>
    request<WorkflowApp | GraphDocument>('/dify/import', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: yaml,
    }),
  exportDify: async (appId: string) => {
    const response = await fetch(`${API_ROOT}/apps/${encodeURIComponent(appId)}/dify/export`)
    if (!response.ok) {
      const error = await errorMessage(response)
      throw new ApiError(error.message, response.status, error.details)
    }
    return response.text()
  },
}

export const knowledgeApi = {
  list: () => request<KnowledgeBase[]>('/knowledge-bases'),
  create: (name: string, description: string) =>
    request<KnowledgeBase>('/knowledge-bases', jsonHeaders({
      method: 'POST',
      body: JSON.stringify({ name, description }),
    })),
  remove: (id: string) =>
    request<void>(`/knowledge-bases/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  documents: (id: string) =>
    request<KnowledgeDocument[]>(`/knowledge-bases/${encodeURIComponent(id)}/documents`),
  document: (id: string, documentId: string) =>
    request<KnowledgeDocumentDetail>(
      `/knowledge-bases/${encodeURIComponent(id)}/documents/${encodeURIComponent(documentId)}`),
  addDocument: (id: string, name: string, content: string) =>
    request<KnowledgeDocument>(`/knowledge-bases/${encodeURIComponent(id)}/documents`, jsonHeaders({
      method: 'POST',
      body: JSON.stringify({ name, content }),
    })),
  removeDocument: (id: string, documentId: string) =>
    request<void>(`/knowledge-bases/${encodeURIComponent(id)}/documents/${encodeURIComponent(documentId)}`,
      { method: 'DELETE' }),
  search: (datasetIds: string[], query: string, topK: number, scoreThreshold: number) =>
    request<KnowledgeHit[]>('/knowledge-bases/search', jsonHeaders({
      method: 'POST',
      body: JSON.stringify({ datasetIds, query, topK, scoreThreshold }),
    })),
}

interface StreamOptions {
  after?: number
  onEvent: (event: RunEvent) => void
  onError?: (error: Error) => void
}

export function streamRunEvents(runId: string, options: StreamOptions): () => void {
  const controller = new AbortController()
  const after = options.after ?? 0

  void (async () => {
    try {
      const response = await fetch(
        `${API_ROOT}/runs/${encodeURIComponent(runId)}/events/stream?after=${after}`,
        { headers: { Accept: 'text/event-stream' }, signal: controller.signal },
      )
      if (!response.ok) {
        const error = await errorMessage(response)
        throw new ApiError(error.message, response.status, error.details)
      }
      if (!response.body) throw new Error('浏览器未收到运行事件流')
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
        let boundary = buffer.indexOf('\n\n')
        while (boundary >= 0) {
          const block = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          const data = block.split('\n')
            .filter((line) => line.startsWith('data:'))
            .map((line) => line.slice(5).trimStart())
            .join('\n')
          if (data && data !== '[DONE]') {
            try {
              const event = JSON.parse(data) as RunEvent & { createdAt?: string }
              options.onEvent({ ...event, timestamp: event.timestamp ?? event.createdAt })
            } catch {
              // Heartbeats and non-JSON event data do not affect polling recovery.
            }
          }
          boundary = buffer.indexOf('\n\n')
        }
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        options.onError?.(error instanceof Error ? error : new Error(String(error)))
      }
    }
  })()

  return () => controller.abort()
}
