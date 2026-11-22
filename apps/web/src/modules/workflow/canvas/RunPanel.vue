<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AlertCircle, ChevronDown, ChevronUp, Circle, Eraser, Play, Radio } from 'lucide-vue-next'
import { useWorkflowStore } from '../store/workflow'
import { useRunStore } from '../store/run'
import { extractInputFields, extractRunInputs, initialRunValues, normalizeRunValues } from '../store/runInputs'
import type { RunInputField } from '../store/runInputs'

const workflow = useWorkflowStore()
const run = useRunStore()
const collapsed = ref(!run.panelOpen)
const values = ref<Record<string, unknown>>({})
const inputErrors = ref<Record<string, string>>({})
const fields = computed(() => workflow.app ? extractRunInputs(workflow.app.graph) : [])
const waitingNode = computed(() => run.nodeRuns.find((nodeRun) => nodeRun.status === 'WAITING'))
const waitingKey = computed(() => waitingNode.value ? `${run.currentRun?.id}:${waitingNode.value.nodeId}` : '')
const waitingRequest = computed(() => {
  const graphNode = workflow.app?.graph.nodes.find((node) => node.id === waitingNode.value?.nodeId)
  const raw = waitingNode.value?.inputs
  const request = typeof raw === 'object' && raw !== null && !Array.isArray(raw)
    ? raw as Record<string, unknown> : undefined
  return {
    prompt: String(request?.prompt ?? graphNode?.config.prompt ?? '请提供人工输入'),
    fields: request?.fields ?? graphNode?.config.fields ?? [],
  }
})
const humanFields = computed(() => extractInputFields(waitingRequest.value.fields))
const humanValues = ref<Record<string, unknown>>({})
const humanErrors = ref<Record<string, string>>({})

watch(waitingKey, () => {
  humanValues.value = initialRunValues(humanFields.value)
  humanErrors.value = {}
}, { immediate: true })
watch(fields, (next) => {
  values.value = { ...initialRunValues(next), ...values.value }
  for (const key of Object.keys(values.value)) {
    if (!next.some((field) => field.key === key)) delete values.value[key]
  }
}, { immediate: true, deep: true })
watch(() => workflow.app?.id, () => {
  values.value = initialRunValues(fields.value)
  inputErrors.value = {}
  run.reset()
})
watch(() => run.panelOpen, (open) => { collapsed.value = !open })
function togglePanel() {
  collapsed.value = !collapsed.value
  run.panelOpen = !collapsed.value
}

function textValue(field: RunInputField): string {
  const value = values.value[field.key]
  if (field.type === 'json' && typeof value !== 'string') return JSON.stringify(value ?? '', null, 2)
  return String(value ?? '')
}
function update(field: RunInputField, event: Event) {
  values.value[field.key] = (event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement).value
  delete inputErrors.value[field.key]
}
function updateBoolean(field: RunInputField, event: Event) {
  values.value[field.key] = (event.target as HTMLInputElement).checked
}
function humanTextValue(field: RunInputField): string {
  const value = humanValues.value[field.key]
  if (field.type === 'json' && typeof value !== 'string') return JSON.stringify(value ?? '', null, 2)
  return String(value ?? '')
}
function updateHuman(field: RunInputField, event: Event) {
  humanValues.value[field.key] = (event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement).value
  delete humanErrors.value[field.key]
  run.resumeError = ''
}
function updateHumanBoolean(field: RunInputField, event: Event) {
  humanValues.value[field.key] = (event.target as HTMLInputElement).checked
  delete humanErrors.value[field.key]
  run.resumeError = ''
}
async function submitHuman() {
  const node = waitingNode.value
  if (!node) return
  const normalized = normalizeRunValues(humanFields.value, humanValues.value)
  humanErrors.value = normalized.errors
  if (Object.keys(normalized.errors).length) return
  await run.resume(node.nodeId, normalized.inputs)
}
async function start() {
  const normalized = normalizeRunValues(fields.value, values.value)
  inputErrors.value = normalized.errors
  if (Object.keys(normalized.errors).length) return
  collapsed.value = false
  await run.start(normalized.inputs)
}
function format(value: unknown): string {
  if (value === undefined || value === null) return '—'
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
}
function statusLabel(status: string | undefined): string {
  const labels: Record<string, string> = {
    PENDING: '等待中', RUNNING: '运行中', PAUSED: '等待人工输入', WAITING: '等待输入',
    SUCCEEDED: '成功', COMPLETED: '完成', FAILED: '失败', CANCELLED: '已取消', SKIPPED: '已跳过', STOPPED: '已停止',
  }
  return labels[status ?? ''] ?? status ?? '尚未运行'
}
function eventTime(timestamp: string | undefined): string {
  if (!timestamp) return ''
  const date = new Date(timestamp)
  return Number.isNaN(date.getTime()) ? timestamp : date.toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<template>
  <section class="run-panel" :class="{ collapsed }">
    <header class="run-panel-header">
      <button class="run-panel-toggle" @click="togglePanel">
        <component :is="collapsed ? ChevronUp : ChevronDown" :size="15" />
        <span>运行与事件</span>
        <span v-if="run.currentRun" class="run-status" :class="`status-${run.currentRun.status.toLowerCase()}`">
          <Circle :size="7" fill="currentColor" />{{ statusLabel(run.currentRun.status) }}
        </span>
      </button>
      <div class="run-panel-meta">
        <span v-if="run.running" class="stream-indicator"><Radio :size="12" />SSE + 轮询</span>
        <code v-if="run.currentRun">{{ run.currentRun.id }}</code>
        <button class="icon-button" title="清空运行面板" :disabled="run.running" @click="run.clear()"><Eraser :size="14" /></button>
      </div>
    </header>

    <div v-show="!collapsed" class="run-panel-content">
      <div class="run-input-pane" data-testid="run-inputs">
        <div class="run-section-title"><span>运行输入</span><span>{{ fields.length }} 项</span></div>
        <div class="run-input-scroll">
          <p v-if="fields.length === 0" class="no-input-message">User Input 未声明输入字段，将以空输入运行。</p>
          <div v-for="field in fields" :key="field.key" class="run-input-field">
            <label :for="`run-${field.key}`">{{ field.label }}<span v-if="field.required" class="required">*</span></label>
            <small v-if="field.description">{{ field.description }}</small>
            <label v-if="field.type === 'boolean'" class="run-checkbox">
              <input :id="`run-${field.key}`" type="checkbox" :checked="Boolean(values[field.key])" @change="updateBoolean(field, $event)" />
              <span>是</span>
            </label>
            <select v-else-if="field.type === 'select'" :id="`run-${field.key}`" :value="textValue(field)" @change="update(field, $event)">
              <option value="">请选择</option>
              <option v-for="option in field.options" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
            <textarea v-else-if="field.type === 'paragraph' || field.type === 'json'" :id="`run-${field.key}`" :class="{ mono: field.type === 'json' }" :value="textValue(field)" :rows="field.type === 'json' ? 4 : 3" @input="update(field, $event)" />
            <input v-else :id="`run-${field.key}`" :type="field.type === 'number' ? 'number' : 'text'" :value="textValue(field)" @input="update(field, $event)" />
            <span v-if="inputErrors[field.key]" class="field-error">{{ inputErrors[field.key] }}</span>
          </div>
        </div>
        <div v-if="run.error" class="run-error"><AlertCircle :size="14" /><span>{{ run.error }}</span></div>
        <button class="primary-button execute-button" :disabled="run.running || !workflow.app" @click="start">
          <Play :size="13" fill="currentColor" />{{ run.running ? (run.currentRun?.status === 'PAUSED' ? '等待人工输入…' : '正在执行…') : '执行当前草稿' }}
        </button>
      </div>

      <div class="run-events-pane" data-testid="run-events">
        <div class="run-section-title"><span>节点执行</span><span>{{ run.nodeRuns.length }} 条</span></div>
        <div class="event-scroll">
          <div v-if="run.streamWarning" class="stream-warning">{{ run.streamWarning }}</div>
          <div v-if="!run.events.length && !run.nodeRuns.length" class="event-empty">
            <Radio :size="21" /><span>执行后，节点状态与事件会实时显示在这里</span>
          </div>
          <details v-for="nodeRun in run.nodeRuns" :key="nodeRun.id ?? nodeRun.nodeId" class="node-run-row" :class="`status-${nodeRun.status.toLowerCase()}`">
            <summary>
              <span class="event-status-dot" />
              <strong>{{ nodeRun.title ?? workflow.app?.graph.nodes.find((node) => node.id === nodeRun.nodeId)?.title ?? nodeRun.nodeId }}</strong>
              <span class="event-type">{{ statusLabel(nodeRun.status) }}</span>
              <span v-if="nodeRun.durationMs !== undefined" class="event-duration mono">{{ nodeRun.durationMs }} ms</span>
            </summary>
            <div v-if="nodeRun.error" class="event-error">{{ nodeRun.error }}</div>
            <div class="event-io"><div><span>输入</span><pre>{{ format(nodeRun.inputs) }}</pre></div><div><span>输出</span><pre>{{ format(nodeRun.outputs) }}</pre></div></div>
          </details>
          <details v-for="(event, index) in run.events" :key="event.id ?? event.sequence ?? index" class="stream-event-row">
            <summary>
              <span class="event-sequence mono">{{ event.sequence ?? index + 1 }}</span>
              <span class="event-type">{{ event.type }}</span>
              <span v-if="event.nodeId" class="event-node">{{ workflow.app?.graph.nodes.find((node) => node.id === event.nodeId)?.title ?? event.nodeId }}</span>
              <span v-if="event.message">{{ event.message }}</span>
              <time>{{ eventTime(event.timestamp) }}</time>
            </summary>
            <div v-if="event.error" class="event-error">{{ event.error }}</div>
            <pre v-if="event.data !== undefined || event.payload !== undefined">{{ format(event.data ?? event.payload) }}</pre>
          </details>
        </div>
      </div>

      <div class="run-result-pane" data-testid="run-result">
        <div class="run-section-title"><span>{{ waitingNode ? '人工输入' : '结果' }}</span><span>{{ waitingNode ? 'HUMAN INPUT' : 'OUTPUT / ANSWER' }}</span></div>
        <div class="result-scroll">
          <section v-if="waitingNode" class="human-input-form" data-testid="human-input-form">
            <strong>{{ workflow.app?.graph.nodes.find((node) => node.id === waitingNode?.nodeId)?.title ?? waitingNode.nodeId }}</strong>
            <p class="human-input-prompt">{{ waitingRequest.prompt }}</p>
            <div v-for="field in humanFields" :key="field.key" class="run-input-field">
              <label :for="`human-${waitingNode.nodeId}-${field.key}`">{{ field.label }}<span v-if="field.required" class="required">*</span></label>
              <small v-if="field.description">{{ field.description }}</small>
              <label v-if="field.type === 'boolean'" class="run-checkbox">
                <input :id="`human-${waitingNode.nodeId}-${field.key}`" type="checkbox" :checked="Boolean(humanValues[field.key])" @change="updateHumanBoolean(field, $event)" />
                <span>是</span>
              </label>
              <select v-else-if="field.type === 'select'" :id="`human-${waitingNode.nodeId}-${field.key}`" :value="humanTextValue(field)" @change="updateHuman(field, $event)">
                <option value="">请选择</option>
                <option v-for="option in field.options" :key="option.value" :value="option.value">{{ option.label }}</option>
              </select>
              <textarea v-else-if="field.type === 'paragraph' || field.type === 'json'" :id="`human-${waitingNode.nodeId}-${field.key}`" :class="{ mono: field.type === 'json' }" :value="humanTextValue(field)" :rows="field.type === 'json' ? 4 : 3" @input="updateHuman(field, $event)" />
              <input v-else :id="`human-${waitingNode.nodeId}-${field.key}`" :type="field.type === 'number' ? 'number' : 'text'" :value="humanTextValue(field)" @input="updateHuman(field, $event)" />
              <span v-if="humanErrors[field.key]" class="field-error">{{ humanErrors[field.key] }}</span>
            </div>
            <div v-if="run.resumeError" class="human-resume-error">{{ run.resumeError }}</div>
            <button class="primary-button human-submit-button" data-testid="human-input-submit" :disabled="run.submittingNodeId === waitingNode.nodeId" @click="submitHuman">
              <Play :size="12" fill="currentColor" />{{ run.submittingNodeId === waitingNode.nodeId ? '正在提交…' : '提交并继续' }}
            </button>
          </section>
          <div v-if="!run.currentRun" class="result-empty">尚无运行结果</div>
          <template v-else>
            <div class="result-status-line"><span>状态</span><strong>{{ statusLabel(run.currentRun.status) }}</strong></div>
            <section v-if="run.currentRun.answer" class="answer-result"><span>ANSWER</span><p>{{ run.currentRun.answer }}</p></section>
            <section v-if="run.currentRun.outputs !== undefined" class="output-result"><span>OUTPUT</span><pre>{{ format(run.currentRun.outputs) }}</pre></section>
            <div v-if="run.currentRun.error" class="event-error">{{ run.currentRun.error }}</div>
          </template>
        </div>
      </div>
    </div>
  </section>
</template>
