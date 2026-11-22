import type { GraphDocument } from '../api/types'

export interface RunInputOption {
  value: string
  label: string
}

export interface RunInputField {
  key: string
  label: string
  type: 'text' | 'paragraph' | 'number' | 'select' | 'boolean' | 'json'
  required: boolean
  description?: string
  defaultValue?: unknown
  options: RunInputOption[]
}

function inputType(value: unknown): RunInputField['type'] {
  const type = String(value ?? 'text').toLowerCase().replace(/_/g, '-')
  if (['number', 'integer', 'float'].includes(type)) return 'number'
  if (['select', 'dropdown', 'single-select'].includes(type)) return 'select'
  if (['boolean', 'bool', 'checkbox'].includes(type)) return 'boolean'
  if (['json', 'object', 'array'].includes(type)) return 'json'
  if (['paragraph', 'textarea', 'text-area'].includes(type)) return 'paragraph'
  return 'text'
}

export function extractRunInputs(graph: GraphDocument): RunInputField[] {
  const start = graph.nodes.find((node) => {
    const type = node.type.toUpperCase().replace(/[ -]/g, '_')
    return type === 'USER_INPUT' || type === 'START' || type === 'INPUT'
  })
  return start ? extractInputFields(start.config.fields ?? start.config.inputs ?? start.config.variables) : []
}

export function extractInputFields(fields: unknown): RunInputField[] {
  if (!Array.isArray(fields)) return []
  return fields.flatMap((field) => {
    if (typeof field !== 'object' || field === null) return []
    const item = field as Record<string, unknown>
    const key = String(item.variable ?? item.name ?? item.key ?? '')
    if (!key) return []
    const rawOptions = item.options ?? item.choices
    const options = Array.isArray(rawOptions) ? rawOptions.map((option) => {
      if (typeof option === 'object' && option !== null) {
        const value = option as Record<string, unknown>
        return { value: String(value.value ?? value.id ?? ''), label: String(value.label ?? value.name ?? value.value ?? '') }
      }
      return { value: String(option), label: String(option) }
    }).filter((option) => option.value) : []
    return [{
      key,
      label: String(item.label ?? item.title ?? key),
      type: inputType(item.type),
      required: Boolean(item.required),
      description: typeof item.description === 'string' ? item.description : undefined,
      defaultValue: item.default ?? item.defaultValue,
      options,
    }]
  })
}

export function initialRunValues(fields: RunInputField[]): Record<string, unknown> {
  return Object.fromEntries(fields.map((field) => [
    field.key,
    field.defaultValue ?? (field.type === 'boolean' ? false : field.type === 'json' ? '' : ''),
  ]))
}

export function normalizeRunValues(
  fields: RunInputField[],
  values: Record<string, unknown>,
): { inputs: Record<string, unknown>; errors: Record<string, string> } {
  const inputs: Record<string, unknown> = {}
  const errors: Record<string, string> = {}
  for (const field of fields) {
    const raw = values[field.key]
    if (field.required && (raw === '' || raw === undefined || raw === null)) {
      errors[field.key] = '请输入此项'
      continue
    }
    if (raw === '' && !field.required) continue
    if (field.type === 'number') {
      const number = Number(raw)
      if (!Number.isFinite(number)) errors[field.key] = '请输入有效数字'
      else inputs[field.key] = number
    } else if (field.type === 'json') {
      try {
        inputs[field.key] = typeof raw === 'string' ? JSON.parse(raw) : raw
      } catch {
        errors[field.key] = '请输入有效 JSON'
      }
    } else {
      inputs[field.key] = raw
    }
  }
  return { inputs, errors }
}
