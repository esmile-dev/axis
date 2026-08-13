<script setup lang="ts">
import { BookOpen, Loader2, Send, Sparkles, X } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Textarea } from '@/components/ui/textarea'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

/**
 * 跨条目 RAG 问答面板（knowledge-ask-rag）：不选中条目即可用，面向全库提问。
 * - POST /api/knowledge/ask（SSE：sources 帧（首帧，引用来源 JSON）→ token 帧 → done 帧）
 * - 两段式 RAG：后端混合检索 top-5 → 全文装配 → 生成带 [n] 引用的回答
 * - stateless：每次提问独立，不加载/保存会话历史
 * 点来源卡片 emit('select') 由父级选中对应条目，形成「全局问 → 定位原文」漏斗。
 */
const emit = defineEmits<{
  close: []
  select: [itemId: string]
}>()

interface AskSource {
  n: number
  itemId: string
  title: string
}

interface AskMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources?: AskSource[]
  streaming?: boolean
  error?: boolean
}

const messages = ref<AskMessage[]>([])
const sending = ref(false)
const input = ref('')
const listRef = ref<HTMLElement | null>(null)

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

async function send(question: string) {
  messages.value.push({ id: crypto.randomUUID(), role: 'user', content: question })
  // 用 reactive 包装，后续流式增量修改才能触发视图更新
  const assistant = reactive<AskMessage>({
    id: crypto.randomUUID(),
    role: 'assistant',
    content: '',
    sources: [],
    streaming: true
  })
  messages.value.push(assistant)
  sending.value = true

  try {
    const { public: { apiBase } } = useRuntimeConfig()
    const response = await fetch(`${apiBase}/api/knowledge/ask`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question })
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
          if (event.type === 'sources') {
            assistant.sources = JSON.parse(event.json)
          } else if (event.type === 'token') {
            assistant.content += event.text
          }
        } catch {
          // 忽略无法解析的帧
        }
      }
    }
  } catch (e) {
    console.error('Knowledge ask request failed', e)
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
  <!-- 右侧固定面板：不遮挡左侧列表，点来源卡片后面板保持打开 -->
  <div class="fixed top-0 right-0 h-screen w-full sm:w-[440px] bg-background border-l border-border/40 shadow-2xl z-40 flex flex-col">
    <!-- 面板头 -->
    <div class="flex items-center justify-between px-4 py-3 border-b border-border/40 shrink-0">
      <div class="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <Sparkles class="w-3.5 h-3.5" />
        问知识库
        <span class="font-normal">· 跨全库检索，答案标注来源</span>
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
            v-if="messages.length === 0"
            class="flex flex-col items-center justify-center py-10 text-center text-muted-foreground"
          >
            <Sparkles class="w-6 h-6 mb-3 opacity-30" />
            <p class="text-xs">面向整个知识库提问，答案只依据库中内容生成并标注来源</p>
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
              class="max-w-[92%] bg-secondary/30 rounded-2xl px-3.5 py-2.5 text-sm"
              :class="{ 'border border-destructive/40': m.error }"
            >
              <MarkdownRenderer v-if="m.content" :content="m.content" />
              <div
                v-if="m.streaming"
                class="flex items-center gap-2 text-muted-foreground"
                :class="{ 'mt-2': m.content }"
              >
                <Loader2 class="w-3.5 h-3.5 animate-spin" />
                <span v-if="!m.content" class="text-xs">检索知识库中…</span>
              </div>

              <!-- 来源卡片：点击选中条目 -->
              <div v-if="m.sources?.length" class="mt-3 pt-2.5 border-t border-border/40 space-y-1">
                <div class="text-[11px] text-muted-foreground mb-1">来源</div>
                <button
                  v-for="s in m.sources"
                  :key="s.itemId"
                  class="w-full flex items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs text-muted-foreground hover:text-foreground hover:bg-secondary/50 transition-colors"
                  @click="emit('select', s.itemId)"
                >
                  <span class="shrink-0 inline-flex items-center justify-center w-4 h-4 rounded bg-primary/15 text-primary text-[10px]">{{ s.n }}</span>
                  <BookOpen class="w-3 h-3 shrink-0" />
                  <span class="truncate">{{ s.title }}</span>
                </button>
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
          placeholder="向整个知识库提问，Enter 发送…"
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
