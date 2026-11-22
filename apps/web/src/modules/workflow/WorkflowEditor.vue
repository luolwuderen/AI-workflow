<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  AlertCircle,
  Check,
  ChevronDown,
  CloudOff,
  Download,
  FilePlus2,
  LoaderCircle,
  RefreshCw,
  Save,
  Upload,
  Workflow,
  X,
} from 'lucide-vue-next'
import type { AppType } from './api/types'
import { workflowApi } from './api/client'
import NodePalette from './nodes/NodePalette.vue'
import WorkflowCanvas from './canvas/WorkflowCanvas.vue'
import NodeInspector from './inspector/NodeInspector.vue'
import RunPanel from './canvas/RunPanel.vue'
import { useWorkflowStore } from './store/workflow'
import { useRunStore } from './store/run'

const store = useWorkflowStore()
const runStore = useRunStore()
const newAppType = ref<AppType>('WORKFLOW')
const fileInput = ref<HTMLInputElement>()
const exporting = ref(false)
const selectedAppId = computed({
  get: () => store.app?.id ?? '',
  set: (id: string) => { void store.loadApp(id) },
})

function addNode(type: string) {
  const viewport = store.app?.graph.viewport ?? { x: 0, y: 0, zoom: 1 }
  store.addNode(type, {
    x: (-viewport.x + 420) / viewport.zoom,
    y: (-viewport.y + 220) / viewport.zoom,
  })
}
function addStartNode() {
  const start = store.visibleCatalog.find((node) => ['USER_INPUT', 'START', 'INPUT'].includes(node.type.toUpperCase().replace(/[ -]/g, '_')))
    ?? store.visibleCatalog[0]
  if (start) addNode(start.type)
}
async function createApp() {
  await store.createNew(newAppType.value)
  runStore.reset()
}
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const yaml = await file.text()
  const success = await store.importYaml(yaml, file.name)
  if (success) runStore.reset()
}
async function exportYaml() {
  if (!store.app || exporting.value) return
  exporting.value = true
  store.operationError = ''
  try {
    await store.saveNow()
    if (store.saveState === 'conflict' || store.saveState === 'error') return
    const yaml = await workflowApi.exportDify(store.app.id)
    const url = URL.createObjectURL(new Blob([yaml], { type: 'text/yaml;charset=utf-8' }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${store.app.name.replace(/[\\/:*?"<>|]/g, '-') || 'weave-workflow'}.yaml`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    store.operationError = `导出失败：${error instanceof Error ? error.message : String(error)}`
  } finally {
    exporting.value = false
  }
}
function editableTarget(event: KeyboardEvent): boolean {
  return event.target instanceof HTMLElement && Boolean(event.target.closest('input, textarea, select, [contenteditable="true"], .cm-editor'))
}
function onKeydown(event: KeyboardEvent) {
  const modifier = event.metaKey || event.ctrlKey
  if (modifier && event.key.toLowerCase() === 's') {
    event.preventDefault()
    void store.saveNow()
    return
  }
  if (modifier && event.key === 'Enter') {
    event.preventDefault()
    runStore.openPanel()
    return
  }
  if (editableTarget(event)) return
  if (modifier && event.key.toLowerCase() === 'z') {
    event.preventDefault()
    if (event.shiftKey) store.redo()
    else store.undo()
  } else if (modifier && event.key.toLowerCase() === 'y') {
    event.preventDefault()
    store.redo()
  } else if (modifier && event.key.toLowerCase() === 'c') {
    event.preventDefault()
    store.copySelection()
  } else if (modifier && event.key.toLowerCase() === 'v') {
    event.preventDefault()
    store.paste()
  } else if (modifier && event.key.toLowerCase() === 'd') {
    event.preventDefault()
    store.copySelection()
    store.paste()
  } else if ((event.key === 'Delete' || event.key === 'Backspace') && (store.selectedNodeIds.length || store.selectedEdgeIds.length)) {
    event.preventDefault()
    if (store.selectedNodeIds.length) store.removeNodes(store.selectedNodeIds)
    else store.removeEdges(store.selectedEdgeIds)
  } else if (event.key === 'Escape') {
    store.setSelection([])
  }
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (store.saveState === 'dirty' || store.saveState === 'saving' || store.saveState === 'error') {
    event.preventDefault()
  }
}

onMounted(() => {
  void store.bootstrap()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('beforeunload', beforeUnload)
  runStore.stopTracking()
})
</script>

<template>
  <div class="weave-app">
    <header class="app-header">
      <div class="brand-block">
        <div class="brand-mark"><Workflow :size="20" stroke-width="1.7" /></div>
        <div class="brand-name"><strong>WEAVE</strong><span>流织</span></div>
        <span class="brand-divider" />
        <span class="product-label">工作流工作台</span>
      </div>

      <div class="document-block">
        <div class="app-switcher">
          <select v-model="selectedAppId" aria-label="打开应用" :disabled="store.loading || store.apps.length === 0">
            <option v-if="!store.app" value="">选择应用</option>
            <option v-for="app in store.apps" :key="app.id" :value="app.id">{{ app.name }}</option>
          </select>
          <ChevronDown :size="13" />
        </div>
        <span v-if="store.app" class="app-type-label">{{ store.app.appType === 'WORKFLOW' ? 'WORKFLOW' : 'CHATFLOW' }}</span>
      </div>

      <div class="header-actions">
        <button
          class="save-status"
          :class="`save-${store.saveState}`"
          data-testid="save-status"
          :title="store.saveError || '单击立即保存'"
          @click="store.saveNow()"
        >
          <LoaderCircle v-if="store.saveState === 'saving'" class="spin" :size="13" />
          <CloudOff v-else-if="store.saveState === 'error' || store.saveState === 'conflict'" :size="13" />
          <Check v-else :size="13" />
          <span>{{ store.saveLabel }}</span>
        </button>
        <span class="header-divider" />
        <label class="header-button" data-testid="import-yaml" :class="{ disabled: store.importing }">
          <LoaderCircle v-if="store.importing" class="spin" :size="14" />
          <Upload v-else :size="14" /><span>导入 YAML</span>
          <input ref="fileInput" type="file" accept=".yaml,.yml,text/yaml" :disabled="store.importing" @change="importFile" />
        </label>
        <button class="header-button" data-testid="export-yaml" :disabled="!store.app || exporting" @click="exportYaml">
          <Download :size="14" /><span>{{ exporting ? '导出中' : '导出 YAML' }}</span>
        </button>
        <span class="header-divider" />
        <div class="new-app-control">
          <select v-model="newAppType" data-testid="app-type-select" aria-label="新应用类型">
            <option value="WORKFLOW">Workflow</option>
            <option value="CHATFLOW">Chatflow</option>
          </select>
          <button class="new-app-button" data-testid="new-app-button" :disabled="store.loading" @click="createApp">
            <FilePlus2 :size="14" /><span>新建</span>
          </button>
        </div>
      </div>
    </header>

    <div v-if="store.saveState === 'conflict'" class="global-banner conflict-banner">
      <AlertCircle :size="15" /><span>{{ store.saveError }}</span>
      <button @click="store.reloadConflict()"><RefreshCw :size="13" />重新加载最新草稿</button>
    </div>
    <div v-else-if="store.saveState === 'error'" class="global-banner error-banner">
      <AlertCircle :size="15" /><span>自动保存失败：{{ store.saveError }}</span>
      <button @click="store.saveNow()"><Save :size="13" />重试保存</button>
    </div>
    <div v-if="store.operationError && store.app" class="global-banner error-banner">
      <AlertCircle :size="15" /><span>{{ store.operationError }}</span>
      <button class="icon-button" @click="store.operationError = ''"><X :size="14" /></button>
    </div>
    <div v-if="store.importError" class="global-banner error-banner">
      <AlertCircle :size="15" /><span>YAML 导入失败：{{ store.importError }}</span>
      <button class="icon-button" @click="store.importError = ''"><X :size="14" /></button>
    </div>

    <main v-if="store.app" class="workbench">
      <NodePalette @add="addNode" />
      <WorkflowCanvas @add-at-center="addStartNode" />
      <NodeInspector />
      <RunPanel />
    </main>

    <div v-if="store.loading || (!store.app && !store.operationError)" class="loading-screen">
      <div class="loading-mark"><Workflow :size="28" /></div>
      <LoaderCircle class="spin" :size="19" />
      <span>正在连接工作流服务…</span>
    </div>
    <div v-else-if="!store.app" class="fatal-screen">
      <CloudOff :size="30" />
      <h2>暂时无法打开工作台</h2>
      <p>{{ store.operationError }}</p>
      <button class="primary-button" @click="store.bootstrap()"><RefreshCw :size="14" />重新连接</button>
      <span>请确认后端服务已在 localhost:8080 启动</span>
    </div>
  </div>
</template>
