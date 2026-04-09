/**
 * API 测试 - Issues CRUD
 */
import { describe, it, expect, beforeEach } from 'vitest'

const BASE_URL = 'http://localhost:3000'

describe('Issues API', () => {
  beforeEach(async () => {
    // 清理测试数据
    const res = await fetch(`${BASE_URL}/api/issues`)
    const issues = await res.json()

    for (const issue of issues) {
      if (issue.title.startsWith('[TEST]')) {
        await fetch(`${BASE_URL}/api/issues/${issue.id}`, { method: 'DELETE' })
      }
    }
  })

  describe('创建 Issue', () => {
    it('应该创建新 Issue', async () => {
      const res = await fetch(`${BASE_URL}/api/issues`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: '[TEST] 测试 Issue',
          description: '测试描述',
          priority: 'HIGH',
          type: 'FEATURE'
        })
      })
      const data = await res.json()

      expect(res.status).toBe(200)
      expect(data.id).toBeDefined()
      expect(data.title).toBe('[TEST] 测试 Issue')
      expect(data.status).toBe('TODO')
      expect(data.priority).toBe('HIGH')
      expect(data.type).toBe('FEATURE')
    })

    it('应该创建带 projectId 的 Issue', async () => {
      // 先创建项目
      const projectRes = await fetch(`${BASE_URL}/api/projects`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: '[TEST] 关联项目' })
      })
      const project = await projectRes.json()

      // 创建关联 Issue
      const res = await fetch(`${BASE_URL}/api/issues`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: '[TEST] 关联 Issue',
          projectId: project.id
        })
      })
      const data = await res.json()

      expect(data.projectId).toBe(project.id)
    })
  })

  describe('查询 Issues', () => {
    it('应该返回所有 Issues', async () => {
      // 创建测试数据
      await fetch(`${BASE_URL}/api/issues`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: '[TEST] 查询测试' })
      })

      const res = await fetch(`${BASE_URL}/api/issues`)
      const data = await res.json()

      expect(Array.isArray(data)).toBe(true)
      expect(data.length).toBeGreaterThan(0)
    })

    it('应该按 projectId 筛选', async () => {
      // 创建项目
      const projectRes = await fetch(`${BASE_URL}/api/projects`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: '[TEST] 筛选项目' })
      })
      const project = await projectRes.json()

      // 创建关联 Issue
      await fetch(`${BASE_URL}/api/issues`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: '[TEST] 项目关联项',
          projectId: project.id
        })
      })

      const res = await fetch(`${BASE_URL}/api/issues?projectId=${project.id}`)
      const data = await res.json()

      expect(Array.isArray(data)).toBe(true)
      data.forEach(issue => {
        expect(issue.projectId).toBe(project.id)
      })
    })
  })

  describe('更新 Issue', () => {
    it('应该更新状态', async () => {
      // 创建
      const createRes = await fetch(`${BASE_URL}/api/issues`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: '[TEST] 状态测试' })
      })
      const created = await createRes.json()

      // 更新
      const updateRes = await fetch(`${BASE_URL}/api/issues/${created.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'IN_PROGRESS' })
      })
      const updated = await updateRes.json()

      expect(updateRes.status).toBe(200)
      expect(updated.status).toBe('IN_PROGRESS')
    })

    it('应该更新优先级', async () => {
      // 创建
      const createRes = await fetch(`${BASE_URL}/api/issues`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: '[TEST] 优先级测试' })
      })
      const created = await createRes.json()

      // 更新
      const updateRes = await fetch(`${BASE_URL}/api/issues/${created.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ priority: 'URGENT' })
      })
      const updated = await updateRes.json()

      expect(updated.priority).toBe('URGENT')
    })
  })

  describe('删除 Issue', () => {
    it('应该删除 Issue', async () => {
      // 创建
      const createRes = await fetch(`${BASE_URL}/api/issues`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: '[TEST] 待删除' })
      })
      const created = await createRes.json()

      // 删除
      const deleteRes = await fetch(`${BASE_URL}/api/issues/${created.id}`, {
        method: 'DELETE'
      })

      expect(deleteRes.status).toBe(200)

      // 验证已删除
      const listRes = await fetch(`${BASE_URL}/api/issues`)
      const issues = await listRes.json()
      expect(issues.find(i => i.id === created.id)).toBeUndefined()
    })
  })

  describe('Issue 详情', () => {
    it('应该返回 Issue 详情', async () => {
      // 创建
      const createRes = await fetch(`${BASE_URL}/api/issues`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: '[TEST] 详情测试',
          description: '详细描述内容'
        })
      })
      const created = await createRes.json()

      // 获取详情
      const detailRes = await fetch(`${BASE_URL}/api/issues/${created.id}`)
      const detail = await detailRes.json()

      expect(detailRes.status).toBe(200)
      expect(detail.id).toBe(created.id)
      expect(detail.title).toBe('[TEST] 详情测试')
      expect(detail.description).toBe('详细描述内容')
    })
  })

  describe('Issue 评论', () => {
    it('应该添加评论', async () => {
      // 创建 Issue
      const createRes = await fetch(`${BASE_URL}/api/issues`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: '[TEST] 评论测试' })
      })
      const created = await createRes.json()

      // 添加评论
      const commentRes = await fetch(`${BASE_URL}/api/issues/${created.id}/comments`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: '测试评论内容' })
      })
      const comment = await commentRes.json()

      expect(commentRes.status).toBe(200)
      expect(comment.content).toBe('测试评论内容')
      expect(comment.issueId).toBe(created.id)
    })
  })
})