<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { Codemirror } from 'vue-codemirror'
import { EditorView } from '@codemirror/view'
import type { Extension } from '@codemirror/state'
import { Plus } from 'lucide-vue-next'
import type { VariableOption } from '../nodes/variables'

const props = withDefaults(defineProps<{
  modelValue: string
  language?: 'javascript' | 'python' | 'prompt' | 'text'
  variables?: VariableOption[]
  height?: string
}>(), { language: 'text', variables: () => [], height: '220px' })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const view = shallowRef<EditorView>()
const syntax = shallowRef<Extension>([])
let syntaxRequest = 0

// 语法解析器按语言懒加载，避免把两种语言的 parser 都打进同一个块
watch(() => props.language, async (language) => {
  const request = ++syntaxRequest
  const loaded = language === 'javascript'
    ? (await import('@codemirror/lang-javascript')).javascript()
    : language === 'python'
      ? (await import('@codemirror/lang-python')).python()
      : []
  if (request === syntaxRequest) syntax.value = loaded
}, { immediate: true })

const extensions = computed(() => [syntax.value, EditorView.lineWrapping])
const value = computed({
  get: () => props.modelValue,
  set: (next: string) => emit('update:modelValue', next),
})
function ready(payload: { view: EditorView }) {
  view.value = payload.view
}
function insertVariable(event: Event) {
  const select = event.target as HTMLSelectElement
  const option = props.variables.find((item) => item.id === select.value)
  select.value = ''
  if (!option || !view.value) return
  const editor = view.value
  const cursor = editor.state.selection.main.head
  const text = `{{${option.nodeId}.${option.name}}}`
  editor.dispatch({
    changes: { from: cursor, insert: text },
    selection: { anchor: cursor + text.length },
  })
  editor.focus()
}
</script>

<template>
  <div class="code-field">
    <div class="code-toolbar">
      <span>{{ language === 'prompt' ? 'PROMPT' : language.toUpperCase() }}</span>
      <label v-if="language === 'prompt' && variables.length" class="insert-variable">
        <Plus :size="12" />
        <select value="" @change="insertVariable">
          <option value="">插入变量</option>
          <option v-for="option in variables" :key="option.id" :value="option.id">{{ option.label }}</option>
        </select>
      </label>
    </div>
    <Codemirror
      v-model="value"
      :extensions="extensions"
      :style="{ height }"
      :indent-with-tab="true"
      :tab-size="2"
      @ready="ready"
    />
  </div>
</template>
