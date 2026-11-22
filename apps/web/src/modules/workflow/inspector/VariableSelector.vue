<script setup lang="ts">
import { computed } from 'vue'
import { Braces } from 'lucide-vue-next'
import type { VariableOption, VariableValue } from '../nodes/variables'
import { formatVariable, variableOptionId } from '../nodes/variables'

const props = withDefaults(defineProps<{
  modelValue: VariableValue
  options: VariableOption[]
  placeholder?: string
  format?: string
}>(), { placeholder: '选择上游变量' })
const emit = defineEmits<{ 'update:modelValue': [value: VariableValue] }>()

const selected = computed(() => variableOptionId(props.modelValue))
const groups = computed(() => {
  const result = new Map<string, VariableOption[]>()
  for (const option of props.options) result.set(option.nodeTitle, [...(result.get(option.nodeTitle) ?? []), option])
  return [...result.entries()]
})
function onChange(event: Event) {
  const id = (event.target as HTMLSelectElement).value
  if (!id) {
    emit('update:modelValue', Array.isArray(props.modelValue) ? [] : undefined)
    return
  }
  const option = props.options.find((item) => item.id === id)
  if (option) emit('update:modelValue', formatVariable(option, props.modelValue, props.format))
}
</script>

<template>
  <div class="variable-selector">
    <Braces :size="14" />
    <select :value="selected" :aria-label="placeholder" @change="onChange">
      <option value="">{{ placeholder }}</option>
      <optgroup v-for="[nodeTitle, variables] in groups" :key="nodeTitle" :label="nodeTitle">
        <option v-for="option in variables" :key="option.id" :value="option.id">
          {{ option.name }} · {{ option.type }}
        </option>
      </optgroup>
    </select>
  </div>
</template>
