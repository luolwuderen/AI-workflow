<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { Plus, Trash2 } from 'lucide-vue-next'
import type { JsonSchema, UISchema } from '../api/types'
import type { VariableOption } from '../nodes/variables'
import VariableSelector from './VariableSelector.vue'
import KeyValueEditor from './KeyValueEditor.vue'
import CasesEditor from './CasesEditor.vue'
import { matchesVisibility } from './conditions'
import { cloneJson } from '../../../shared/utils/graph'

// CodeMirror 体积较大，仅在渲染代码/提示词字段时按需加载
const CodeEditor = defineAsyncComponent(() => import('./CodeEditor.vue'))

defineOptions({ name: 'DynamicField' })
const props = withDefaults(defineProps<{
  modelValue: unknown
  schema: JsonSchema
  uiSchema?: UISchema
  path?: Array<string | number>
  variables?: VariableOption[]
  errors?: Record<string, string[]>
  rootConfig?: Record<string, unknown>
  required?: boolean
}>(), {
  uiSchema: () => ({}),
  path: () => [],
  variables: () => [],
  errors: () => ({}),
  rootConfig: () => ({}),
  required: false,
})
const emit = defineEmits<{ change: [path: Array<string | number>, value: unknown] }>()

const fieldKey = computed(() => String(props.path[props.path.length - 1] ?? ''))
const pathKey = computed(() => props.path.join('.'))
const ownErrors = computed(() => props.errors[pathKey.value] ?? [])
const schemaType = computed(() => {
  if (props.schema.properties) return 'object'
  if (props.schema.items) return 'array'
  return Array.isArray(props.schema.type) ? props.schema.type[0] : props.schema.type ?? 'string'
})
const widget = computed(() => String(props.uiSchema['ui:widget'] ?? props.uiSchema.widget ?? props.schema['x-widget'] ?? ''))
const uiOptions = computed(() => {
  const options = props.uiSchema['ui:options']
  return typeof options === 'object' && options !== null ? options as Record<string, unknown> : {}
})
const visible = computed(() => matchesVisibility(props.rootConfig,
  props.uiSchema['ui:visibleWhen'] ?? props.uiSchema['ui:showWhen'] ?? props.schema.visibleWhen ?? props.schema.showWhen))
const objectValue = computed<Record<string, unknown>>(() => typeof props.modelValue === 'object' && props.modelValue !== null && !Array.isArray(props.modelValue)
  ? props.modelValue as Record<string, unknown> : {})
const properties = computed(() => props.schema.properties ?? {})
const orderedKeys = computed(() => {
  const keys = Object.keys(properties.value)
  const order = props.uiSchema['ui:order']
  if (!Array.isArray(order)) return keys
  const ranks = new Map(order.map((key, index) => [String(key), index]))
  return keys.sort((a, b) => (ranks.get(a) ?? 999) - (ranks.get(b) ?? 999))
})
const arrayValue = computed<unknown[]>(() => Array.isArray(props.modelValue) ? props.modelValue : [])
const enumValues = computed(() => props.schema.enum ?? [])
const kind = computed(() => {
  const key = fieldKey.value
  if ((widget.value === 'cases' || key === 'cases') && schemaType.value === 'array') return 'cases'
  if (widget.value === 'key-value' || widget.value === 'keyValue') return 'key-value'
  if (schemaType.value === 'object' && !props.schema.properties && props.schema.additionalProperties) return 'key-value'
  if (schemaType.value === 'object' && props.schema.properties) return 'object'
  if (schemaType.value === 'array' && props.schema.items?.properties) return 'array-objects'
  if (widget.value === 'variable' || widget.value === 'variable-selector' || props.schema['x-variable-selector']
    || key === 'selector' || key === 'inputSelector' || uiOptions.value.variable === true) return 'variable'
  if (enumValues.value.length) return 'select'
  if (widget.value === 'code' || props.schema.format === 'code' || ['code', 'sourceCode'].includes(key)) return 'code'
  if (widget.value === 'prompt' || ['prompt', 'systemPrompt', 'instructions', 'answer', 'content', 'template'].includes(key)) return 'prompt'
  if (widget.value === 'textarea' || props.schema.format === 'textarea') return 'textarea'
  if (schemaType.value === 'boolean') return 'boolean'
  if (schemaType.value === 'number' || schemaType.value === 'integer') return 'number'
  if (schemaType.value === 'string' && !widget.value) return 'text'
  if (widget.value === 'text' || widget.value === 'input') return 'text'
  return 'json'
})
const label = computed(() => props.schema.title ?? fieldKey.value.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[_-]/g, ' '))
const codeLanguage = computed<'javascript' | 'python'>(() => {
  const configured = String(uiOptions.value.language ?? props.rootConfig.language ?? '').toLowerCase()
  return configured.includes('python') ? 'python' : 'javascript'
})
const editorHeight = computed(() => String(uiOptions.value.height ?? (kind.value === 'prompt' ? '240px' : '220px')))
const variableFormat = computed(() => typeof uiOptions.value.variableFormat === 'string' ? uiOptions.value.variableFormat : undefined)

function fieldUi(key: string): UISchema {
  const value = props.uiSchema[key]
  return typeof value === 'object' && value !== null ? value as UISchema : {}
}
function forwardChange(path: Array<string | number>, value: unknown) {
  emit('change', path, value)
}
function update(value: unknown) {
  emit('change', props.path, value)
}
function onText(event: Event) {
  update((event.target as HTMLInputElement | HTMLTextAreaElement).value)
}
function onNumber(event: Event) {
  const value = (event.target as HTMLInputElement).value
  update(value === '' ? undefined : Number(value))
}
function onBoolean(event: Event) {
  update((event.target as HTMLInputElement).checked)
}
function onEnum(event: Event) {
  const index = Number((event.target as HTMLSelectElement).value)
  update(Number.isInteger(index) ? enumValues.value[index] : undefined)
}
function defaultFor(schema: JsonSchema | undefined): unknown {
  if (!schema) return ''
  if (schema.default !== undefined) return cloneJson(schema.default)
  if (schema.properties) {
    return Object.fromEntries(Object.entries(schema.properties).map(([key, child]) => [key, defaultFor(child)]))
  }
  if (schema.type === 'array' || schema.items) return []
  if (schema.type === 'boolean') return false
  if (schema.type === 'number' || schema.type === 'integer') return 0
  if (schema.type === 'object') return {}
  return ''
}
function addArrayItem() {
  update([...arrayValue.value, defaultFor(props.schema.items)])
}
function removeArrayItem(index: number) {
  update(arrayValue.value.filter((_, itemIndex) => itemIndex !== index))
}
const jsonText = ref('')
const jsonError = ref('')
function stringify(value: unknown): string {
  return value === undefined ? '' : JSON.stringify(value, null, 2)
}
jsonText.value = stringify(props.modelValue)
watch(() => props.modelValue, (value) => {
  if (!jsonError.value) jsonText.value = stringify(value)
})
function onJson(event: Event) {
  jsonText.value = (event.target as HTMLTextAreaElement).value
  if (!jsonText.value.trim()) {
    jsonError.value = ''
    update(undefined)
    return
  }
  try {
    const parsed = JSON.parse(jsonText.value) as unknown
    jsonError.value = ''
    update(parsed)
  } catch {
    jsonError.value = 'JSON 格式无效'
  }
}
</script>

<template>
  <div v-if="visible" class="dynamic-field" :class="[`field-${kind}`, { 'has-error': ownErrors.length || jsonError }]" :data-field-path="pathKey">
    <template v-if="kind === 'object'">
      <details v-if="path.length" class="nested-object" open>
        <summary>{{ label }}<span v-if="required" class="required">*</span></summary>
        <p v-if="schema.description" class="field-description">{{ schema.description }}</p>
        <div class="nested-fields">
          <DynamicField
            v-for="key in orderedKeys"
            :key="key"
            :model-value="objectValue[key]"
            :schema="properties[key]"
            :ui-schema="fieldUi(key)"
            :path="[...path, key]"
            :variables="variables"
            :errors="errors"
            :root-config="rootConfig"
            :required="schema.required?.includes(key)"
            @change="forwardChange"
          />
        </div>
      </details>
      <div v-else class="schema-fields">
        <DynamicField
          v-for="key in orderedKeys"
          :key="key"
          :model-value="objectValue[key]"
          :schema="properties[key]"
          :ui-schema="fieldUi(key)"
          :path="[...path, key]"
          :variables="variables"
          :errors="errors"
          :root-config="rootConfig"
          :required="schema.required?.includes(key)"
          @change="forwardChange"
        />
      </div>
    </template>

    <template v-else-if="kind === 'cases'">
      <label class="field-label">{{ label }}<span v-if="required" class="required">*</span></label>
      <p v-if="schema.description" class="field-description">{{ schema.description }}</p>
      <CasesEditor :model-value="modelValue" :options="variables" @update:model-value="update" />
    </template>

    <template v-else-if="kind === 'key-value'">
      <label class="field-label">{{ label }}<span v-if="required" class="required">*</span></label>
      <p v-if="schema.description" class="field-description">{{ schema.description }}</p>
      <KeyValueEditor :model-value="modelValue" :shape="schemaType === 'object' ? 'object' : 'array'" :declaration="fieldKey === 'outputs'" @update:model-value="update" />
    </template>

    <template v-else-if="kind === 'array-objects'">
      <label class="field-label">{{ label }}<span v-if="required" class="required">*</span></label>
      <p v-if="schema.description" class="field-description">{{ schema.description }}</p>
      <div class="object-array">
        <section v-for="(item, index) in arrayValue" :key="index" class="object-array-item">
          <header><span>{{ label }} {{ index + 1 }}</span><button class="icon-button" title="删除此项" @click="removeArrayItem(index)"><Trash2 :size="13" /></button></header>
          <DynamicField
            v-for="key in Object.keys(schema.items?.properties ?? {})"
            :key="key"
            :model-value="typeof item === 'object' && item !== null ? (item as Record<string, unknown>)[key] : undefined"
            :schema="schema.items!.properties![key]"
            :ui-schema="fieldUi(key)"
            :path="[...path, index, key]"
            :variables="variables"
            :errors="errors"
            :root-config="rootConfig"
            :required="schema.items?.required?.includes(key)"
            @change="forwardChange"
          />
        </section>
      </div>
      <button class="add-row-button" @click="addArrayItem"><Plus :size="13" />添加{{ label }}</button>
    </template>

    <template v-else-if="kind === 'variable'">
      <label class="field-label">{{ label }}<span v-if="required" class="required">*</span></label>
      <p v-if="schema.description" class="field-description">{{ schema.description }}</p>
      <VariableSelector :model-value="modelValue as never" :options="variables" :format="variableFormat" @update:model-value="update" />
    </template>

    <template v-else-if="kind === 'code' || kind === 'prompt'">
      <label class="field-label">{{ label }}<span v-if="required" class="required">*</span></label>
      <p v-if="schema.description" class="field-description">{{ schema.description }}</p>
      <CodeEditor
        :model-value="String(modelValue ?? '')"
        :language="kind === 'prompt' ? 'prompt' : codeLanguage"
        :variables="variables"
        :height="editorHeight"
        @update:model-value="update"
      />
    </template>

    <template v-else-if="kind === 'boolean'">
      <label class="boolean-field">
        <input type="checkbox" :checked="Boolean(modelValue)" @change="onBoolean" />
        <span><strong>{{ label }}<span v-if="required" class="required">*</span></strong><small v-if="schema.description">{{ schema.description }}</small></span>
      </label>
    </template>

    <template v-else>
      <label class="field-label" :for="`field-${pathKey}`">{{ label }}<span v-if="required" class="required">*</span></label>
      <p v-if="schema.description" class="field-description">{{ schema.description }}</p>
      <select v-if="kind === 'select'" :id="`field-${pathKey}`" :value="enumValues.indexOf(modelValue)" @change="onEnum">
        <option :value="-1">请选择</option>
        <option v-for="(option, index) in enumValues" :key="index" :value="index">{{ String(option) }}</option>
      </select>
      <textarea v-else-if="kind === 'textarea'" :id="`field-${pathKey}`" :value="String(modelValue ?? '')" rows="4" :placeholder="String(uiOptions.placeholder ?? '')" @input="onText" />
      <input v-else-if="kind === 'number'" :id="`field-${pathKey}`" type="number" :value="modelValue === undefined ? '' : Number(modelValue)" :min="schema.minimum" :max="schema.maximum" @input="onNumber" />
      <input v-else-if="kind === 'text'" :id="`field-${pathKey}`" type="text" :value="String(modelValue ?? '')" :placeholder="String(uiOptions.placeholder ?? '')" :maxlength="schema.maxLength" @input="onText" />
      <textarea v-else :id="`field-${pathKey}`" class="json-input mono" :value="jsonText" rows="5" spellcheck="false" @input="onJson" />
      <span v-if="jsonError" class="field-error">{{ jsonError }}</span>
    </template>

    <span v-for="error in ownErrors" :key="error" class="field-error">{{ error }}</span>
  </div>
</template>
