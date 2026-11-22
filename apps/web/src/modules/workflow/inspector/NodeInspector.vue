<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { AlertTriangle, Copy, FlaskConical, Info, LoaderCircle, MousePointer2, Search, Settings2, Trash2, X } from 'lucide-vue-next'
import type { JsonSchema, KnowledgeHit, ValidationIssue } from '../api/types'
import { knowledgeApi } from '../api/client'
import { buildVariableOptions } from '../nodes/variables'
import DynamicField from './DynamicField.vue'
import { useWorkflowStore } from '../store/workflow'

const store = useWorkflowStore()
const inspector = ref<HTMLElement>()
const selected = computed(() => store.selectedNode)
const definition = computed(() => store.selectedDefinition)
const isRetrieval = computed(() => selected.value?.type === 'knowledge-retrieval')
const datasetIds = computed<string[]>(() => {
  const value = selected.value?.config.datasetIds
  return Array.isArray(value) ? value.map(String) : []
})
const testQuery = ref('')
const testHits = ref<KnowledgeHit[]>([])
const testing = ref(false)
const testError = ref('')
const testRan = ref(false)

watch(() => [selected.value?.id, selected.value?.type], () => {
  testQuery.value = String(selected.value?.config.query ?? '')
  testHits.value = []
  testError.value = ''
  testRan.value = false
})

async function runRetrievalTest() {
  const node = selected.value
  if (!node || testing.value) return
  if (datasetIds.value.length === 0) {
    testError.value = '请先选择至少一个知识库。'
    return
  }
  const query = testQuery.value.trim()
  if (!query) {
    testError.value = '请输入检索内容。'
    return
  }
  testing.value = true
  testError.value = ''
  try {
    testHits.value = await knowledgeApi.search(datasetIds.value, query,
      Number(node.config.topK ?? 4), Number(node.config.scoreThreshold ?? 0))
    testRan.value = true
  } catch (cause) {
    testError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    testing.value = false
  }
}

const variables = computed(() => store.app ? buildVariableOptions(store.app.graph, store.catalog, selected.value?.id) : [])
const schema = computed<JsonSchema>(() => definition.value?.configSchema ?? {
  type: 'object',
  title: '节点配置',
  description: '该节点未发布 Schema，可直接编辑配置 JSON。',
})
const selectedIssues = computed(() => selected.value
  ? store.issues.filter((issue) => issue.nodeId === selected.value!.id)
  : [])
const globalIssues = computed(() => store.issues.filter((issue) => !issue.nodeId))
const errors = computed<Record<string, string[]>>(() => {
  const result: Record<string, string[]> = {}
  for (const issue of selectedIssues.value) {
    const raw = issue.field ?? issue.path?.match(/(?:^|\.)config\.(.+)$/)?.[1] ?? issue.path ?? ''
    const path = raw.replace(/^config\./, '').replace(/\[(\d+)\]/g, '.$1').replace(/\./g, '.').replace(/^\./, '')
    result[path] = [...(result[path] ?? []), issue.message]
  }
  return result
})

function updateConfig(path: Array<string | number>, value: unknown) {
  if (selected.value) store.updateNodeConfig(selected.value.id, path, value)
}
function locate(issue: ValidationIssue) {
  store.locateIssue(issue)
}
function duplicate() {
  store.copySelection()
  store.paste()
}

const SCOPE_FORBIDDEN = ['user-input', 'schedule-trigger', 'plugin-trigger', 'webhook-trigger', 'output', 'answer', 'iteration', 'loop']
const scopeChangeable = computed(() => Boolean(selected.value && !SCOPE_FORBIDDEN.includes(selected.value.type)))
const scopeOptions = computed(() => {
  if (!store.app) return []
  const types = selected.value?.type === 'exit-loop' ? ['loop'] : ['iteration', 'loop']
  return store.app.graph.nodes.filter((node) => node.id !== selected.value?.id
    && !node.parentId && types.includes(node.type))
})
function setScope(event: Event) {
  if (!selected.value) return
  const value = (event.target as HTMLSelectElement).value
  store.setNodeParent(selected.value.id, value || undefined)
}

watch(() => store.focusRequest, async (request) => {
  if (!request) return
  await nextTick()
  const fields = inspector.value?.querySelectorAll<HTMLElement>('[data-field-path]')
  const normalized = request.field.replace(/^config\./, '').replace(/\[(\d+)\]/g, '.$1')
  const target = [...(fields ?? [])].find((field) => field.dataset.fieldPath === normalized)
    ?? (selected.value ? inspector.value?.querySelector<HTMLElement>('.inspector-form') : undefined)
  target?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  if (target?.dataset.fieldPath) {
    target.classList.add('field-highlight')
    target.querySelector<HTMLElement>('input, textarea, select, .cm-content')?.focus()
    setTimeout(() => target.classList.remove('field-highlight'), 1400)
  }
})
</script>

<template>
  <aside ref="inspector" class="inspector" :class="selected ? 'has-selection' : 'no-selection'" data-testid="inspector">
    <template v-if="selected">
      <header class="inspector-heading">
        <div>
          <span class="eyebrow">CONFIGURATION</span>
          <h2>节点配置</h2>
        </div>
        <button class="icon-button" title="取消选择" @click="store.setSelection([])"><X :size="16" /></button>
      </header>
      <div class="inspector-scroll">
        <section class="node-identity">
          <div class="identity-topline">
            <span class="node-type-badge">{{ definition?.category ?? '未知节点' }}</span>
            <span v-if="definition && !definition.executable" class="unsupported-badge">不可执行</span>
          </div>
          <label class="field-label" for="node-title">节点名称</label>
          <input id="node-title" class="node-title-input" :value="selected.title" @input="store.updateNodeTitle(selected!.id, ($event.target as HTMLInputElement).value)" />
          <div class="identity-meta"><span>{{ selected.type }}</span><code :title="selected.id">{{ selected.id }}</code></div>
          <div v-if="scopeChangeable" class="scope-field">
            <label class="field-label" for="node-scope">所属作用域</label>
            <select id="node-scope" class="node-title-input" :value="selected.parentId ?? ''" @change="setScope">
              <option value="">顶层（无作用域）</option>
              <option v-for="option in scopeOptions" :key="option.id" :value="option.id">{{ option.title }}（{{ option.type }}）</option>
            </select>
            <p class="scope-hint">Iteration 内注入 item、index；Loop 内注入 index。变更作用域会清理跨作用域连线。</p>
          </div>
          <div v-if="definition && !definition.executable" class="support-message">
            <Info :size="14" />
            <span>{{ definition.supportMessage || '此节点当前仅支持编排与保存，执行时会明确报告不支持。' }}</span>
          </div>
        </section>

        <section v-if="globalIssues.length || selectedIssues.length" class="inspector-issues">
          <div v-for="(issue, index) in [...globalIssues, ...selectedIssues]" :key="index">
            <AlertTriangle :size="13" /><span>{{ issue.message }}</span>
          </div>
        </section>

        <section class="inspector-form">
          <div class="section-title"><Settings2 :size="14" /><span>参数</span><span class="schema-badge">SCHEMA</span></div>
          <DynamicField
            :model-value="selected.config"
            :schema="schema"
            :ui-schema="definition?.uiSchema ?? {}"
            :path="[]"
            :variables="variables"
            :errors="errors"
            :root-config="selected.config"
            @change="updateConfig"
          />
        </section>

        <section v-if="isRetrieval" class="retrieval-test" data-testid="retrieval-test">
          <div class="section-title"><FlaskConical :size="14" /><span>测试检索</span></div>
          <p class="test-hint">按当前知识库、条数与相似度门槛实际检索一次，用于确认命中效果。</p>
          <div class="test-controls">
            <input v-model="testQuery" data-testid="retrieval-test-query" placeholder="输入检索内容"
                   @keyup.enter="runRetrievalTest" />
            <button class="secondary-button" :disabled="testing" @click="runRetrievalTest">
              <LoaderCircle v-if="testing" class="spin" :size="13" /><Search v-else :size="13" /><span>测试</span>
            </button>
          </div>
          <p v-if="testError" class="test-error">{{ testError }}</p>
          <ul v-else-if="testHits.length" class="test-hits">
            <li v-for="hit in testHits" :key="`${hit.documentId}-${hit.chunkIndex}`">
              <header>
                <strong>{{ hit.documentName || '未命名文档' }}</strong>
                <span class="test-score">{{ hit.score.toFixed(3) }}</span>
              </header>
              <p>{{ hit.content }}</p>
            </li>
          </ul>
          <p v-else-if="testRan" class="test-hint">没有命中片段，可降低最低相似度后重试。</p>
        </section>
      </div>
      <footer class="inspector-footer">
        <button class="secondary-button" @click="duplicate"><Copy :size="14" />创建副本</button>
        <button class="danger-button" @click="store.removeNodes([selected!.id])"><Trash2 :size="14" />删除节点</button>
      </footer>
    </template>

    <template v-else>
      <header class="inspector-heading">
        <div><span class="eyebrow">INSPECTOR</span><h2>检查器</h2></div>
      </header>
      <div class="inspector-scroll">
        <div class="inspector-empty">
          <MousePointer2 :size="24" />
          <h3>选择一个节点</h3>
          <p>在画布中单击节点以编辑参数、引用上游变量并查看校验问题。</p>
        </div>
        <section v-if="store.app" class="document-summary">
          <h3>当前文档</h3>
          <dl>
            <div><dt>应用类型</dt><dd>{{ store.app.appType === 'WORKFLOW' ? 'Workflow' : 'Chatflow' }}</dd></div>
            <div><dt>节点</dt><dd>{{ store.app.graph.nodes.length }}</dd></div>
            <div><dt>连接</dt><dd>{{ store.app.graph.edges.length }}</dd></div>
            <div><dt>草稿版本</dt><dd class="mono">v{{ store.app.version }}</dd></div>
          </dl>
        </section>
        <section v-if="store.issues.length" class="all-issues">
          <h3>校验问题</h3>
          <button v-for="(issue, index) in store.issues" :key="index" @click="locate(issue)">
            <AlertTriangle :size="13" /><span>{{ issue.message }}</span>
          </button>
        </section>
        <section class="shortcut-summary">
          <h3>快捷操作</h3>
          <div><span>复制 / 粘贴</span><kbd>⌘ C / ⌘ V</kbd></div>
          <div><span>撤销 / 重做</span><kbd>⌘ Z / ⇧⌘ Z</kbd></div>
          <div><span>框选节点</span><kbd>Shift + 拖拽</kbd></div>
          <div><span>删除所选</span><kbd>Delete</kbd></div>
          <div><span>保存草稿</span><kbd>⌘ S</kbd></div>
        </section>
      </div>
    </template>
  </aside>
</template>
