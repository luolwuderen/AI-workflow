<script setup lang="ts">
import { computed } from 'vue'
import { Plus, Trash2 } from 'lucide-vue-next'
import VariableSelector from './VariableSelector.vue'
import { CONDITION_OPERATORS } from './conditions'
import type { VariableOption, VariableValue } from '../nodes/variables'
import { cloneJson, createId } from '../../../shared/utils/graph'

const props = defineProps<{ modelValue: unknown; options: VariableOption[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: unknown] }>()

const cases = computed<Record<string, unknown>[]>(() => Array.isArray(props.modelValue)
  ? props.modelValue.filter((item): item is Record<string, unknown> => typeof item === 'object' && item !== null)
  : [])
const noValueOperators = ['is-empty', 'not-empty', 'empty', 'not_empty']

function conditions(item: Record<string, unknown>): Record<string, unknown>[] {
  return Array.isArray(item.conditions)
    ? item.conditions.filter((condition): condition is Record<string, unknown> => typeof condition === 'object' && condition !== null)
    : []
}
function updateCase(index: number, patch: Record<string, unknown>) {
  const next = cloneJson(cases.value)
  next[index] = { ...next[index], ...patch }
  emit('update:modelValue', next)
}
function updateCondition(caseIndex: number, conditionIndex: number, patch: Record<string, unknown>) {
  const next = cloneJson(cases.value)
  const list = conditions(next[caseIndex])
  list[conditionIndex] = { ...list[conditionIndex], ...patch }
  next[caseIndex].conditions = list
  emit('update:modelValue', next)
}
function addCase() {
  emit('update:modelValue', [...cloneJson(cases.value), {
    id: createId('case'),
    logicalOperator: 'and',
    conditions: [{ id: createId('condition'), variable: '', operator: 'equals', value: '' }],
  }])
}
function addCondition(caseIndex: number) {
  const next = cloneJson(cases.value)
  next[caseIndex].conditions = [...conditions(next[caseIndex]), { id: createId('condition'), variable: '', operator: 'equals', value: '' }]
  emit('update:modelValue', next)
}
function variableKey(condition: Record<string, unknown>): string {
  return 'leftValue' in condition && !('variable' in condition) ? 'leftValue' : 'variable'
}
function operatorKey(condition: Record<string, unknown>): string {
  return 'comparisonOperator' in condition && !('operator' in condition) ? 'comparisonOperator' : 'operator'
}
function valueKey(condition: Record<string, unknown>): string {
  return 'rightValue' in condition && !('value' in condition) ? 'rightValue' : 'value'
}
</script>

<template>
  <div class="cases-editor">
    <article v-for="(item, caseIndex) in cases" :key="String(item.id ?? caseIndex)" class="case-card">
      <header>
        <span class="case-label">{{ caseIndex === 0 ? 'IF' : `ELIF ${caseIndex}` }}</span>
        <select
          :value="String(item.logicalOperator ?? item.logical_operator ?? 'and')"
          title="条件组合方式"
          @change="updateCase(caseIndex, { logicalOperator: ($event.target as HTMLSelectElement).value })"
        >
          <option value="and">满足全部（AND）</option>
          <option value="or">满足任一（OR）</option>
        </select>
        <button v-if="cases.length > 1" class="icon-button" title="删除分支" @click="emit('update:modelValue', cases.filter((_, index) => index !== caseIndex))"><Trash2 :size="13" /></button>
      </header>
      <div v-for="(condition, conditionIndex) in conditions(item)" :key="String(condition.id ?? conditionIndex)" class="condition-row">
        <VariableSelector
          :model-value="(condition[variableKey(condition)] as VariableValue)"
          :options="options"
          placeholder="变量"
          @update:model-value="updateCondition(caseIndex, conditionIndex, { [variableKey(condition)]: $event })"
        />
        <select
          :value="String(condition[operatorKey(condition)] ?? 'equals')"
          @change="updateCondition(caseIndex, conditionIndex, { [operatorKey(condition)]: ($event.target as HTMLSelectElement).value })"
        >
          <option v-for="operator in CONDITION_OPERATORS" :key="operator.value" :value="operator.value">{{ operator.label }}</option>
        </select>
        <input
          v-if="!noValueOperators.includes(String(condition[operatorKey(condition)]))"
          :value="String(condition[valueKey(condition)] ?? '')"
          placeholder="比较值"
          @input="updateCondition(caseIndex, conditionIndex, { [valueKey(condition)]: ($event.target as HTMLInputElement).value })"
        />
        <button class="icon-button" title="删除条件" @click="updateCase(caseIndex, { conditions: conditions(item).filter((_, index) => index !== conditionIndex) })"><Trash2 :size="12" /></button>
      </div>
      <button class="add-row-button" @click="addCondition(caseIndex)"><Plus :size="12" />添加条件</button>
    </article>
    <div class="else-case"><span>ELSE</span><p>以上条件均不满足时进入此分支</p></div>
    <button class="secondary-button add-case" @click="addCase"><Plus :size="13" />添加 ELIF 分支</button>
  </div>
</template>
