import { defineStore } from 'pinia'
import { workflowApi, ApiError } from '../api/client'
import type {
  AppType,
  CatalogNode,
  GraphDocument,
  GraphEdge,
  GraphNode,
  NodeConfig,
  ValidationIssue,
  WorkflowApp,
} from '../api/types'
import { cloneGraph, cloneJson, createId, setAtPath } from '../../../shared/utils/graph'
import { GraphHistory } from './graphHistory'
import { layoutGraph } from '../canvas/layout'

export type SaveState = 'saved' | 'dirty' | 'saving' | 'error' | 'conflict'

function buildGraphNode(type: string, definition: CatalogNode, position: { x: number; y: number }, sameTypeCount: number): GraphNode {
  return {
    id: createId(type.toLowerCase().replace(/[^a-z0-9]+/g, '-')),
    type,
    title: sameTypeCount ? `${definition.title} ${sameTypeCount + 1}` : definition.title,
    position,
    config: cloneJson(definition.defaults ?? {}) as NodeConfig,
  }
}

interface Clipboard {
  nodes: GraphNode[]
  edges: GraphEdge[]
}

interface WorkflowState {
  app: WorkflowApp | null
  apps: WorkflowApp[]
  catalog: CatalogNode[]
  selectedNodeIds: string[]
  selectedEdgeIds: string[]
  issues: ValidationIssue[]
  focusRequest: { field: string; nonce: number } | null
  loading: boolean
  validating: boolean
  importing: boolean
  saveState: SaveState
  saveError: string
  operationError: string
  importError: string
  revision: number
  canUndo: boolean
  canRedo: boolean
  clipboard: Clipboard | null
}

const history = new GraphHistory()
let saveTimer: ReturnType<typeof setTimeout> | undefined
let activeSave: Promise<void> | null = null
let saveQueued = false

function emptyGraph(appType: AppType): GraphDocument {
  return { version: 1, appType, nodes: [], edges: [] }
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function fieldFromIssue(issue: ValidationIssue): string {
  if (issue.field) return issue.field
  const match = issue.path?.match(/(?:^|\.)config\.(.+)$/)
  return match?.[1] ?? issue.path ?? ''
}

export const useWorkflowStore = defineStore('workflow', {
  state: (): WorkflowState => ({
    app: null,
    apps: [],
    catalog: [],
    selectedNodeIds: [],
    selectedEdgeIds: [],
    issues: [],
    focusRequest: null,
    loading: true,
    validating: false,
    importing: false,
    saveState: 'saved',
    saveError: '',
    operationError: '',
    importError: '',
    revision: 0,
    canUndo: false,
    canRedo: false,
    clipboard: null,
  }),

  getters: {
    selectedNode(state): GraphNode | null {
      const id = state.selectedNodeIds[0]
      return state.app?.graph.nodes.find((node) => node.id === id) ?? null
    },
    selectedDefinition(state): CatalogNode | undefined {
      const selectedId = state.selectedNodeIds[0]
      const selected = state.app?.graph.nodes.find((node) => node.id === selectedId)
      return state.catalog.find((definition) => definition.type === selected?.type)
    },
    visibleCatalog(state): CatalogNode[] {
      if (!state.app) return []
      return state.catalog.filter((definition) => definition.appTypes.includes(state.app!.appType))
    },
    definition(state) {
      return (type: string) => state.catalog.find((definition) => definition.type === type)
    },
    saveLabel(state): string {
      const labels: Record<SaveState, string> = {
        saved: '草稿已保存',
        dirty: '有待保存更改',
        saving: '正在保存…',
        error: '保存失败',
        conflict: '版本冲突',
      }
      return labels[state.saveState]
    },
  },

  actions: {
    syncHistory() {
      this.canUndo = history.canUndo
      this.canRedo = history.canRedo
    },

    setApp(app: WorkflowApp) {
      if (saveTimer) clearTimeout(saveTimer)
      this.app = {
        ...app,
        graph: {
          ...emptyGraph(app.appType),
          ...app.graph,
          appType: app.appType,
          nodes: app.graph?.nodes ?? [],
          edges: app.graph?.edges ?? [],
        },
      }
      this.selectedNodeIds = []
      this.selectedEdgeIds = []
      this.issues = []
      this.focusRequest = null
      this.saveState = 'saved'
      this.saveError = ''
      this.operationError = ''
      this.importError = ''
      this.revision += 1
      history.clear()
      this.syncHistory()
    },

    async bootstrap() {
      this.loading = true
      this.operationError = ''
      try {
        const [catalog, apps] = await Promise.all([workflowApi.catalog(), workflowApi.listApps()])
        this.catalog = catalog
        this.apps = apps
        if (apps.length > 0) {
          this.setApp(await workflowApi.getApp(apps[0].id))
        } else {
          const graph = emptyGraph('WORKFLOW')
          const app = await workflowApi.createApp('未命名工作流', 'WORKFLOW', graph)
          this.apps = [app]
          this.setApp(app)
        }
      } catch (error) {
        this.operationError = `无法连接工作流服务：${messageOf(error)}`
      } finally {
        this.loading = false
      }
    },

    async loadApp(id: string) {
      if (this.app?.id === id) return
      await this.saveNow()
      if (this.saveState === 'conflict' || this.saveState === 'error') return
      this.loading = true
      this.operationError = ''
      try {
        const app = await workflowApi.getApp(id)
        this.setApp(app)
      } catch (error) {
        this.operationError = messageOf(error)
      } finally {
        this.loading = false
      }
    },

    async createNew(appType: AppType) {
      await this.saveNow()
      if (this.saveState === 'conflict' || this.saveState === 'error') return
      this.loading = true
      this.operationError = ''
      try {
        const name = appType === 'WORKFLOW' ? '未命名工作流' : '未命名对话流'
        const app = await workflowApi.createApp(name, appType, emptyGraph(appType))
        this.apps = [app, ...this.apps]
        this.setApp(app)
      } catch (error) {
        this.operationError = messageOf(error)
      } finally {
        this.loading = false
      }
    },

    scheduleSave() {
      if (!this.app || this.saveState === 'conflict') return
      if (saveTimer) clearTimeout(saveTimer)
      saveTimer = setTimeout(() => { void this.saveNow() }, 900)
    },

    markChanged(schedule = true) {
      this.revision += 1
      if (this.saveState !== 'conflict') this.saveState = 'dirty'
      this.saveError = ''
      this.syncHistory()
      if (schedule) this.scheduleSave()
    },

    async saveNow(): Promise<void> {
      if (saveTimer) clearTimeout(saveTimer)
      saveTimer = undefined
      if (!this.app || this.saveState === 'conflict') return
      if (activeSave) {
        saveQueued = true
        await activeSave
        if (this.app && this.saveState === 'dirty') {
          saveQueued = false
          return this.saveNow()
        }
        return
      }
      const appId = this.app.id
      const expectedVersion = this.app.version
      const snapshot = cloneGraph(this.app.graph)
      const savedRevision = this.revision
      this.saveState = 'saving'
      this.saveError = ''
      activeSave = workflowApi.saveDraft(appId, snapshot, expectedVersion)
        .then((updated) => {
          if (this.app?.id !== appId) return
          this.app.version = updated.version
          this.app.updatedAt = updated.updatedAt
          const index = this.apps.findIndex((item) => item.id === appId)
          if (index >= 0) this.apps[index] = { ...this.apps[index], version: updated.version, updatedAt: updated.updatedAt }
          this.saveState = this.revision === savedRevision ? 'saved' : 'dirty'
        })
        .catch((error: unknown) => {
          if (this.app?.id !== appId) return
          this.saveState = error instanceof ApiError && error.status === 409 ? 'conflict' : 'error'
          this.saveError = this.saveState === 'conflict'
            ? '草稿已在其他窗口更新。请重新加载最新版本后再继续。'
            : messageOf(error)
        })
        .finally(() => {
          activeSave = null
          if (this.app?.id === appId && this.saveState !== 'conflict') {
            if (saveQueued || this.revision !== savedRevision) {
              saveQueued = false
              this.scheduleSave()
            }
          }
        })
      return activeSave
    },

    mutateGraph(mutator: (graph: GraphDocument) => void) {
      if (!this.app) return
      history.capture(this.app.graph)
      mutator(this.app.graph)
      this.markChanged()
    },

    addNode(type: string, position: { x: number; y: number }, connectFrom?: { source: string; sourceHandle?: string }): string | undefined {
      const definition = this.catalog.find((item) => item.type === type)
      if (!definition || !this.app) return undefined
      const node = buildGraphNode(type, definition, position, this.app.graph.nodes.filter((item) => item.type === type).length)
      this.mutateGraph((graph) => {
        graph.nodes.push(node)
        const sourceScope = graph.nodes.find((item) => item.id === connectFrom?.source)?.parentId ?? null
        if (connectFrom && sourceScope === null) {
          graph.edges.push({
            id: createId('edge'),
            source: connectFrom.source,
            target: node.id,
            sourceHandle: connectFrom.sourceHandle,
            targetHandle: 'target',
          })
        }
      })
      this.selectedNodeIds = [node.id]
      return node.id
    },

    insertNodeOnEdge(type: string, edgeId: string, position: { x: number; y: number }): string | undefined {
      const definition = this.catalog.find((item) => item.type === type)
      const edge = this.app?.graph.edges.find((item) => item.id === edgeId)
      if (!definition || !this.app || !edge) return undefined
      const node = buildGraphNode(type, definition, position, this.app.graph.nodes.filter((item) => item.type === type).length)
      const upstream = { source: edge.source, sourceHandle: edge.sourceHandle, targetHandle: edge.targetHandle, target: edge.target }
      this.mutateGraph((graph) => {
        graph.nodes.push(node)
        graph.edges = graph.edges.filter((item) => item.id !== edgeId)
        graph.edges.push(
          { id: createId('edge'), source: upstream.source, target: node.id, sourceHandle: upstream.sourceHandle, targetHandle: 'target' },
          { id: createId('edge'), source: node.id, target: upstream.target, sourceHandle: 'source', targetHandle: upstream.targetHandle },
        )
      })
      this.selectedNodeIds = [node.id]
      return node.id
    },

    updateNodeTitle(nodeId: string, title: string) {
      this.mutateGraph((graph) => {
        const node = graph.nodes.find((item) => item.id === nodeId)
        if (node) node.title = title
      })
    },

    updateNodeConfig(nodeId: string, path: Array<string | number>, value: unknown) {
      this.mutateGraph((graph) => {
        const node = graph.nodes.find((item) => item.id === nodeId)
        if (node) node.config = setAtPath(node.config, path, value)
      })
    },

    replaceNodeConfig(nodeId: string, config: NodeConfig) {
      this.mutateGraph((graph) => {
        const node = graph.nodes.find((item) => item.id === nodeId)
        if (node) node.config = config
      })
    },

    beginMove() {
      if (this.app) history.begin(this.app.graph)
    },

    updateNodePosition(nodeId: string, position: { x: number; y: number }, dragging: boolean) {
      const node = this.app?.graph.nodes.find((item) => item.id === nodeId)
      if (!node) return
      node.position = { ...position }
      if (!dragging) this.markChanged(false)
      else {
        this.revision += 1
        if (this.saveState !== 'conflict') this.saveState = 'dirty'
      }
    },

    endMove() {
      if (!this.app) return
      history.end(this.app.graph)
      this.markChanged()
    },

    removeNodes(ids: string[]) {
      if (ids.length === 0 || !this.app) return
      // 删除容器时级联删除作用域内节点，避免留下无主子节点
      const removed = new Set(ids)
      let grew = true
      while (grew) {
        grew = false
        for (const node of this.app.graph.nodes) {
          if (node.parentId && removed.has(node.parentId) && !removed.has(node.id)) {
            removed.add(node.id)
            grew = true
          }
        }
      }
      this.mutateGraph((graph) => {
        graph.nodes = graph.nodes.filter((node) => !removed.has(node.id))
        graph.edges = graph.edges.filter((edge) => !removed.has(edge.source) && !removed.has(edge.target))
      })
      this.selectedNodeIds = this.selectedNodeIds.filter((id) => !removed.has(id))
    },

    setNodeParent(nodeId: string, parentId?: string) {
      this.mutateGraph((graph) => {
        const node = graph.nodes.find((item) => item.id === nodeId)
        const parent = parentId ? graph.nodes.find((item) => item.id === parentId) : undefined
        if (!node || (parentId && (!parent || (parent.type !== 'iteration' && parent.type !== 'loop')))) return
        if (parentId) node.parentId = parentId
        else delete node.parentId
        // 作用域变更后清理跨作用域连线
        const scopeOf = (id: string) => graph.nodes.find((item) => item.id === id)?.parentId ?? null
        graph.edges = graph.edges.filter((edge) => scopeOf(edge.source) === scopeOf(edge.target))
      })
    },

    removeEdges(ids: string[]) {
      if (ids.length === 0) return
      const removed = new Set(ids)
      this.mutateGraph((graph) => {
        graph.edges = graph.edges.filter((edge) => !removed.has(edge.id))
      })
      this.selectedEdgeIds = this.selectedEdgeIds.filter((id) => !removed.has(id))
    },

    connectionIssue(source: string, target: string, ignoreEdgeId?: string): string | null {
      if (!this.app) return '画布尚未加载。'
      const edges = ignoreEdgeId ? this.app.graph.edges.filter((edge) => edge.id !== ignoreEdgeId) : this.app.graph.edges
      if (source === target) return '不允许自连接。'
      if (edges.some((edge) => edge.source === source && edge.target === target)) return '这条连线已经存在。'
      const sourceNode = this.app.graph.nodes.find((node) => node.id === source)
      if (!sourceNode) return '源节点不存在。'
      const targetNode = this.app.graph.nodes.find((node) => node.id === target)
      if ((sourceNode.parentId ?? null) !== (targetNode?.parentId ?? null)) return '连线两端必须在同一作用域。'
      const queue = [target]
      const visited = new Set<string>()
      while (queue.length) {
        const current = queue.shift() as string
        if (current === source) return '图不能包含环。'
        if (visited.has(current)) continue
        visited.add(current)
        edges.filter((edge) => edge.source === current).forEach((edge) => queue.push(edge.target))
      }
      return null
    },

    reconnectEdge(id: string, connection: Omit<GraphEdge, 'id'>): string | null {
      if (!this.app) return '画布尚未加载。'
      const edge = this.app.graph.edges.find((item) => item.id === id)
      if (!edge) return '连线不存在。'
      const issue = this.connectionIssue(connection.source, connection.target, id)
      if (issue) return issue
      this.mutateGraph((graph) => {
        const target = graph.edges.find((item) => item.id === id)
        if (!target) return
        target.source = connection.source
        target.target = connection.target
        target.sourceHandle = connection.sourceHandle
        target.targetHandle = connection.targetHandle
      })
      return null
    },

    connect(connection: Omit<GraphEdge, 'id'>) {
      if (!this.app || connection.source === connection.target) return
      const sourceScope = this.app.graph.nodes.find((node) => node.id === connection.source)?.parentId ?? null
      const targetScope = this.app.graph.nodes.find((node) => node.id === connection.target)?.parentId ?? null
      if (sourceScope !== targetScope) return
      const duplicate = this.app.graph.edges.some((edge) =>
        edge.source === connection.source && edge.target === connection.target
        && edge.sourceHandle === connection.sourceHandle && edge.targetHandle === connection.targetHandle)
      if (duplicate) return
      this.mutateGraph((graph) => {
        graph.edges.push({ ...connection, id: createId('edge') })
      })
    },

    selectNode(id: string, additive = false) {
      if (additive) {
        this.selectedNodeIds = this.selectedNodeIds.includes(id)
          ? this.selectedNodeIds.filter((item) => item !== id)
          : [...this.selectedNodeIds, id]
      } else {
        this.selectedNodeIds = id ? [id] : []
      }
      this.selectedEdgeIds = []
    },

    selectEdge(id: string, additive = false) {
      if (additive) {
        this.selectedEdgeIds = this.selectedEdgeIds.includes(id)
          ? this.selectedEdgeIds.filter((item) => item !== id)
          : [...this.selectedEdgeIds, id]
      } else {
        this.selectedEdgeIds = [id]
        this.selectedNodeIds = []
      }
    },

    setSelection(ids: string[]) {
      this.selectedNodeIds = [...new Set(ids)]
      if (this.selectedNodeIds.length || ids.length === 0) this.selectedEdgeIds = []
    },

    copySelection() {
      if (!this.app || this.selectedNodeIds.length === 0) return
      const selected = new Set(this.selectedNodeIds)
      this.clipboard = {
        nodes: cloneJson(this.app.graph.nodes.filter((node) => selected.has(node.id))),
        edges: cloneJson(this.app.graph.edges.filter((edge) => selected.has(edge.source) && selected.has(edge.target))),
      }
    },

    paste() {
      if (!this.clipboard || this.clipboard.nodes.length === 0) return
      const clipboard = cloneJson(this.clipboard)
      const ids = new Map<string, string>()
      for (const node of clipboard.nodes) ids.set(node.id, createId('node'))
      const newNodes = clipboard.nodes.map((node) => ({
        ...node,
        id: ids.get(node.id)!,
        position: { x: node.position.x + 36, y: node.position.y + 36 },
      }))
      const newEdges = clipboard.edges.map((edge) => ({
        ...edge,
        id: createId('edge'),
        source: ids.get(edge.source)!,
        target: ids.get(edge.target)!,
      }))
      this.mutateGraph((graph) => {
        graph.nodes.push(...newNodes)
        graph.edges.push(...newEdges)
      })
      this.selectedNodeIds = newNodes.map((node) => node.id)
      this.clipboard = clipboard
    },

    undo() {
      if (!this.app) return
      const graph = history.undo(this.app.graph)
      if (!graph) return
      this.app.graph = graph
      this.selectedNodeIds = this.selectedNodeIds.filter((id) => graph.nodes.some((node) => node.id === id))
      this.selectedEdgeIds = this.selectedEdgeIds.filter((id) => graph.edges.some((edge) => edge.id === id))
      this.markChanged()
    },

    redo() {
      if (!this.app) return
      const graph = history.redo(this.app.graph)
      if (!graph) return
      this.app.graph = graph
      this.selectedNodeIds = this.selectedNodeIds.filter((id) => graph.nodes.some((node) => node.id === id))
      this.selectedEdgeIds = this.selectedEdgeIds.filter((id) => graph.edges.some((edge) => edge.id === id))
      this.markChanged()
    },

    autoLayout() {
      if (!this.app) return
      history.capture(this.app.graph)
      this.app.graph = layoutGraph(this.app.graph)
      this.markChanged()
    },

    setViewport(viewport: { x: number; y: number; zoom: number }) {
      if (!this.app) return
      this.app.graph.viewport = viewport
      this.revision += 1
      if (this.saveState !== 'conflict') this.saveState = 'dirty'
      this.scheduleSave()
    },

    async validateGraph(locate = true): Promise<boolean> {
      if (!this.app) return false
      this.validating = true
      this.operationError = ''
      try {
        const result = await workflowApi.validate(this.app.id, cloneGraph(this.app.graph))
        const rawIssues = Array.isArray(result) ? result : result.issues
        const issues = rawIssues.map((issue) => {
          const indexMatch = issue.path?.match(/^\$\.nodes\[(\d+)\]/)
          const namedMatch = issue.path?.match(/^\$\.nodes\[id=([^\]]+)\]/)
          const nodeId = issue.nodeId ?? namedMatch?.[1]
            ?? (indexMatch ? this.app!.graph.nodes[Number(indexMatch[1])]?.id : undefined)
          return { ...issue, nodeId }
        })
        this.issues = issues
        if (locate && issues.length > 0) this.locateIssue(issues[0])
        return !issues.some((issue) => issue.severity?.toLowerCase() !== 'warning')
      } catch (error) {
        this.operationError = `校验失败：${messageOf(error)}`
        return false
      } finally {
        this.validating = false
      }
    },

    locateIssue(issue: ValidationIssue) {
      if (issue.nodeId && this.app?.graph.nodes.some((node) => node.id === issue.nodeId)) {
        this.selectedNodeIds = [issue.nodeId]
      }
      this.focusRequest = { field: fieldFromIssue(issue), nonce: Date.now() }
    },

    async reloadConflict() {
      if (!this.app) return
      this.loading = true
      try {
        this.setApp(await workflowApi.getApp(this.app.id))
      } catch (error) {
        this.saveError = messageOf(error)
      } finally {
        this.loading = false
      }
    },

    async importYaml(yaml: string, name = '导入工作流') {
      await this.saveNow()
      if (this.saveState === 'conflict' || this.saveState === 'error') {
        this.importError = '请先处理当前草稿的保存问题，再导入 YAML。'
        return false
      }
      this.importing = true
      this.importError = ''
      try {
        const imported = await workflowApi.importDify(yaml)
        let app: WorkflowApp
        if ('graph' in imported) {
          app = imported
        } else {
          const appName = name.replace(/\.(yaml|yml)$/i, '') || '导入工作流'
          const created = await workflowApi.createApp(appName, imported.appType, imported)
          app = await workflowApi.saveDraft(created.id, imported, created.version)
        }
        this.apps = [app, ...this.apps.filter((item) => item.id !== app.id)]
        this.setApp(app)
        return true
      } catch (error) {
        this.importError = messageOf(error)
        return false
      } finally {
        this.importing = false
      }
    },

    async refreshApps() {
      this.apps = await workflowApi.listApps()
    },
  },
})
