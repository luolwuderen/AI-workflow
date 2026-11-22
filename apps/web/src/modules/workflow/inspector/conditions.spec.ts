import { describe, expect, it } from 'vitest'
import { matchesVisibility } from './conditions'

describe('配置条件显隐', () => {
  const config = { mode: 'advanced', retry: true, model: { provider: 'local' } }

  it('支持字段等值、嵌套路径和组合规则', () => {
    expect(matchesVisibility(config, { field: 'mode', equals: 'advanced' })).toBe(true)
    expect(matchesVisibility(config, { field: 'model.provider', in: ['remote'] })).toBe(false)
    expect(matchesVisibility(config, [
      { field: 'mode', equals: 'advanced' },
      { field: 'retry', truthy: true },
    ])).toBe(true)
  })

  it('支持简化映射与非等值规则', () => {
    expect(matchesVisibility(config, { mode: 'basic' })).toBe(false)
    expect(matchesVisibility(config, { field: 'mode', notEquals: 'basic' })).toBe(true)
    expect(matchesVisibility(config, undefined)).toBe(true)
  })
})
