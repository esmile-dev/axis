/**
 * API 测试 - Projects CRUD
 */
import { describe, it, expect, beforeEach } from 'vitest'

const BASE_URL = 'http://localhost:3000'

describe('Projects API', () => {
  beforeEach(async () => {
    // 清理测试数据
    const res = await fetch(`${BASE_URL}/api/projects`)
    const projects = await res.json()

    for (const project of projects) {
      if (project.name.startsWith('[TEST]')) {
        await fetch(`${BASE_URL}/api/projects/${project.id}`, { method: 'DELETE' })
      }
    }
  })

  describe('创建 Project', () => {
    it('应该创建新 Project', async () => {
      const res = await fetch(`${BASE_URL}/api/projects`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: '[TEST] 测试项目', description: '测试描述' })
      })
      const data = await res.json()

      expect(res.status).toBe(200)
      expect(data.id).toBeDefined()
      expect(data.name).toBe('[TEST] 测试项目')
      expect(data.status).toBe('PLANNING')
    })
  })

  describe('查询 Projects', () => {
    it('应该返回所有 Projects（含 issueCount）', async () => {
      // 创建测试项目
      await fetch(`${BASE_URL}/api/projects`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: '[TEST] 项目A' })
      })

      const res = await fetch(`${BASE_URL}/api/projects`)
      const data = await res.json()

      expect(Array.isArray(data)).toBe(true)
      expect(data.length).toBeGreaterThan(0)
      // 应该有 _count.issues 字段
      expect(data[0]._count).toBeDefined()
      expect(data[0]._count.issues).toBeDefined()
    })
  })

  describe('更新 Project', () => {
    it('应该更新项目状态', async () => {
      // 创建
      const createRes = await fetch(`${BASE_URL}/api/projects`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: '[TEST] 状态测试项目' })
      })
      const created = await createRes.json()

      // 更新
      const updateRes = await fetch(`${BASE_URL}/api/projects/${created.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'ACTIVE' })
      })
      const updated = await updateRes.json()

      expect(updateRes.status).toBe(200)
      expect(updated.status).toBe('ACTIVE')
    })
  })

  describe('删除 Project', () => {
    it('应该删除 Project', async () => {
      // 创建
      const createRes = await fetch(`${BASE_URL}/api/projects`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: '[TEST] 待删除项目' })
      })
      const created = await createRes.json()

      // 删除
      const deleteRes = await fetch(`${BASE_URL}/api/projects/${created.id}`, {
        method: 'DELETE'
      })

      expect(deleteRes.status).toBe(200)

      // 验证已删除
      const listRes = await fetch(`${BASE_URL}/api/projects`)
      const projects = await listRes.json()
      expect(projects.find(p => p.id === created.id)).toBeUndefined()
    })
  })
})