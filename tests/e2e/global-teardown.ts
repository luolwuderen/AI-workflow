import { request } from '@playwright/test'

const API = 'http://127.0.0.1:8080/api/v1'
const TEST_APP = /^(Acceptance|Box Hint|Connect Rules|Context Menu|Drag Add|Edge Delete|Edge Drag|Gap Check|Resize Fit|Insert Edge|Insert Branch|E2E Workflow|E2E Chatflow|E2E Editor|E2E Branches|E2E Parallel|E2E Human Input|E2E Agent|E2E Layout) \d+$/

export default async function globalTeardown() {
  const api = await request.newContext()
  let removed = 0
  let failed = 0
  try {
    const response = await api.get(`${API}/apps`)
    if (!response.ok()) {
      console.warn(`[e2e] 清理跳过：应用列表返回 ${response.status()}`)
      return
    }
    const apps: Array<{ id: string; name: string }> = await response.json()
    for (const app of apps.filter((item) => TEST_APP.test(item.name))) {
      const deleted = await api.delete(`${API}/apps/${app.id}`)
      if (deleted.ok()) removed += 1
      else if (deleted.status() !== 404) {
        failed += 1
        console.warn(`[e2e] 清理失败：${app.name} (${deleted.status()})`)
      }
    }
    console.log(`[e2e] 清理测试应用 ${removed} 个${failed ? `，失败 ${failed} 个` : ''}`)
  } catch (error) {
    console.warn(`[e2e] 清理未完成：${error instanceof Error ? error.message : String(error)}`)
  } finally {
    await api.dispose()
  }
}
