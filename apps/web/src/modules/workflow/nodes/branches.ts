import type { GraphNode } from '../api/types'

export interface BranchHandle {
  id: string
  label: string
}

function normalizedType(node: GraphNode): string {
  return node.type.toUpperCase().replace(/[ -]/g, '_')
}

export function branchHandles(node: GraphNode): BranchHandle[] {
  const type = normalizedType(node)
  if (type === 'IF' || type === 'IF_ELSE' || type === 'IFELSE') {
    const cases = Array.isArray(node.config.cases) ? node.config.cases : []
    const branches = cases.flatMap((item, index) => {
      if (typeof item !== 'object' || item === null) return []
      const config = item as Record<string, unknown>
      const id = String(config.id ?? config.caseId ?? `case-${index + 1}`)
      return [{ id, label: index === 0 ? 'IF' : `ELIF ${index}` }]
    })
    return [...branches, { id: 'else', label: 'ELSE' }]
  }
  if (type === 'QUESTION_CLASSIFIER') {
    const classes = Array.isArray(node.config.classes) ? node.config.classes : []
    return classes.flatMap((item, index) => {
      if (typeof item !== 'object' || item === null) return []
      const value = item as Record<string, unknown>
      return [{
        id: String(value.id ?? `class-${index + 1}`),
        label: String(value.name ?? value.title ?? `分类 ${index + 1}`),
      }]
    })
  }
  if (node.config.errorStrategy === 'failBranch' || node.config.errorStrategy === 'FAIL_BRANCH') {
    return [
      { id: 'source', label: '成功' },
      { id: 'fail', label: '失败' },
    ]
  }
  return []
}
