/**
 * API 测试 - Inbox CRUD
 */
import { describe, it, expect, beforeAll, afterAll, beforeEach } from 'vitest'

const BASE_URL = 'http://localhost:3000'

// 存储测试数据 ID
let testItemIds: string[] = []

describe('Inbox API', () => {
  // 测试前清空数据
  beforeEach(async () => {
    // 获取所有测试数据并删除
    const res = await fetch(`${BASE_URL}/api/inbox`)
    const items = await res.json()

    for (const item of items) {
      if (item.content.startsWith('[TEST]')) {
        await fetch(`${BASE_URL}/api/inbox/${item.id}`, { method: 'DELETE' })
      }
    }
  })

  // 所有测试结束后再次清理
  afterAll(async () => {
    const res = await fetch(`${BASE_URL}/api/inbox`)
    const items = await res.json()

    for (const item of items) {
      if (item.content.startsWith('[TEST]')) {
        await fetch(`${BASE_URL}/api/inbox/${item.id}`, { method: 'DELETE' })
      }
    }
  })

  describe('创建 InboxItem', () => {
    it('应该创建新的 InboxItem', async () => {
      const res = await fetch(`${BASE_URL}/api/inbox`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: '[TEST] 新测试项' })
      })
      const data = await res.json()

      expect(res.status).toBe(200)
      expect(data.id).toBeDefined()
      expect(data.content).toBe('[TEST] 新测试项')
      expect(data.status).toBe('TODO')

      testItemIds.push(data.id)
    })
  })

  describe('查询 InboxItem', () => {
    it('应该返回所有 InboxItems', async () => {
      // 先创建测试数据
      await fetch(`${BASE_URL}/api/inbox`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: '[TEST] 查询测试项' })
      })

      const res = await fetch(`${BASE_URL}/api/inbox`)
      const data = await res.json()

      expect(Array.isArray(data)).toBe(true)
      expect(data.length).toBeGreaterThan(0)
    })

    it('应该按状态筛选', async () => {
      // 创建 TODO 状态项
      await fetch(`${BASE_URL}/api/inbox`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: '[TEST] TODO项' })
      })

      const res = await fetch(`${BASE_URL}/api/inbox?status=TODO`)
      const data = await res.json()

      expect(Array.isArray(data)).toBe(true)
      data.forEach(item => {
        expect(item.status).toBe('TODO')
      })
    })

    it('应该按内容搜索', async () => {
      // 创建特殊内容项
      await fetch(`${BASE_URL}/api/inbox`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: '[TEST] 特殊关键词ABC' })
      })

      const res = await fetch(`${BASE_URL}/api/inbox?search=ABC`)
      const data = await res.json()

      expect(Array.isArray(data)).toBe(true)
      expect(data.some(item => item.content.includes('ABC'))).toBe(true)
    })
  })

  describe('更新 InboxItem', () => {
    it('应该更新状态为 DONE', async () => {
      // 先创建
      const createRes = await fetch(`${BASE_URL}/api/inbox`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: '[TEST] 待完成项' })
      })
      const created = await createRes.json()

      // 更新状态
      const updateRes = await fetch(`${BASE_URL}/api/inbox/${created.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'DONE' })
      })
      const updated = await updateRes.json()

      expect(updateRes.status).toBe(200)
      expect(updated.status).toBe('DONE')
    })
  })

  describe('删除 InboxItem', () => {
    it('应该删除 InboxItem', async () => {
      // 先创建
      const createRes = await fetch(`${BASE_URL}/api/inbox`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: '[TEST] 待删除项' })
      })
      const created = await createRes.json()

      // 删除
      const deleteRes = await fetch(`${BASE_URL}/api/inbox/${created.id}`, {
        method: 'DELETE'
      })

      expect(deleteRes.status).toBe(200)

      // 验证已删除
      const listRes = await fetch(`${BASE_URL}/api/inbox`)
      const items = await listRes.json()
      expect(items.find(i => i.id === created.id)).toBeUndefined()
    })
  })
})