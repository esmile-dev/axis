/**
 * API 测试 - 健康检查
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest'

const BASE_URL = 'http://localhost:3000'

describe('Health API', () => {
  it('应该返回正常状态', async () => {
    const res = await fetch(`${BASE_URL}/api/health`)
    const data = await res.json()

    expect(data.status).toBe('ok')
    expect(data.timestamp).toBeDefined()
  })
})