import type { CatalogNode, GraphDocument, GraphNode, NodeOutput } from '../api/types'

export interface VariableOption {
  id: string
  nodeId: string
  nodeTitle: string
  name: string
  label: string
  type: string
  description?: string
}

function normalizeType(type: string): string {
  return type.toUpperCase().replace(/[ -]/g, '_')
}

function hasDynamicFormOutputs(node: GraphNode, definition?: CatalogNode): boolean {
  const type = normalizeType(node.type)
  return type === 'USER_INPUT' || type === 'START' || type === 'INPUT' || type === 'HUMAN_INPUT'
    || definition?.title.toLowerCase() === 'user input' || definition?.title.toLowerCase() === 'human input'
}

function outputsFromFormFields(node: GraphNode): NodeOutput[] {
  const fields = node.config.fields ?? node.config.inputs ?? node.config.variables
  if (!Array.isArray(fields)) return []
  return fields.flatMap((field) => {
    if (typeof field !== 'object' || field === null) return []
    const item = field as Record<string, unknown>
    const name = String(item.variable ?? item.name ?? item.key ?? '')
    if (!name) return []
    return [{
      name,
      type: String(item.type ?? 'string'),
      title: typeof item.label === 'string' ? item.label : name,
    }]
  })
}

function configuredOutputs(node: GraphNode): NodeOutput[] {
  const outputs = node.config.outputs
  if (Array.isArray(outputs)) {
    return outputs.flatMap((output) => {
      if (typeof output === 'string') return [{ name: output, type: 'string' }]
      if (typeof output !== 'object' || output === null) return []
      return [output as NodeOutput]
    })
  }
  if (typeof outputs === 'object' && outputs !== null) {
    return Object.entries(outputs).map(([name, definition]) => {
      if (typeof definition === 'object' && definition !== null) {
        return { name, ...(definition as Record<string, unknown>) }
      }
      return { name, type: String(definition ?? 'any') }
    })
  }
  return []
}

function nodeOutputs(node: GraphNode, definition?: CatalogNode): NodeOutput[] {
  const dynamic = hasDynamicFormOutputs(node, definition) ? outputsFromFormFields(node) : configuredOutputs(node)
  if (dynamic.length > 0) return dynamic
  const outputs = definition?.outputs
  if (Array.isArray(outputs)) {
    return outputs.map((output) => typeof output === 'string' ? { name: output } : output)
  }
  if (typeof outputs === 'object' && outputs !== null) {
    const schemaProperties = 'properties' in outputs && typeof outputs.properties === 'object' && outputs.properties !== null
      ? outputs.properties as Record<string, unknown>
      : outputs
    return Object.entries(schemaProperties).flatMap(([name, definitionValue]) => {
      if (name === 'type' && 'properties' in outputs) return []
      if (typeof definitionValue === 'object' && definitionValue !== null) {
        return [{ name, ...(definitionValue as Record<string, unknown>) }]
      }
      return [{ name, type: String(definitionValue ?? 'any') }]
    })
  }
  return []
}

export function upstreamNodeIds(graph: GraphDocument, currentNodeId?: string): Set<string> {
  if (!currentNodeId) return new Set(graph.nodes.map((node) => node.id))
  const upstream = new Set<string>()
  const visit = (nodeId: string) => {
    for (const edge of graph.edges) {
      if (edge.target !== nodeId || upstream.has(edge.source)) continue
      upstream.add(edge.source)
      visit(edge.source)
    }
  }
  visit(currentNodeId)
  return upstream
}

export function buildVariableOptions(
  graph: GraphDocument,
  catalog: CatalogNode[],
  currentNodeId?: string,
): VariableOption[] {
  const definitions = new Map(catalog.map((definition) => [definition.type, definition]))
  const allowed = upstreamNodeIds(graph, currentNodeId)
  // 作用域内的节点还可以引用容器外部的上游变量
  const current = graph.nodes.find((node) => node.id === currentNodeId)
  if (current?.parentId) upstreamNodeIds(graph, current.parentId).forEach((id) => allowed.add(id))
  // 容器自身的 outputSelector / breakConditions 需要引用作用域内的节点输出
  if (current && (current.type === 'iteration' || current.type === 'loop'))
    graph.nodes.filter((node) => node.parentId === current.id).forEach((node) => allowed.add(node.id))
  return graph.nodes
    .filter((node) => allowed.has(node.id) && node.id !== currentNodeId)
    .flatMap((node) => {
      const definition = definitions.get(node.type)
      return nodeOutputs(node, definition).flatMap((output, index) => {
        const name = String(output.name ?? output.path ?? `output_${index + 1}`)
        return [{
          id: `${node.id}.${name}`,
          nodeId: node.id,
          nodeTitle: node.title,
          name,
          label: `${node.title} / ${output.title ?? name}`,
          type: String(output.type ?? 'any'),
          description: output.description,
        }]
      })
    })
}

export type VariableValue = string | string[] | { nodeId: string; path?: string; output?: string } | undefined

export function variableOptionId(value: VariableValue): string {
  if (Array.isArray(value)) return value.join('.')
  if (typeof value === 'object' && value !== null) {
    return `${value.nodeId}.${value.path ?? value.output ?? ''}`
  }
  if (typeof value === 'string') {
    const dify = value.match(/^\{\{#([^.]+)\.([^}]+)#\}\}$/)
    return dify ? `${dify[1]}.${dify[2]}` : value
  }
  return ''
}

export function formatVariable(option: VariableOption, current: VariableValue, format?: string): VariableValue {
  if (format === 'dify-template') return `{{#${option.nodeId}.${option.name}#}}`
  if (format === 'template') return `{{${option.nodeId}.${option.name}}}`
  if (format === 'array' || (!format && Array.isArray(current))) return [option.nodeId, option.name]
  if (format === 'object' || (!format && typeof current === 'object' && current !== null)) {
    return { nodeId: option.nodeId, path: option.name }
  }
  return `${option.nodeId}.${option.name}`
}
