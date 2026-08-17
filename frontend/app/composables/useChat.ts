import { reactive } from 'vue'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  toolCalls: string[]
  streaming?: boolean
  error?: boolean
}

export interface ChatConversation {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface ChatLongMemory {
  id: string
  content: string
  createdAt: string
}

/** 危险操作人工确认请求（后端 SSE confirm 帧） */
export interface PendingConfirm {
  confirmId: string
  action: string
  detail: string
}

interface ChatMessageRow {
  id: string
  role: string
  content: string
}

/**
 * 聊天状态管理 — 对接后端：
 * - POST /api/agent/chat（SSE：token/tool/confirm/error/done 帧），fetch + ReadableStream（$fetch 不支持流式）
 * - POST /api/agent/confirm/:id 危险操作人工确认回调（confirm 帧挂起期间放行/拒绝）
 * - GET/DELETE /api/agent/conversations[/:id/messages] 会话历史（短期记忆，服务端持久化）
 * - GET/DELETE /api/agent/memories 长期记忆
 */
export function useChat() {
  const conversations = useState<ChatConversation[]>('chat-conversations', () => [])
  const activeId = useState<string>('chat-active-id', () => '')
  const messages = useState<ChatMessage[]>('chat-messages', () => [])
  const memories = useState<ChatLongMemory[]>('chat-memories', () => [])
  const sending = useState<boolean>('chat-sending', () => false)
  const loadingHistory = useState<boolean>('chat-loading-history', () => false)
  const initialized = useState<boolean>('chat-initialized', () => false)
  const pendingConfirm = useState<PendingConfirm | null>('chat-pending-confirm', () => null)

  // 当前流式请求的取消控制器（stop() 中止）
  let abortController: AbortController | null = null

  const api = useApi()

  async function loadConversations() {
    try {
      conversations.value = await api<ChatConversation[]>('/api/agent/conversations')
    } catch (e) {
      console.error('Failed to load conversations', e)
    }
  }

  async function selectConversation(id: string) {
    if (sending.value || id === activeId.value) return
    activeId.value = id
    loadingHistory.value = true
    messages.value = []
    try {
      const rows = await api<ChatMessageRow[]>(`/api/agent/conversations/${id}/messages`)
      messages.value = rows.map(r => ({
        id: r.id,
        role: r.role === 'USER' ? 'user' : 'assistant',
        content: r.content,
        toolCalls: []
      }))
    } catch (e) {
      console.error('Failed to load conversation messages', e)
    } finally {
      loadingHistory.value = false
    }
  }

  /** 新会话：仅重置前端状态，会话记录在首条消息发送后由后端落库 */
  function newConversation() {
    if (sending.value) return
    activeId.value = crypto.randomUUID()
    messages.value = []
  }

  async function deleteConversation(id: string) {
    try {
      await api(`/api/agent/conversations/${id}`, { method: 'DELETE' })
      conversations.value = conversations.value.filter(c => c.id !== id)
      if (activeId.value === id) {
        newConversation()
      }
    } catch (e) {
      console.error('Failed to delete conversation', e)
    }
  }

  async function send(text: string) {
    const content = text.trim()
    if (!content || sending.value) return
    messages.value.push({ id: crypto.randomUUID(), role: 'user', content, toolCalls: [] })
    await doSend(content)
  }

  /** 手动重试：移除失败的 assistant 占位，复用最近一条 user 消息重发；
   *  retry 标志让后端先去掉失败时已落库的同一 user 消息，避免历史双份 */
  async function retryLastFailed() {
    const last = messages.value[messages.value.length - 1]
    if (!last || last.role !== 'assistant' || !last.error || sending.value) return
    const lastUser = messages.value[messages.value.length - 2]
    if (!lastUser || lastUser.role !== 'user') return
    messages.value.pop()
    await doSend(lastUser.content, true)
  }

  async function doSend(content: string, retry = false) {
    if (!activeId.value) {
      activeId.value = crypto.randomUUID()
    }
    // 新会话：后端会在首轮结束后异步用 LLM 生成语义标题，需要延迟再刷一次列表
    const isNewConversation = !conversations.value.some(c => c.id === activeId.value)

    // 用 reactive 包装，后续流式增量修改才能触发视图更新
    const assistant = reactive<ChatMessage>({
      id: crypto.randomUUID(),
      role: 'assistant',
      content: '',
      toolCalls: [],
      streaming: true
    })
    messages.value.push(assistant)
    sending.value = true

    try {
      const { public: { apiBase } } = useRuntimeConfig()
      abortController = new AbortController()
      const response = await fetch(`${apiBase}/api/agent/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: content, sessionId: activeId.value, retry }),
        signal: abortController.signal
      })
      if (!response.ok || !response.body) {
        throw new Error(`HTTP ${response.status}`)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''
        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          const payload = line.slice(5).trim()
          if (!payload) continue
          try {
            const event = JSON.parse(payload)
            if (event.type === 'token') {
              assistant.content += event.text
            } else if (event.type === 'tool') {
              assistant.toolCalls.push(event.label)
            } else if (event.type === 'confirm') {
              pendingConfirm.value = {
                confirmId: event.confirmId,
                action: event.action,
                detail: event.detail
              }
            } else if (event.type === 'error') {
              // 后端 LLM 流失败帧：标记错误样式，失败原因直接展示（后随 done 帧正常收尾）
              assistant.error = true
              assistant.content += (assistant.content ? '\n\n' : '') + `⚠️ ${event.message}`
            }
          } catch {
            // 忽略无法解析的帧
          }
        }
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        // 用户主动停止：保留已生成的部分内容，不视为错误
        if (!assistant.content && assistant.toolCalls.length === 0) {
          assistant.content = '*已停止生成*'
        }
      } else {
        console.error('Chat request failed', e)
        assistant.error = true
        if (!assistant.content) {
          assistant.content = '请求失败，请确认后端服务已启动、AI 配置可用后重试。'
        } else {
          // 已有部分内容时的断流（如网络中断）：保留已生成内容并明示不完整
          assistant.content += '\n\n⚠️ 连接中断，以上内容可能不完整'
        }
      }
    } finally {
      abortController = null
      assistant.streaming = false
      sending.value = false
      pendingConfirm.value = null
      // 刷新会话列表（新会话落库 / 标题与排序更新）
      await loadConversations()
      if (isNewConversation) {
        // 等后端异步 LLM 标题落库后再刷一次（截断兜底 → 语义标题）
        setTimeout(() => loadConversations(), 3000)
      }
    }
  }

  /** 中止当前流式生成（前端断开 SSE，后端流中断按约定收尾） */
  function stop() {
    abortController?.abort()
  }

  /** 危险操作确认回调：放行/拒绝后端挂起中的删除类 tool，卡片乐观消失 */
  async function respondConfirm(approved: boolean) {
    const pending = pendingConfirm.value
    if (!pending) return
    pendingConfirm.value = null
    try {
      await api(`/api/agent/confirm/${pending.confirmId}`, { method: 'POST', body: { approved } })
    } catch (e) {
      console.error('Failed to submit confirmation', e)
    }
  }

  async function loadMemories() {
    try {
      memories.value = await api<ChatLongMemory[]>('/api/agent/memories')
    } catch (e) {
      console.error('Failed to load memories', e)
    }
  }

  async function deleteMemory(id: string) {
    try {
      await api(`/api/agent/memories/${id}`, { method: 'DELETE' })
      memories.value = memories.value.filter(m => m.id !== id)
    } catch (e) {
      console.error('Failed to delete memory', e)
    }
  }

  /** 页面 onMounted 调用一次：恢复最近会话 + 加载长期记忆 */
  async function init() {
    if (initialized.value) return
    initialized.value = true
    await loadConversations()
    if (conversations.value.length > 0) {
      await selectConversation(conversations.value[0].id)
    } else {
      newConversation()
    }
    await loadMemories()
  }

  return {
    conversations, activeId, messages, memories, sending, loadingHistory, pendingConfirm,
    init, selectConversation, newConversation, deleteConversation, send, retryLastFailed, stop,
    respondConfirm, loadMemories, deleteMemory
  }
}
