import { defineStore } from 'pinia'
import { streamRunEvents, workflowApi } from '../api/client'
import type { NodeRun, RunEvent, WorkflowRun } from '../api/types'
import { useWorkflowStore } from './workflow'

interface RunState {
  panelOpen: boolean
  running: boolean
  currentRun: WorkflowRun | null
  events: RunEvent[]
  error: string
  resumeError: string
  submittingNodeId: string | null
  streamWarning: string
}

let closeStream: (() => void) | undefined
let pollTimer: ReturnType<typeof setInterval> | undefined

function terminal(status: string | undefined): boolean {
  return ['SUCCEEDED', 'COMPLETED', 'FAILED', 'CANCELLED', 'STOPPED'].includes(status ?? '')
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export const useRunStore = defineStore('run', {
  state: (): RunState => ({
    panelOpen: false,
    running: false,
    currentRun: null,
    events: [],
    error: '',
    resumeError: '',
    submittingNodeId: null,
    streamWarning: '',
  }),
  getters: {
    nodeRuns(state): NodeRun[] {
      const runs = state.currentRun?.nodeRuns
      if (Array.isArray(runs)) return runs
      return runs ? Object.values(runs) : []
    },
    latestSequence(state): number {
      return state.events.reduce((latest, event) => Math.max(latest, event.sequence ?? 0), 0)
    },
  },
  actions: {
    openPanel() {
      this.panelOpen = true
    },
    stopTracking() {
      closeStream?.()
      closeStream = undefined
      if (pollTimer) clearInterval(pollTimer)
      pollTimer = undefined
    },
    reset() {
      this.stopTracking()
      this.running = false
      this.currentRun = null
      this.events = []
      this.error = ''
      this.resumeError = ''
      this.submittingNodeId = null
      this.streamWarning = ''
    },
    clear() {
      if (this.running) return
      this.currentRun = null
      this.events = []
      this.error = ''
      this.resumeError = ''
      this.submittingNodeId = null
      this.streamWarning = ''
    },
    addEvent(event: RunEvent) {
      const duplicate = this.events.some((item) =>
        (event.id !== undefined && item.id === event.id)
        || (event.sequence !== undefined && item.sequence === event.sequence))
      if (duplicate) return
      this.events.push(event)
      this.events.sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0))
    },
    async poll(runId: string) {
      try {
        const run = await workflowApi.getRun(runId)
        this.currentRun = run
        if (terminal(run.status)) {
          this.running = false
          this.stopTracking()
        }
      } catch (error) {
        if (!this.streamWarning) this.streamWarning = `运行状态刷新失败，将自动重试：${errorMessage(error)}`
      }
    },
    track(runId: string) {
      this.stopTracking()
      closeStream = streamRunEvents(runId, {
        after: this.latestSequence,
        onEvent: (event) => {
          this.addEvent(event)
          if (['RUN_FINISHED', 'RUN_SUCCEEDED', 'RUN_FAILED', 'RUN_CANCELLED'].includes(event.type) || terminal(event.status)) {
            void this.poll(runId)
          }
        },
        onError: () => {
          this.streamWarning = '事件流暂时不可用，已启用轮询兜底。'
        },
      })
      void this.poll(runId)
      pollTimer = setInterval(() => { void this.poll(runId) }, 1400)
    },
    async resume(nodeId: string, inputs: Record<string, unknown>) {
      const runId = this.currentRun?.id
      if (!runId || this.submittingNodeId) return
      this.submittingNodeId = nodeId
      this.resumeError = ''
      try {
        const resumed = await workflowApi.resumeRun(runId, nodeId, inputs)
        this.currentRun = resumed
        if (terminal(resumed.status)) {
          this.running = false
          this.stopTracking()
        }
      } catch (error) {
        this.resumeError = errorMessage(error)
      } finally {
        this.submittingNodeId = null
      }
    },
    async start(inputs: Record<string, unknown>) {
      const workflow = useWorkflowStore()
      if (!workflow.app || this.running) return
      this.panelOpen = true
      this.error = ''
      this.resumeError = ''
      this.submittingNodeId = null
      this.streamWarning = ''
      this.events = []
      this.currentRun = null
      await workflow.saveNow()
      if (workflow.saveState === 'conflict' || workflow.saveState === 'error') {
        this.error = '草稿尚未保存，请处理保存问题后再运行。'
        return
      }
      const valid = await workflow.validateGraph()
      if (!valid) {
        this.error = workflow.issues.length > 0 ? '请先处理画布中的校验问题。' : workflow.operationError
        return
      }
      this.running = true
      try {
        const run = await workflowApi.createRun(workflow.app.id, inputs)
        this.currentRun = run
        if (terminal(run.status)) this.running = false
        else this.track(run.id)
      } catch (error) {
        this.running = false
        this.error = errorMessage(error)
      }
    },
  },
})
