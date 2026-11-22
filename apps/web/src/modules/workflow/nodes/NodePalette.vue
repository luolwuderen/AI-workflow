<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Component } from 'vue'
import {
  Box,
  Braces,
  Database,
  GitBranch,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Send,
  Sparkles,
  X,
} from 'lucide-vue-next'
import { useWorkflowStore } from '../store/workflow'

const emit = defineEmits<{ add: [type: string] }>()
const store = useWorkflowStore()
const collapsed = ref(false)
const search = ref('')
const category = ref('全部')

const categories = computed(() => ['全部', ...new Set(store.visibleCatalog.map((node) => node.category))])
const filtered = computed(() => {
  const query = search.value.trim().toLowerCase()
  return store.visibleCatalog.filter((node) => {
    if (category.value !== '全部' && node.category !== category.value) return false
    return !query || `${node.title} ${node.type} ${node.description}`.toLowerCase().includes(query)
  })
})
const grouped = computed(() => {
  const groups = new Map<string, typeof filtered.value>()
  for (const node of filtered.value) {
    groups.set(node.category, [...(groups.get(node.category) ?? []), node])
  }
  return [...groups.entries()]
})

const icons: Record<string, Component> = {
  开始: Box,
  输入: Box,
  AI: Sparkles,
  控制: GitBranch,
  数据: Braces,
  工具: Database,
  输出: Send,
}
function categoryIcon(name: string): Component {
  return icons[name] ?? Box
}
function startDrag(event: DragEvent, type: string) {
  if (!event.dataTransfer) return
  event.dataTransfer.effectAllowed = 'copy'
  event.dataTransfer.setData('application/weave-node', type)
  event.dataTransfer.setData('text/plain', type)
}
</script>

<template>
  <aside class="node-palette" :class="{ collapsed }" data-testid="node-palette">
    <div v-if="collapsed" class="palette-collapsed">
      <button class="icon-button" title="展开节点目录" @click="collapsed = false">
        <PanelLeftOpen :size="17" />
      </button>
      <span>节点</span>
    </div>
    <template v-else>
      <div class="palette-heading">
        <div>
          <span class="eyebrow">NODE LIBRARY</span>
          <h2>节点目录</h2>
        </div>
        <button class="icon-button" title="收起节点目录" @click="collapsed = true">
          <PanelLeftClose :size="16" />
        </button>
      </div>
      <div class="palette-search">
        <Search :size="15" />
        <input v-model="search" data-testid="node-search" placeholder="搜索名称、类型或能力" />
        <button v-if="search" class="clear-search" title="清除搜索" @click="search = ''"><X :size="13" /></button>
      </div>
      <div class="category-tabs" aria-label="节点分类">
        <button
          v-for="item in categories"
          :key="item"
          :class="{ active: category === item }"
          @click="category = item"
        >{{ item }}</button>
      </div>
      <div class="palette-scroll">
        <section v-for="[group, nodes] in grouped" :key="group" class="palette-group">
          <h3><span>{{ group }}</span><span class="count">{{ nodes.length }}</span></h3>
          <button
            v-for="node in nodes"
            :key="node.type"
            class="palette-node"
            draggable="true"
            :data-node-type="node.type"
            :title="`${node.description}${node.supportMessage ? `\n${node.supportMessage}` : ''}`"
            @dragstart="startDrag($event, node.type)"
            @click="emit('add', node.type)"
          >
            <span class="node-kind-icon"><component :is="categoryIcon(node.category)" :size="15" /></span>
            <span class="palette-node-copy">
              <span class="palette-node-title">{{ node.title }}</span>
              <span class="palette-node-description">{{ node.description }}</span>
            </span>
            <span v-if="!node.executable" class="support-dot" title="当前版本不可执行" />
          </button>
        </section>
        <div v-if="filtered.length === 0" class="palette-empty">
          <Search :size="22" />
          <p>没有匹配的节点</p>
          <span>尝试其他关键词或分类</span>
        </div>
      </div>
      <div class="palette-footnote">
        <span class="support-dot" /> 标记节点可配置保存，当前不可执行
      </div>
    </template>
  </aside>
</template>
