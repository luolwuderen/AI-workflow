export interface VisibilityRule {
  field?: string
  path?: string
  equals?: unknown
  notEquals?: unknown
  in?: unknown[]
  truthy?: boolean
}

export type VisibilityCondition = VisibilityRule | VisibilityRule[] | Record<string, unknown>

function readPath(config: Record<string, unknown>, path: string): unknown {
  return path.split('.').reduce<unknown>((value, key) => {
    if (typeof value !== 'object' || value === null) return undefined
    return (value as Record<string, unknown>)[key]
  }, config)
}

export function matchesVisibility(config: Record<string, unknown>, condition: unknown): boolean {
  if (condition === undefined || condition === null) return true
  if (Array.isArray(condition)) return condition.every((rule) => matchesVisibility(config, rule))
  if (typeof condition !== 'object') return Boolean(condition)

  const record = condition as Record<string, unknown>
  if (!('field' in record || 'path' in record)) {
    return Object.entries(record).every(([key, expected]) => {
      const actual = readPath(config, key)
      return Array.isArray(expected) ? expected.includes(actual) : actual === expected
    })
  }

  const rule = record as VisibilityRule
  const actual = readPath(config, rule.field ?? rule.path ?? '')
  if ('equals' in rule && actual !== rule.equals) return false
  if ('notEquals' in rule && actual === rule.notEquals) return false
  if (rule.in && !rule.in.includes(actual)) return false
  if ('truthy' in rule && Boolean(actual) !== rule.truthy) return false
  return true
}

export const CONDITION_OPERATORS = [
  { value: 'equals', label: '等于' },
  { value: 'not-equals', label: '不等于' },
  { value: 'contains', label: '包含' },
  { value: 'not-contains', label: '不包含' },
  { value: 'starts-with', label: '开头是' },
  { value: 'ends-with', label: '结尾是' },
  { value: 'is-empty', label: '为空' },
  { value: 'not-empty', label: '不为空' },
  { value: 'greater', label: '大于' },
  { value: 'greater-or-equal', label: '大于等于' },
  { value: 'less', label: '小于' },
  { value: 'less-or-equal', label: '小于等于' },
] as const
