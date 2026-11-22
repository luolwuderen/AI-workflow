import type { GraphDocument } from '../api/types'
import { cloneGraph } from '../../../shared/utils/graph'

export class GraphHistory {
  private past: GraphDocument[] = []
  private future: GraphDocument[] = []
  private transaction: GraphDocument | undefined

  constructor(private readonly limit = 60) {}

  capture(graph: GraphDocument): void {
    this.past.push(cloneGraph(graph))
    if (this.past.length > this.limit) this.past.shift()
    this.future = []
  }

  begin(graph: GraphDocument): void {
    if (!this.transaction) this.transaction = cloneGraph(graph)
  }

  end(graph: GraphDocument): void {
    if (!this.transaction) return
    if (JSON.stringify(this.transaction) !== JSON.stringify(graph)) {
      this.past.push(this.transaction)
      if (this.past.length > this.limit) this.past.shift()
      this.future = []
    }
    this.transaction = undefined
  }

  undo(current: GraphDocument): GraphDocument | undefined {
    const previous = this.past.pop()
    if (!previous) return undefined
    this.future.push(cloneGraph(current))
    return cloneGraph(previous)
  }

  redo(current: GraphDocument): GraphDocument | undefined {
    const next = this.future.pop()
    if (!next) return undefined
    this.past.push(cloneGraph(current))
    return cloneGraph(next)
  }

  clear(): void {
    this.past = []
    this.future = []
    this.transaction = undefined
  }

  get canUndo(): boolean {
    return this.past.length > 0
  }

  get canRedo(): boolean {
    return this.future.length > 0
  }
}
