<script setup lang="ts">
import { Loader2, Send, Sparkles, X } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Textarea } from '@/components/ui/textarea'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

/**
 * 知识条目 AI 问答面板（FR-009）：针对单条目的 source-grounded 流式问答。
 * - POST /api/knowledge/{id}/chat（SSE：token/done 帧，与 /api/agent/chat 同协议），
 *   fetch + ReadableStream（$fetch 不支持流式）
 * - 历史按条目持久化在 chat_message，conversationId = `knowledge-${itemId}`，
 *   打开面板时经 /api/agent/conversations/{id}/messages 加载
 * 条目隔离由父级 :key="itemId" 保证：切条目重挂载即清空重载。
 */
const props = defineProps<{
  itemId: string
}>()

const emit = defineEmits<{
  close: []
}>()

interface QaMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  streaming?: boolean
  error?: boolean
}

interface ChatMessageRow {
  id: string
  role: string
  content: string
}

const api = useApi()
const conversationId = computed(() => `knowledge-${props.itemId}`)

const messages = ref<QaMessage[]>([])
const sending = ref(false)
const loadingHistory = ref(false)
const input = ref('')
const listRef = ref<HTMLElement | null>(null)

onMounted(async () => {
  loadingHistory.value = true
  try {
    const rows = await api<ChatMessageRow[]>(`/api/agent/conversations/${conversationId.value}/messages`)
    messages.value = rows.map(r => ({
      id: r.id,
      role: r.role === 'USER' ? 'user' : 'assistant',
      content: r.content
    }))
  } catch (e) {
    console.error('Failed to load QA history', e)
  } finally {
    loadingHistory.value = false
  }
  await scrollToBottom()
})

watch(messages, () => scrollToBottom(), { deep: true })

async function scrollToBottom() {
  await nextTick()
  const viewport = listRef.value?.querySelector('[data-reka-scroll-area-viewport]')
  if (viewport) {
    viewport.scrollTop = viewport.scrollHeight
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function handleSend() {
  const text = input.value.trim()
  if (!text || sending.value) return
  input.value = ''
  send(text)
}

async function send(content: string) {
  messages.value.push({ id: crypto.randomUUID(), role: 'user', content })
  // 用 reactive 包装，后续流式增量修改才能触发视图更新
  const assistant = reactive<QaMessage>({
    id: crypto.randomUUID(),
    role: 'assistant',
    content: '',
    streaming: true
  })
  messages.value.push(assistant)
  sending.value = true

  try {
    const { public: { apiBase } } = useRuntimeConfig()
    const response = await fetch(`${apiBase}/api/knowledge/${props.itemId}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: content })
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
          }
        } catch {
          // 忽略无法解析的帧
        }
      }
    }
  } catch (e) {
    console.error('Knowledge QA request failed', e)
    assistant.error = true
    if (!assistant.content) {
      assistant.content = '请求失败，请确认后端服务已启动、AI 配置可用后重试。'
    }
  } finally {
    assistant.streaming = false
    sending.value = false
  }
}
</script>

<template>
  <!-- 高度由父级决定（父级传 h-[45%]）：根节点不写 h-full，避免类冲突时 100% 顶破布局 -->
  <div class="flex flex-col min-h-0">
    <!-- 面板头 -->
    <div class="flex items-center justify-between px-4 py-2 border-b border-border/40 shrink-0">
      <div class="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <Sparkles class="w-3.5 h-3.5" />
        AI 问答
        <span class="font-normal">· 只依据本条目内容回答</span>
      </div>
      <button class="text-muted-foreground hover:text-foreground transition-colors" @click="emit('close')">
        <X class="w-3.5 h-3.5" />
      </button>
    </div>

    <!-- 消息区 -->
    <div ref="listRef" class="flex-1 min-h-0">
      <ScrollArea class="h-full px-4">
        <div class="py-4 space-y-4">
          <!-- 空态 -->
          <div
            v-if="messages.length === 0 && !loadingHistory"
            class="flex flex-col items-center justify-center py-10 text-center text-muted-foreground"
          >
            <Sparkles class="w-6 h-6 mb-3 opacity-30" />
            <p class="text-xs">就本文内容提问，答案只依据该条目生成</p>
          </div>

          <!-- 历史加载中 -->
          <div v-if="loadingHistory" class="flex justify-center py-8 text-muted-foreground">
            <Loader2 class="w-4 h-4 animate-spin" />
          </div>

          <div
            v-for="m in messages"
            :key="m.id"
            class="flex"
            :class="m.role === 'user' ? 'justify-end' : 'justify-start'"
          >
            <div
              v-if="m.role === 'user'"
              class="max-w-[80%] bg-primary/10 rounded-2xl px-3.5 py-2.5 text-sm whitespace-pre-wrap break-words"
            >
              {{ m.content }}
            </div>
            <div
              v-else
              class="max-w-[85%] bg-secondary/30 rounded-2xl px-3.5 py-2.5 text-sm"
              :class="{ 'border border-destructive/40': m.error }"
            >
              <MarkdownRenderer v-if="m.content" :content="m.content" />
              <div
                v-if="m.streaming"
                class="flex items-center gap-2 text-muted-foreground"
                :class="{ 'mt-2': m.content }"
              >
                <Loader2 class="w-3.5 h-3.5 animate-spin" />
                <span v-if="!m.content" class="text-xs">思考中…</span>
              </div>
            </div>
          </div>
        </div>
      </ScrollArea>
    </div>

    <!-- 输入区 -->
    <div class="shrink-0 border-t border-border/40 px-4 py-3">
      <div class="flex items-end gap-2">
        <Textarea
          v-model="input"
          rows="1"
          placeholder="就本文内容提问，Enter 发送…"
          class="flex-1 min-h-[38px] max-h-32 resize-none bg-secondary/50 border-none text-sm"
          :disabled="sending"
          @keydown="handleKeydown"
        />
        <Button size="icon" class="h-9 w-9" :disabled="!input.trim() || sending" @click="handleSend">
          <Send class="w-3.5 h-3.5" />
        </Button>
      </div>
    </div>
  </div>
</template>
