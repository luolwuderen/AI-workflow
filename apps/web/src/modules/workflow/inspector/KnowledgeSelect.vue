<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { BookOpen, LoaderCircle, RefreshCw } from 'lucide-vue-next'
import { knowledgeApi } from '../api/client'
import type { KnowledgeBase } from '../api/types'

const props = defineProps<{ modelValue: unknown }>()
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()

const bases = ref<KnowledgeBase[]>([])
const loading = ref(false)
const error = ref('')

function selectedIds(): string[] {
  return Array.isArray(props.modelValue) ? props.modelValue.map(String) : []
}

function selected(id: string): boolean {
  return selectedIds().includes(id)
}

function toggle(id: string, checked: boolean) {
  const current = selectedIds()
  if (checked && !current.includes(id)) emit('update:modelValue', [...current, id])
  else if (!checked) emit('update:modelValue', current.filter((item) => item !== id))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    bases.value = await knowledgeApi.list()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="knowledge-select" data-testid="knowledge-select">
    <div class="select-toolbar">
      <span class="select-count">已选 {{ selectedIds().length }} 个知识库</span>
      <button class="icon-button" title="刷新知识库列表" :disabled="loading" @click="load">
        <LoaderCircle v-if="loading" class="spin" :size="13" /><RefreshCw v-else :size="13" />
      </button>
    </div>
    <p v-if="error" class="select-error">{{ error }}</p>
    <div v-else-if="loading" class="select-hint"><LoaderCircle class="spin" :size="13" />加载中…</div>
    <div v-else-if="bases.length === 0" class="select-hint">
      <BookOpen :size="13" />暂无知识库，请先打开知识库面板创建。
    </div>
    <ul v-else class="select-list">
      <li v-for="base in bases" :key="base.id">
        <label>
          <input type="checkbox" :checked="selected(base.id)"
                 @change="toggle(base.id, ($event.target as HTMLInputElement).checked)" />
          <span class="select-meta">
            <strong>{{ base.name }}</strong>
            <small>{{ base.description || '无描述' }}</small>
          </span>
        </label>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.knowledge-select { display: flex; flex-direction: column; gap: 6px; }
.select-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.select-count { color: #64748b; font-size: 11.5px; }
.select-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.select-list label {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 7px 9px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
}
.select-list label:hover { background: #f8fafc; }
.select-list input { margin-top: 2px; }
.select-meta { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.select-meta strong { font-size: 12.5px; font-weight: 600; color: #0f172a; }
.select-meta small { font-size: 11.5px; color: #64748b; }
.select-hint, .select-error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 7px 9px;
  font-size: 12px;
  border-radius: 8px;
}
.select-hint { color: #64748b; background: #f8fafc; }
.select-error { color: #b91c1c; background: #fef2f2; }
.icon-button {
  border: 0;
  background: transparent;
  color: #64748b;
  display: inline-flex;
  padding: 4px;
  border-radius: 6px;
  cursor: pointer;
}
.icon-button:hover { background: #f1f5f9; color: #0f172a; }
.icon-button:disabled { opacity: 0.4; cursor: not-allowed; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
