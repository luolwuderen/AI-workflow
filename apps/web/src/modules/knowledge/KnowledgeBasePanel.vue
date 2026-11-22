<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { BookOpen, FileText, LoaderCircle, Plus, Trash2, X } from 'lucide-vue-next'
import { knowledgeApi } from '../workflow/api/client'
import { useWorkflowStore } from '../workflow/store/workflow'
import type { KnowledgeBase, KnowledgeDocument } from '../workflow/api/types'

const emit = defineEmits<{ close: [] }>()

const store = useWorkflowStore()
const retrievalTarget = computed(() => {
  const node = store.selectedNode
  return node && node.type === 'knowledge-retrieval' ? node : null
})

const bases = ref<KnowledgeBase[]>([])
const documents = ref<KnowledgeDocument[]>([])
const selected = ref<KnowledgeBase | null>(null)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const newName = ref('')
const newDescription = ref('')
const documentName = ref('')
const documentContent = ref('')

function fail(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : String(cause)
}

async function loadBases() {
  loading.value = true
  error.value = ''
  try {
    bases.value = await knowledgeApi.list()
  } catch (cause) {
    fail(cause)
  } finally {
    loading.value = false
  }
}

async function selectBase(base: KnowledgeBase) {
  selected.value = base
  documents.value = []
  error.value = ''
  try {
    documents.value = await knowledgeApi.documents(base.id)
  } catch (cause) {
    fail(cause)
  }
}

async function createBase() {
  if (!newName.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const created = await knowledgeApi.create(newName.value.trim(), newDescription.value.trim())
    newName.value = ''
    newDescription.value = ''
    await loadBases()
    await selectBase(created)
  } catch (cause) {
    fail(cause)
  } finally {
    busy.value = false
  }
}

async function removeBase(base: KnowledgeBase) {
  if (busy.value || !window.confirm(`删除知识库「${base.name}」及其全部文档？`)) return
  busy.value = true
  error.value = ''
  try {
    await knowledgeApi.remove(base.id)
    if (selected.value?.id === base.id) {
      selected.value = null
      documents.value = []
    }
    await loadBases()
  } catch (cause) {
    fail(cause)
  } finally {
    busy.value = false
  }
}

async function addDocument() {
  if (!selected.value || !documentName.value.trim() || !documentContent.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await knowledgeApi.addDocument(selected.value.id, documentName.value.trim(), documentContent.value)
    documentName.value = ''
    documentContent.value = ''
    documents.value = await knowledgeApi.documents(selected.value.id)
  } catch (cause) {
    fail(cause)
  } finally {
    busy.value = false
  }
}

function addToNode(base: KnowledgeBase) {
  const node = retrievalTarget.value
  if (!node) return
  const current = Array.isArray(node.config.datasetIds) ? [...(node.config.datasetIds as string[])] : []
  if (!current.includes(base.id)) current.push(base.id)
  store.updateNodeConfig(node.id, ['datasetIds'], current)
}

async function removeDocument(document: KnowledgeDocument) {
  if (!selected.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await knowledgeApi.removeDocument(selected.value.id, document.id)
    documents.value = await knowledgeApi.documents(selected.value.id)
  } catch (cause) {
    fail(cause)
  } finally {
    busy.value = false
  }
}

onMounted(loadBases)
</script>

<template>
  <div class="knowledge-overlay" data-testid="knowledge-panel">
    <section class="knowledge-panel">
      <header>
        <div class="panel-title"><BookOpen :size="17" /><strong>知识库</strong></div>
        <button class="icon-button" data-testid="close-knowledge" aria-label="关闭知识库" @click="emit('close')">
          <X :size="15" />
        </button>
      </header>

      <p v-if="error" class="error-line">{{ error }}</p>

      <div class="panel-body">
        <aside class="base-list">
          <div class="create-base">
            <input v-model="newName" data-testid="knowledge-name" placeholder="新知识库名称" />
            <input v-model="newDescription" placeholder="描述（可选）" />
            <button class="primary-button" :disabled="busy || !newName.trim()" @click="createBase">
              <LoaderCircle v-if="busy" class="spin" :size="13" /><Plus v-else :size="13" /><span>创建</span>
            </button>
          </div>

          <div v-if="loading" class="hint"><LoaderCircle class="spin" :size="14" />加载中…</div>
          <div v-else-if="bases.length === 0" class="hint">还没有知识库</div>
          <ul v-else class="bases">
            <li v-for="base in bases" :key="base.id"
                :class="{ active: selected?.id === base.id }"
                @click="selectBase(base)">
              <div class="base-meta">
                <strong>{{ base.name }}</strong>
                <small>{{ base.description || '无描述' }}</small>
              </div>
              <button class="icon-button" title="加入当前检索节点" :disabled="!retrievalTarget"
                      @click.stop="addToNode(base)">
                <Plus :size="13" />
              </button>
              <button class="icon-button danger" aria-label="删除知识库" @click.stop="removeBase(base)">
                <Trash2 :size="13" />
              </button>
            </li>
          </ul>
        </aside>

        <div class="document-pane">
          <template v-if="selected">
            <h3>{{ selected.name }} 的文档</h3>
            <div class="add-document">
              <input v-model="documentName" data-testid="document-name" placeholder="文档名称" />
              <textarea v-model="documentContent" data-testid="document-content" rows="4"
                        placeholder="粘贴文档内容，导入时自动切块并生成向量" />
              <button class="primary-button"
                      :disabled="busy || !documentName.trim() || !documentContent.trim()"
                      @click="addDocument">
                <LoaderCircle v-if="busy" class="spin" :size="13" /><Plus v-else :size="13" /><span>导入文档</span>
              </button>
            </div>

            <div v-if="documents.length === 0" class="hint">还没有文档</div>
            <ul v-else class="documents">
              <li v-for="document in documents" :key="document.id">
                <FileText :size="14" />
                <div class="document-meta">
                  <strong>{{ document.name }}</strong>
                  <small>{{ document.chunkCount }} 个文本块</small>
                </div>
                <button class="icon-button danger" aria-label="删除文档" @click="removeDocument(document)">
                  <Trash2 :size="13" />
                </button>
              </li>
            </ul>
          </template>
          <div v-else class="hint">选择左侧知识库以管理文档</div>
        </div>
      </div>

      <footer>
        <code>选中知识检索节点后，点击知识库右侧 + 可写入 datasetIds。</code>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.knowledge-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  background: rgba(15, 23, 42, 0.38);
  backdrop-filter: blur(2px);
}
.knowledge-panel {
  width: min(880px, calc(100vw - 48px));
  max-height: calc(100vh - 96px);
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.24);
  overflow: hidden;
}
.knowledge-panel header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid #eef2f7;
}
.panel-title { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.panel-body { display: grid; grid-template-columns: 280px 1fr; min-height: 340px; overflow: hidden; }
.base-list { border-right: 1px solid #eef2f7; padding: 14px; display: flex; flex-direction: column; gap: 10px; overflow: auto; }
.create-base, .add-document { display: flex; flex-direction: column; gap: 8px; }
input, textarea {
  width: 100%;
  border: 1px solid #dbe3ee;
  border-radius: 8px;
  padding: 7px 9px;
  font: inherit;
  font-size: 12.5px;
  color: #0f172a;
  outline: none;
}
input:focus, textarea:focus { border-color: #6366f1; box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.12); }
.bases, .documents { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.bases li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid transparent;
}
.bases li:hover { background: #f6f8fc; }
.bases li.active { background: #eef2ff; border-color: #c7d2fe; }
.base-meta, .document-meta { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; }
.base-meta strong, .document-meta strong { font-size: 12.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.base-meta small, .document-meta small { color: #64748b; font-size: 11.5px; }
.document-pane { padding: 16px; display: flex; flex-direction: column; gap: 12px; overflow: auto; }
.document-pane h3 { margin: 0; font-size: 13px; color: #334155; }
.documents li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid #eef2f7;
  border-radius: 8px;
}
.hint { color: #64748b; font-size: 12.5px; display: flex; align-items: center; gap: 6px; padding: 10px 2px; }
.primary-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 0;
  border-radius: 8px;
  padding: 8px 12px;
  background: #4f46e5;
  color: #fff;
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
}
.primary-button:disabled { opacity: 0.55; cursor: not-allowed; }
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
.icon-button:disabled { opacity: 0.35; cursor: not-allowed; }
.icon-button:disabled:hover { background: transparent; color: #64748b; }
.icon-button.danger:hover { background: #fef2f2; color: #dc2626; }
.error-line { margin: 0; padding: 8px 18px; color: #b91c1c; background: #fef2f2; font-size: 12.5px; }
footer { padding: 10px 18px; border-top: 1px solid #eef2f7; color: #64748b; font-size: 11.5px; }
code { background: #f1f5f9; padding: 2px 5px; border-radius: 4px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
