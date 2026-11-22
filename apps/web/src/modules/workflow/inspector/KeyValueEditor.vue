<script setup lang="ts">
import { computed } from 'vue'
import { Plus, Trash2 } from 'lucide-vue-next'
import { cloneJson } from '../../../shared/utils/graph'

interface Row {
  key: string
  value: unknown
  original?: Record<string, unknown>
}

const props = withDefaults(defineProps<{
  modelValue: unknown
  shape?: 'array' | 'object'
  keyLabel?: string
  valueLabel?: string
  declaration?: boolean
}>(), { shape: 'array', keyLabel: '键', valueLabel: '值', declaration: false })
const emit = defineEmits<{ 'update:modelValue': [value: unknown] }>()

const rows = computed<Row[]>(() => {
  if (Array.isArray(props.modelValue)) {
    return props.modelValue.map((entry) => {
      if (typeof entry !== 'object' || entry === null) return { key: '', value: entry }
      const original = entry as Record<string, unknown>
      return {
        key: String(original.key ?? original.name ?? ''),
        value: original.value ?? '',
        original,
      }
    })
  }
  if (typeof props.modelValue === 'object' && props.modelValue !== null) {
    return Object.entries(props.modelValue).map(([key, value]) => ({ key, value }))
  }
  return []
})

function updateRows(next: Row[]) {
  const objectShape = props.shape === 'object' || (!Array.isArray(props.modelValue) && typeof props.modelValue === 'object' && props.modelValue !== null)
  if (objectShape) {
    emit('update:modelValue', Object.fromEntries(next.filter((row) => row.key).map((row) => [row.key, row.value])))
    return
  }
  emit('update:modelValue', next.map((row) => {
    const original = cloneJson(row.original ?? {})
    const keyName = 'name' in original && !('key' in original) ? 'name' : 'key'
    return { ...original, [keyName]: row.key, value: row.value }
  }))
}
function update(index: number, patch: Partial<Row>) {
  const next = rows.value.map((row) => ({ ...row, original: row.original ? cloneJson(row.original) : undefined }))
  next[index] = { ...next[index], ...patch }
  updateRows(next)
}
function isTypeDeclaration(row: Row): boolean {
  return props.declaration || (typeof row.value === 'object' && row.value !== null && 'type' in row.value)
}
function declarationType(row: Row): string {
  return typeof row.value === 'object' && row.value !== null ? String((row.value as Record<string, unknown>).type ?? 'string') : 'string'
}
function updateDeclaration(index: number, event: Event) {
  const row = rows.value[index]
  const current = typeof row.value === 'object' && row.value !== null ? row.value as Record<string, unknown> : {}
  update(index, { value: { ...current, type: (event.target as HTMLSelectElement).value } })
}
</script>

<template>
  <div class="key-value-editor">
    <div v-if="rows.length" class="key-value-head"><span>{{ keyLabel }}</span><span>{{ valueLabel }}</span><span /></div>
    <div v-for="(row, index) in rows" :key="index" class="key-value-row">
      <input :value="row.key" placeholder="名称" @input="update(index, { key: ($event.target as HTMLInputElement).value })" />
      <select v-if="isTypeDeclaration(row)" :value="declarationType(row)" @change="updateDeclaration(index, $event)">
        <option value="string">string</option><option value="number">number</option>
        <option value="boolean">boolean</option><option value="object">object</option><option value="array">array</option>
      </select>
      <input v-else :value="String(row.value ?? '')" placeholder="值" @input="update(index, { value: ($event.target as HTMLInputElement).value })" />
      <button class="icon-button" title="删除" @click="updateRows(rows.filter((_, itemIndex) => itemIndex !== index))"><Trash2 :size="13" /></button>
    </div>
    <button class="add-row-button" @click="updateRows([...rows, { key: '', value: declaration ? { type: 'string' } : '' }])"><Plus :size="13" />添加键值</button>
  </div>
</template>
