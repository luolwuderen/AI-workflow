import { describe, expect, it } from 'vitest'
import type { CatalogNode, GraphDocument } from '../api/types'
import { buildVariableOptions, formatVariable, upstreamNodeIds } from './variables'

const graph: GraphDocument = {
  version: 1,
  appType: 'WORKFLOW',
  nodes: [
    { id: 'start', type: 'USER_INPUT', title: '用户输入', position: { x: 0, y: 0 }, config: { fields: [{ variable: 'query', label: '问题', type: 'text' }] } },
    { id: 'code', type: 'CODE', title: '代码处理', position: { x: 0, y: 0 }, config: { outputs: { result: { type: 'string' } } } },
    { id: 'llm', type: 'LLM', title: '模型', position: { x: 0, y: 0 }, config: {} },
    { id: 'other', type: 'TEMPLATE', title: '旁路', position: { x: 0, y: 0 }, config: {} },
  ],
  edges: [
    { id: 'e1', source: 'start', target: 'code' },
    { id: 'e2', source: 'code', target: 'llm' },
    { id: 'e3', source: 'other', target: 'code' },
  ],
}

const catalog = [
  { type: 'USER_INPUT', title: 'User Input', outputs: [{ name: 'fallback', type: 'string' }] },
  { type: 'CODE', title: 'Code', outputs: [{ name: 'static', type: 'string' }] },
  { type: 'LLM', title: 'LLM', outputs: { type: 'object', properties: { text: { type: 'string' }, usage: { type: 'object' } } } },
  { type: 'TEMPLATE', title: 'Template', outputs: [{ name: 'output', type: 'string' }] },
] as CatalogNode[]

describe('变量选项', () => {
  it('只列出当前节点的全部上游，并采用动态输入与代码输出声明', () => {
    expect(upstreamNodeIds(graph, 'llm')).toEqual(new Set(['start', 'code', 'other']))
    const options = buildVariableOptions(graph, catalog, 'llm')
    expect(options.map((option) => option.id)).toEqual(['start.query', 'code.result', 'other.output'])
    expect(options[0].label).toBe('用户输入 / 问题')
  })

  it('不会把当前节点或下游输出列入选项', () => {
    const options = buildVariableOptions(graph, catalog, 'code')
    expect(options.map((option) => option.id)).toEqual(['start.query', 'other.output'])
    expect(options.some((option) => option.nodeId === 'llm')).toBe(false)
  })

  it('读取后端发布的输出 JSON Schema', () => {
    const ids = buildVariableOptions(graph, catalog).map((option) => option.id)
    expect(ids).toContain('llm.text')
    expect(ids).toContain('llm.usage')
    expect(ids).not.toContain('llm.properties')
  })

  it('作用域内节点可引用容器外部的上游变量', () => {
    const scoped: GraphDocument = {
      version: 1,
      appType: 'WORKFLOW',
      nodes: [
        { id: 'start', type: 'USER_INPUT', title: '用户输入', position: { x: 0, y: 0 }, config: { fields: [{ variable: 'query', label: '问题', type: 'text' }] } },
        { id: 'iteration', type: 'iteration', title: '迭代', position: { x: 0, y: 0 }, config: { inputSelector: 'start.query' } },
        { id: 'body', type: 'TEMPLATE', title: '迭代体', position: { x: 0, y: 0 }, config: {}, parentId: 'iteration' },
      ],
      edges: [
        { id: 'e1', source: 'start', target: 'iteration' },
      ],
    }

    expect(buildVariableOptions(scoped, catalog, 'body').map((option) => option.id)).toEqual(['start.query'])
    expect(buildVariableOptions(scoped, catalog, 'iteration').map((option) => option.id))
      .toEqual(['start.query', 'body.output'])
  })

  it('将 Human Input 的动态表单字段发布为下游变量', () => {
    const humanGraph: GraphDocument = {
      version: 1,
      appType: 'WORKFLOW',
      nodes: [
        { id: 'human', type: 'human-input', title: '人工审批', position: { x: 0, y: 0 }, config: { fields: [
          { variable: 'note', label: '处理意见', type: 'paragraph', required: true },
          { variable: 'approved', label: '是否通过', type: 'boolean', required: true },
        ] } },
        { id: 'output', type: 'output', title: '输出', position: { x: 0, y: 0 }, config: {} },
      ],
      edges: [{ id: 'e1', source: 'human', target: 'output' }],
    }

    const options = buildVariableOptions(humanGraph, catalog, 'output')
    expect(options.map((option) => option.id)).toEqual(['human.note', 'human.approved'])
    expect(options[0].label).toBe('人工审批 / 处理意见')
    expect(options[1].type).toBe('boolean')
  })

  it('按原字段形态生成 Dify 变量引用', () => {
    const option = buildVariableOptions(graph, catalog, 'code')[0]
    expect(formatVariable(option, [])).toEqual(['start', 'query'])
    expect(formatVariable(option, { nodeId: '', path: '' })).toEqual({ nodeId: 'start', path: 'query' })
    expect(formatVariable(option, '')).toBe('start.query')
    expect(formatVariable(option, '', 'template')).toBe('{{start.query}}')
  })
})
