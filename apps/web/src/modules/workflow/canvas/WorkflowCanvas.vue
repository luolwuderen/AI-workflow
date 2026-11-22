<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { MarkerType, SelectionMode, VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection, Edge, EdgeChange, EdgeMouseEvent, EdgeUpdateEvent, Node, NodeChange, NodeMouseEvent } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import {
  AlignHorizontalDistributeCenter,
  Check,
  ClipboardPaste,
  Copy,
  CopyPlus,
  FilePlus2,
  LocateFixed,
  Play,
  Redo2,
  Trash2,
  Undo2,
  Unlink,
  X,
} from 'lucide-vue-next'
import type { GraphEdge } from '../api/types'
import WorkflowNode from '../nodes/WorkflowNode.vue'
import type { WorkflowNodeData } from '../nodes/WorkflowNode.vue'
import { useWorkflowStore } from '../store/workflow'
import { useRunStore } from '../store/run'

const emit = defineEmits<{ addAtCenter: [] }>()
const store = useWorkflowStore()
const runStore = useRunStore()
const { screenToFlowCoordinate, fitView, getViewport } = useVueFlow()
const flowWrap = ref<HTMLElement>()
let resizeObserver: ResizeObserver | undefined
let resizeTimer: ReturnType<typeof setTimeout> | undefined
const boxHint = ref(false)
const shiftHeld = ref(false)
const notice = ref('')
let hintTimer: ReturnType<typeof setTimeout> | undefined
let noticeTimer: ReturnType<typeof setTimeout> | undefined
type ContextMenuState = {
  kind: 'node' | 'edge' | 'pane'
  x: number
  y: number
  id?: string
}

const menu = ref<ContextMenuState | null>(null)
const edgeSync = ref(0)
const hoveredEdgeId = ref('')
const showIssues = ref(true)
const picker = ref<{ x: number; y: number; flow: { x: number; y: number }; source: string; handle?: string } | null>(null)
const pickerQuery = ref('')
const pickerInput = ref<HTMLInputElement>()
let connectDrag: { nodeId: string; handleId?: string; handleType?: string } | null = null
let connectCompleted = false

const flowNodes = computed<Node[]>(() => {
  if (!store.app) return []
  const graph = store.app.graph
  return graph.nodes.map((node) => ({
    id: node.id,
    type: 'weave',
    position: node.position,
    selected: store.selectedNodeIds.includes(node.id),
    data: {
      node,
      definition: store.definition(node.type),
      nodeRun: runStore.nodeRuns.find((run) => run.nodeId === node.id),
      scopeTitle: node.parentId ? graph.nodes.find((item) => item.id === node.parentId)?.title : undefined,
      childCount: graph.nodes.filter((item) => item.parentId === node.id).length,
    } satisfies WorkflowNodeData,
  }))
})

const flowEdges = computed<Edge[]>(() => {
  if (!store.app) return []
  // 非法重连后强制回写一次，让 Vue Flow 丢弃拖拽产生的临时连线
  void edgeSync.value
  return store.app.graph.edges.map((edge: GraphEdge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    sourceHandle: edge.sourceHandle,
    targetHandle: edge.targetHandle,
    type: 'smoothstep',
    markerEnd: MarkerType.ArrowClosed,
    class: [
      store.selectedNodeIds.includes(edge.source) || store.selectedNodeIds.includes(edge.target) || store.selectedEdgeIds.includes(edge.id) ? 'selected-edge' : '',
      hoveredEdgeId.value === edge.id ? 'drop-target' : '',
    ].filter(Boolean).join(' '),
    animated: runStore.nodeRuns.some((run) => run.nodeId === edge.source && run.status === 'RUNNING'),
  }))
})

function onNodesChange(changes: NodeChange[]) {
  const selection = new Set(store.selectedNodeIds)
  const removed: string[] = []
  for (const change of changes) {
    if (change.type === 'position' && change.position) {
      store.updateNodePosition(change.id, change.position, Boolean(change.dragging))
    } else if (change.type === 'select') {
      if (change.selected) selection.add(change.id)
      else selection.delete(change.id)
    } else if (change.type === 'remove') {
      removed.push(change.id)
    }
  }
  store.setSelection([...selection])
  if (removed.length) store.removeNodes(removed)
}

function onEdgesChange(changes: EdgeChange[]) {
  store.removeEdges(changes.filter((change) => change.type === 'remove').map((change) => change.id))
}

function showNotice(message: string) {
  notice.value = message
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { notice.value = '' }, 3200)
}

function onConnect(connection: Connection) {
  connectCompleted = true
  if (!connection.source || !connection.target) return
  const issue = store.connectionIssue(connection.source, connection.target)
  if (issue) {
    showNotice(issue)
    return
  }
  store.connect({
    source: connection.source,
    target: connection.target,
    sourceHandle: connection.sourceHandle ?? undefined,
    targetHandle: connection.targetHandle ?? undefined,
  })
}

function onEdgeClick({ edge, event }: EdgeMouseEvent) {
  store.selectEdge(edge.id, event.metaKey || event.ctrlKey || event.shiftKey)
}

function openMenu(next: ContextMenuState) {
  const canvas = flowWrap.value?.getBoundingClientRect()
  const right = canvas?.right ?? window.innerWidth
  const bottom = canvas?.bottom ?? window.innerHeight
  const height = next.kind === 'pane' ? 152 : next.kind === 'node' ? 120 : 44
  menu.value = {
    ...next,
    x: Math.max(8, Math.min(next.x, right - 192)),
    y: Math.max(8, Math.min(next.y, bottom - height)),
  }
}

function closeMenu() {
  menu.value = null
}

function pointerPosition(event: MouseEvent | TouchEvent) {
  if ('clientX' in event) return { x: event.clientX, y: event.clientY }
  const touch = event.touches[0] ?? event.changedTouches[0]
  return { x: touch?.clientX ?? 0, y: touch?.clientY ?? 0 }
}

function onNodeMenu({ node, event }: NodeMouseEvent) {
  event.preventDefault()
  if (!store.selectedNodeIds.includes(node.id)) store.selectNode(node.id)
  const point = pointerPosition(event)
  openMenu({ kind: 'node', id: node.id, x: point.x, y: point.y })
}

function onEdgeMenu({ edge, event }: EdgeMouseEvent) {
  event.preventDefault()
  store.selectEdge(edge.id)
  const point = pointerPosition(event)
  openMenu({ kind: 'edge', id: edge.id, x: point.x, y: point.y })
}

function onPaneMenu(event: MouseEvent) {
  event.preventDefault()
  const target = event.target as HTMLElement | null
  if (target?.closest('.vue-flow__node, .vue-flow__edge, .vue-flow__controls, .vue-flow__minimap, .context-menu')) return
  store.setSelection([])
  openMenu({ kind: 'pane', x: event.clientX, y: event.clientY })
}

function duplicateSelection() {
  if (!store.selectedNodeIds.length) return
  store.copySelection()
  store.paste()
  closeMenu()
}

function copySelection() {
  store.copySelection()
  closeMenu()
}

function deleteFromMenu() {
  if (menu.value?.kind === 'edge' && menu.value.id) {
    store.removeEdges([menu.value.id])
  } else if (store.selectedNodeIds.length) {
    store.removeNodes(store.selectedNodeIds)
  }
  closeMenu()
}

function pasteFromMenu() {
  store.paste()
  closeMenu()
}

function openPalette() {
  closeMenu()
  const search = document.querySelector<HTMLInputElement>('[data-testid="node-search"]')
  search?.focus()
  search?.select()
}

function applyAutoLayout() {
  closeMenu()
  void autoLayout()
}

function applyFitView() {
  closeMenu()
  void fitView({ padding: 0.18, duration: 250 })
}

function onGlobalMouseDown(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  if (target?.closest('.context-menu, .node-picker')) return
  closeMenu()
  closePicker()
}

function onGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    closeMenu()
    closePicker()
  }
}

const pickerNodes = computed(() => {
  const query = pickerQuery.value.trim().toLowerCase()
  if (!query) return store.visibleCatalog
  return store.visibleCatalog.filter((item) =>
    item.title.toLowerCase().includes(query) || item.type.toLowerCase().includes(query))
})

const pickerStyle = computed(() => {
  if (!picker.value) return {}
  const canvas = flowWrap.value?.getBoundingClientRect()
  const right = canvas?.right ?? window.innerWidth
  const bottom = canvas?.bottom ?? window.innerHeight
  return {
    left: `${Math.max(8, Math.min(picker.value.x, right - 256))}px`,
    top: `${Math.max(8, Math.min(picker.value.y, bottom - 320))}px`,
  }
})

function onConnectStart(params: { nodeId?: string | null; handleId?: string | null; handleType?: string | null }) {
  connectCompleted = false
  connectDrag = params.nodeId
    ? { nodeId: params.nodeId, handleId: params.handleId ?? undefined, handleType: params.handleType ?? 'source' }
    : null
}

function onConnectEnd(event?: MouseEvent | TouchEvent) {
  const drag = connectDrag
  connectDrag = null
  if (!drag || connectCompleted || drag.handleType !== 'source' || !event) return
  const target = event.target as HTMLElement | null
  if (!target?.closest('.vue-flow__pane')) return
  if (target.closest('.vue-flow__node, .vue-flow__edge, .vue-flow__controls, .vue-flow__minimap, .node-picker, .context-menu')) return
  const point = pointerPosition(event)
  pickerQuery.value = ''
  picker.value = {
    x: point.x,
    y: point.y,
    flow: screenToFlowCoordinate({ x: point.x, y: point.y }),
    source: drag.nodeId,
    handle: drag.handleId,
  }
  void nextTick(() => pickerInput.value?.focus())
}

function closePicker() {
  picker.value = null
  pickerQuery.value = ''
}

function pickNode(type: string) {
  const state = picker.value
  if (!state) return
  store.addNode(type, { x: state.flow.x - 105, y: state.flow.y - 42 }, { source: state.source, sourceHandle: state.handle })
  closePicker()
}

function pickFirst() {
  if (pickerNodes.value.length) pickNode(pickerNodes.value[0].type)
}

watch(() => store.app?.id, closePicker)

function onEdgeUpdate({ edge, connection }: EdgeUpdateEvent) {
  if (!connection.source || !connection.target) {
    edgeSync.value += 1
    return
  }
  const issue = store.reconnectEdge(edge.id, {
    source: connection.source,
    target: connection.target,
    sourceHandle: connection.sourceHandle ?? undefined,
    targetHandle: connection.targetHandle ?? undefined,
  })
  if (!issue) return
  showNotice(issue)
  edgeSync.value += 1
}

function edgeAtPoint(x: number, y: number): string {
  const element = document.elementFromPoint(x, y)?.closest<HTMLElement>('.vue-flow__edge')
  return element?.getAttribute('data-id') ?? ''
}

function onDragOver(event: DragEvent) {
  hoveredEdgeId.value = edgeAtPoint(event.clientX, event.clientY)
}

function onDragLeave(event: DragEvent) {
  const next = event.relatedTarget
  if (!(next instanceof HTMLElement) || !flowWrap.value?.contains(next)) hoveredEdgeId.value = ''
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  const type = event.dataTransfer?.getData('application/weave-node') || event.dataTransfer?.getData('text/plain')
  const edgeId = edgeAtPoint(event.clientX, event.clientY)
  hoveredEdgeId.value = ''
  if (!type) return
  const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  if (edgeId) {
    store.insertNodeOnEdge(type, edgeId, { x: position.x - 105, y: position.y - 42 })
    return
  }
  store.addNode(type, { x: position.x - 105, y: position.y - 42 })
}

async function autoLayout() {
  store.autoLayout()
  await nextTick()
  await fitView({ padding: 0.2, duration: 280 })
}

function saveViewport() {
  store.setViewport(getViewport())
}

// 画布尺寸变化（窗口缩放、断点切换）后重新适配，跳过首次观察以免覆盖已保存视图
function scheduleFit() {
  if (resizeTimer) clearTimeout(resizeTimer)
  resizeTimer = setTimeout(() => {
    if (!store.app || store.app.graph.nodes.length === 0) return
    void fitView({ padding: 0.18, duration: 250 })
  }, 220)
}

// 在空白处直接拖拽不会框选，给出一次即时引导
function onWrapMouseDown(event: MouseEvent) {
  if (event.button !== 0 || event.shiftKey) return
  const target = event.target as HTMLElement
  if (!target.closest('.vue-flow__pane')) return
  if (target.closest('.vue-flow__node, .vue-flow__edge, .vue-flow__controls, .vue-flow__minimap')) return
  boxHint.value = true
  if (hintTimer) clearTimeout(hintTimer)
  hintTimer = setTimeout(() => { boxHint.value = false }, 2600)
}

function onShiftKey(event: KeyboardEvent) {
  if (event.key === 'Shift') shiftHeld.value = event.type === 'keydown'
}

function resetShift() {
  shiftHeld.value = false
}

onMounted(() => {
  window.addEventListener('keydown', onShiftKey)
  window.addEventListener('keyup', onShiftKey)
  window.addEventListener('blur', resetShift)
  window.addEventListener('mousedown', onGlobalMouseDown)
  window.addEventListener('keydown', onGlobalKeydown)
  if (!flowWrap.value) return
  let observed = false
  resizeObserver = new ResizeObserver(() => {
    if (!observed) {
      observed = true
      return
    }
    scheduleFit()
  })
  resizeObserver.observe(flowWrap.value)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onShiftKey)
  window.removeEventListener('keyup', onShiftKey)
  window.removeEventListener('blur', resetShift)
  window.removeEventListener('mousedown', onGlobalMouseDown)
  window.removeEventListener('keydown', onGlobalKeydown)
  resizeObserver?.disconnect()
  if (resizeTimer) clearTimeout(resizeTimer)
  if (hintTimer) clearTimeout(hintTimer)
  if (noticeTimer) clearTimeout(noticeTimer)
})

async function validate() {
  showIssues.value = true
  await store.validateGraph()
}
</script>

<template>
  <section class="canvas-shell" data-testid="canvas">
    <div class="canvas-toolbar">
      <div class="toolbar-group">
        <button class="tool-button" :disabled="!store.canUndo" title="撤销 · ⌘Z" @click="store.undo()"><Undo2 :size="15" /><span>撤销</span></button>
        <button class="tool-button" :disabled="!store.canRedo" title="重做 · ⇧⌘Z" @click="store.redo()"><Redo2 :size="15" /><span>重做</span></button>
        <span class="toolbar-divider" />
        <button class="tool-button" title="自动布局" @click="autoLayout"><AlignHorizontalDistributeCenter :size="15" /><span>整理</span></button>
        <button class="tool-button" title="适应视图" @click="fitView({ padding: 0.18, duration: 250 })"><LocateFixed :size="15" /><span>适配</span></button>
        <span class="toolbar-divider" />
        <span class="toolbar-tip"><kbd>Shift</kbd> + 拖拽框选</span>
      </div>
      <div class="toolbar-group toolbar-actions">
        <button class="tool-button validate-button" data-testid="validate-button" :disabled="store.validating || !store.app" @click="validate">
          <Check :size="15" /><span>{{ store.validating ? '校验中' : '校验' }}</span>
        </button>
        <button class="primary-button run-button" data-testid="run-button" :disabled="!store.app" @click="runStore.openPanel()">
          <Play :size="14" fill="currentColor" /><span>运行</span><kbd>⌘↵</kbd>
        </button>
      </div>
    </div>

    <div
      ref="flowWrap"
      class="flow-wrap"
      :class="{ 'shift-select': shiftHeld }"
      @drop="onDrop"
      @dragover.prevent="onDragOver"
      @dragleave="onDragLeave"
      @mousedown="onWrapMouseDown"
      @contextmenu="onPaneMenu"
    >
      <VueFlow
        v-if="store.app"
        :key="store.app.id"
        :nodes="flowNodes"
        :edges="flowEdges"
        :default-viewport="store.app.graph.viewport"
        :min-zoom="0.2"
        :max-zoom="2.2"
        :selection-mode="SelectionMode.Partial"
        :selection-on-drag="true"
        :pan-on-drag="[1, 2]"
        :delete-key-code="null"
        :multi-selection-key-code="['Meta', 'Control']"
        :edges-updatable="true"
        :fit-view-on-init="!store.app.graph.viewport"
        @nodes-change="onNodesChange"
        @edges-change="onEdgesChange"
        @connect="onConnect"
        @connect-start="onConnectStart"
        @connect-end="onConnectEnd"
        @edge-update="onEdgeUpdate"
        @edge-click="onEdgeClick"
        @node-context-menu="onNodeMenu"
        @edge-context-menu="onEdgeMenu"
        @node-drag-start="store.beginMove()"
        @node-drag-stop="store.endMove()"
        @pane-click="store.setSelection([])"
        @move-end="saveViewport"
      >
        <template #node-weave="nodeProps">
          <WorkflowNode v-bind="nodeProps" />
        </template>
        <Background variant="lines" :gap="24" :size="1" color="#e4e1da" />
        <Controls position="bottom-left" :show-interactive="false" />
        <MiniMap position="bottom-right" :pannable="true" :zoomable="true" node-color="#b8b7ae" mask-color="rgba(246,244,239,.72)" />
      </VueFlow>
      <div v-if="store.app?.graph.nodes.length === 0" class="canvas-empty">
        <div class="empty-diagram"><span /><span /><span /></div>
        <h3>从一个节点开始编排</h3>
        <p>将左侧节点拖到画布，或单击目录项添加。</p>
        <button class="secondary-button" @click="emit('addAtCenter')">添加起始节点</button>
      </div>
      <transition name="hint-fade">
        <div v-if="notice" class="canvas-guide warning" role="alert">{{ notice }}</div>
        <div v-else-if="boxHint" class="canvas-guide" role="status">按住 <kbd>Shift</kbd> 拖拽即可框选节点</div>
      </transition>
    </div>

    <div v-if="store.issues.length && showIssues" class="validation-dock">
      <div class="validation-title">
        <span>校验发现 {{ store.issues.length }} 项</span>
        <button class="icon-button" title="关闭" @click="showIssues = false"><X :size="14" /></button>
      </div>
      <div class="validation-list">
        <button v-for="(issue, index) in store.issues" :key="`${issue.nodeId}-${index}`" @click="store.locateIssue(issue)">
          <span class="issue-code">{{ issue.severity?.toLowerCase() === 'warning' ? '警告' : '错误' }}</span>
          <span>{{ issue.message }}</span>
          <code v-if="issue.nodeId">{{ issue.nodeId }}</code>
        </button>
      </div>
    </div>

    <div
      v-if="menu"
      class="context-menu"
      role="menu"
      data-testid="context-menu"
      :style="{ left: `${menu.x}px`, top: `${menu.y}px` }"
      @contextmenu.prevent
    >
      <template v-if="menu.kind === 'node'">
        <button class="context-item" role="menuitem" @click="duplicateSelection"><CopyPlus :size="13" /><span>创建副本</span><kbd>⌘D</kbd></button>
        <button class="context-item" role="menuitem" @click="copySelection"><Copy :size="13" /><span>复制</span><kbd>⌘C</kbd></button>
        <span class="context-divider" />
        <button class="context-item danger" role="menuitem" @click="deleteFromMenu"><Trash2 :size="13" /><span>删除节点</span><kbd>Delete</kbd></button>
      </template>
      <template v-else-if="menu.kind === 'edge'">
        <button class="context-item danger" role="menuitem" @click="deleteFromMenu"><Unlink :size="13" /><span>删除连线</span><kbd>Delete</kbd></button>
      </template>
      <template v-else>
        <button class="context-item" role="menuitem" :disabled="!store.clipboard" @click="pasteFromMenu">
          <ClipboardPaste :size="13" /><span>粘贴节点</span><kbd>⌘V</kbd>
        </button>
        <button class="context-item" role="menuitem" @click="openPalette"><FilePlus2 :size="13" /><span>添加节点</span></button>
        <span class="context-divider" />
        <button class="context-item" role="menuitem" @click="applyAutoLayout"><AlignHorizontalDistributeCenter :size="13" /><span>自动布局</span></button>
        <button class="context-item" role="menuitem" @click="applyFitView"><LocateFixed :size="13" /><span>适应视图</span></button>
      </template>
    </div>

    <div v-if="picker" class="node-picker" data-testid="node-picker" :style="pickerStyle" @contextmenu.prevent>
      <div class="picker-heading">
        <span>添加并连接节点</span>
        <kbd>↵ 首个结果</kbd>
      </div>
      <input
        ref="pickerInput"
        v-model="pickerQuery"
        class="picker-search"
        data-testid="node-picker-search"
        placeholder="搜索节点…"
        @keydown.enter="pickFirst"
      />
      <div class="picker-list">
        <button
          v-for="item in pickerNodes"
          :key="item.type"
          class="picker-item"
          :data-picker-type="item.type"
          @click="pickNode(item.type)"
        >
          <span class="picker-title">{{ item.title }}</span>
          <span class="picker-category">{{ item.category }}</span>
          <em v-if="!item.executable">不可执行</em>
        </button>
        <div v-if="!pickerNodes.length" class="picker-empty">没有匹配的节点</div>
      </div>
    </div>
  </section>
</template>
