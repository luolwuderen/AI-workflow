import type { GraphDocument } from '../../modules/workflow/api/types'

export function cloneJson<T>(value: T): T {
  if (value === undefined) return value
  return JSON.parse(JSON.stringify(value)) as T
}

export function cloneGraph(graph: GraphDocument): GraphDocument {
  return cloneJson(graph)
}

export function createId(prefix = 'node'): string {
  const random = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  return `${prefix}-${random}`
}

export function setAtPath(
  source: Record<string, unknown>,
  path: Array<string | number>,
  value: unknown,
): Record<string, unknown> {
  if (path.length === 0) return value as Record<string, unknown>
  const root = cloneJson(source)
  let cursor: unknown = root
  for (let index = 0; index < path.length - 1; index += 1) {
    const key = path[index]
    const nextKey = path[index + 1]
    if (typeof key === 'number') {
      if (!Array.isArray(cursor)) throw new Error('配置路径不是数组')
      if (cursor[key] === undefined) cursor[key] = typeof nextKey === 'number' ? [] : {}
      cursor = cursor[key]
    } else {
      if (typeof cursor !== 'object' || cursor === null || Array.isArray(cursor)) {
        throw new Error('配置路径不是对象')
      }
      const record = cursor as Record<string, unknown>
      if (record[key] === undefined || record[key] === null) {
        record[key] = typeof nextKey === 'number' ? [] : {}
      }
      cursor = record[key]
    }
  }
  const last = path[path.length - 1]
  if (typeof last === 'number') {
    if (!Array.isArray(cursor)) throw new Error('配置路径不是数组')
    cursor[last] = value
  } else {
    if (typeof cursor !== 'object' || cursor === null || Array.isArray(cursor)) {
      throw new Error('配置路径不是对象')
    }
    ;(cursor as Record<string, unknown>)[last] = value
  }
  return root
}

export function valueAtPath(source: unknown, path: string | undefined): unknown {
  if (!path) return undefined
  const parts = path.replace(/\[(\d+)\]/g, '.$1').split('.').filter(Boolean)
  let value = source
  for (const part of parts) {
    if (typeof value !== 'object' || value === null) return undefined
    value = (value as Record<string, unknown>)[part]
  }
  return value
}
