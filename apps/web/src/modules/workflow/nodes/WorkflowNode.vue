<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { AlertCircle, Box, Braces, Database, GitBranch, LoaderCircle, Send, Sparkles } from 'lucide-vue-next'
import type { CatalogNode, GraphNode, NodeRun } from '../api/types'
import { branchHandles } from './branches'

export interface WorkflowNodeData {
  node: GraphNode
  definition?: CatalogNode
  nodeRun?: NodeRun
  /** 所属作用域容器标题 */
  scopeTitle?: string
  /** 容器的作用域内节点数 */
  childCount?: number
}

const props = defineProps<{
  id: string
  data: WorkflowNodeData
  selected?: boolean
}>()

const branches = computed(() => branchHandles(props.data.node))
const status = computed(() => (props.data.nodeRun?.status ?? '').toUpperCase())
const category = computed(() => props.data.definition?.category ?? '节点')
const icons: Record<string, Component> = {
  开始: Box,
  输入: Box,
  AI: Sparkles,
  控制: GitBranch,
  数据: Braces,
  工具: Database,
  输出: Send,
}
const icon = computed(() => icons[category.value] ?? Box)
</script>

<template>
  <div
    class="workflow-node"
    :class="[`status-${status.toLowerCase()}`, { selected, unsupported: data.definition && !data.definition.executable }]"
    :data-node-id="id"
  >
    <Handle id="target" type="target" :position="Position.Left" class="node-handle target-handle" />
    <div class="node-topline" />
    <header class="node-header">
      <span class="node-icon"><component :is="icon" :size="15" /></span>
      <span class="node-category">{{ category }}</span>
      <LoaderCircle v-if="status === 'RUNNING'" class="spin status-icon" :size="14" />
      <AlertCircle v-else-if="status === 'FAILED'" class="status-icon error" :size="14" />
      <span v-else-if="data.definition && !data.definition.executable" class="node-badge">不可执行</span>
      <span v-else class="node-index">{{ data.node.type }}</span>
    </header>
    <div class="node-content">
      <strong>{{ data.node.title }}</strong>
      <span>{{ data.definition?.description || data.node.type }}</span>
      <span v-if="data.scopeTitle" class="node-scope">作用域：{{ data.scopeTitle }}</span>
      <span v-else-if="data.childCount" class="node-scope">{{ data.childCount }} 个子节点</span>
    </div>
    <footer v-if="data.nodeRun" class="node-run-meta">
      <span>{{ status === 'SKIPPED' ? '已跳过' : status === 'RUNNING' ? '执行中' : status === 'WAITING' ? '等待输入' : status === 'FAILED' ? '失败' : '已完成' }}</span>
      <span v-if="data.nodeRun.durationMs !== undefined" class="mono">{{ data.nodeRun.durationMs }} ms</span>
    </footer>
    <template v-if="branches.length">
      <div class="branch-list">
        <div v-for="(branch, index) in branches" :key="branch.id" class="branch-row">
          <span>{{ branch.label }}</span>
          <Handle
            :id="branch.id"
            type="source"
            :position="Position.Right"
            class="node-handle branch-handle"
            :style="{ top: `${((index + 1) / (branches.length + 1)) * 100}%` }"
          />
        </div>
      </div>
    </template>
    <Handle v-else id="source" type="source" :position="Position.Right" class="node-handle source-handle" />
  </div>
</template>
