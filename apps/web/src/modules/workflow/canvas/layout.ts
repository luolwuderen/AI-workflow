import type { GraphDocument } from '../api/types'
import { cloneGraph } from '../../../shared/utils/graph'

export function layoutGraph(source: GraphDocument): GraphDocument {
  const graph = cloneGraph(source)
  const levels = new Map<string, number>()
  const indegree = new Map(graph.nodes.map((node) => [node.id, 0]))
  for (const edge of graph.edges) {
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1)
  }
  const queue = graph.nodes.filter((node) => (indegree.get(node.id) ?? 0) === 0).map((node) => node.id)
  queue.forEach((id) => levels.set(id, 0))

  while (queue.length > 0) {
    const sourceId = queue.shift()!
    const nextLevel = (levels.get(sourceId) ?? 0) + 1
    for (const edge of graph.edges.filter((item) => item.source === sourceId)) {
      levels.set(edge.target, Math.max(levels.get(edge.target) ?? 0, nextLevel))
      const remaining = (indegree.get(edge.target) ?? 1) - 1
      indegree.set(edge.target, remaining)
      if (remaining === 0) queue.push(edge.target)
    }
  }

  for (const node of graph.nodes) {
    if (!levels.has(node.id)) levels.set(node.id, 0)
  }
  const rows = new Map<number, number>()
  for (const node of graph.nodes) {
    const level = levels.get(node.id) ?? 0
    const row = rows.get(level) ?? 0
    node.position = { x: 96 + level * 300, y: 80 + row * 190 }
    rows.set(level, row + 1)
  }
  return graph
}
