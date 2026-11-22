import { describe, expect, it } from 'vitest'
import { reactive } from 'vue'
import type { GraphDocument } from '../api/types'
import { cloneGraph } from '../../../shared/utils/graph'
import { GraphHistory } from './graphHistory'

function graph(): GraphDocument {
  return {
    version: 1,
    appType: 'WORKFLOW',
    nodes: [{ id: 'n1', type: 'CODE', title: '代码', position: { x: 0, y: 0 }, config: {} }],
    edges: [],
  }
}

describe('图历史', () => {
  it('可以从 Vue 响应式图文档创建独立快照', () => {
    const source = reactive(graph())
    const snapshot = cloneGraph(source)
    snapshot.nodes[0].title = '快照修改'
    expect(source.nodes[0].title).toBe('代码')
  })

  it('撤销与重做恢复独立快照并清空分叉未来记录', () => {
    const history = new GraphHistory()
    const original = graph()
    const changed = cloneGraph(original)
    changed.nodes[0].title = '已修改'
    history.capture(original)

    expect(history.undo(changed)?.nodes[0].title).toBe('代码')
    expect(history.canRedo).toBe(true)
    expect(history.redo(original)?.nodes[0].title).toBe('已修改')

    expect(history.undo(changed)?.nodes[0].title).toBe('代码')
    expect(history.canRedo).toBe(true)
    history.capture(original)
    const diverged = cloneGraph(original)
    diverged.nodes[0].title = '分叉'
    expect(history.canRedo).toBe(false)
    expect(history.undo(diverged)?.nodes[0].title).toBe('代码')
  })

  it('拖动事务只形成一条历史记录', () => {
    const history = new GraphHistory()
    const original = graph()
    const moved = cloneGraph(original)
    history.begin(original)
    moved.nodes[0].position = { x: 20, y: 30 }
    moved.nodes[0].position = { x: 120, y: 180 }
    history.end(moved)

    expect(history.canUndo).toBe(true)
    expect(history.undo(moved)?.nodes[0].position).toEqual({ x: 0, y: 0 })
    expect(history.canUndo).toBe(false)
  })
})
