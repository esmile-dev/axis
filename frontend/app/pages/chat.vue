<script setup lang="ts">
import { Send, Loader2, Plus, Wrench, Sparkles, Trash2, Brain, MessageSquare, AlertTriangle, Square, RotateCcw } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Textarea } from '@/components/ui/textarea'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

const {
  conversations, activeId, messages, memories, sending, loadingHistory, pendingConfirm,
  init, selectConversation, newConversation, deleteConversation, send, retryLastFailed, stop,
  respondConfirm, deleteMemory
} = useChat()

// 仅最后一条失败消息展示重试入口
const lastMessageId = computed(() => messages.value[messages.value.length - 1]?.id)

const input = ref('')
const listRef = ref<HTMLElement | null>(null)
const showMemories = ref(false)

const examples = [
  '帮我在 Inbox 记一条灵感：给聊天框加上快捷键',
  '列出所有未完成的 Issue',
  '记住：我喜欢用 TypeScript 写前端',
]

onMounted(async () => {
  await init()
  await scrollToBottom()
})

// 流式输出时保持滚动到底部
watch(messages, () => scrollToBottom(), { deep: true })

async function scrollToBottom() {
  await nextTick()
  const viewport = listRef.value?.querySelector('[data-reka-scroll-area-viewport]')
  if (viewport) {
    viewport.scrollTop = viewport.scrollHeight
  }
}

function handleSend() {
  const text = input.value
  if (!text.trim() || sending.value) return
  input.value = ''
  send(text)
}

function handleKeydown(e: KeyboardEvent) {
  // 输入法组词中（拼音未上屏）按 Enter 是确认候选词，不触发发送
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    handleSend()
  }
}

function handleExample(text: string) {
  if (sending.value) return
  send(text)
}

function formatTime(iso: string) {
  const date = new Date(iso)
  const now = new Date()
  const sameDay = date.toDateString() === now.toDateString()
  return sameDay
    ? date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}
</script>

<template>
  <div class="h-full flex">
    <!-- 会话列表 -->
    <aside class="w-60 shrink-0 border-r border-border bg-card/20 flex flex-col">
      <div class="p-3 border-b border-border">
        <Button variant="outline" size="sm" class="w-full" :disabled="sending" @click="newConversation">
          <Plus class="w-4 h-4 mr-2" />
          新会话
        </Button>
      </div>
      <ScrollArea class="flex-1">
        <div class="p-2 space-y-0.5">
          <div
            v-for="c in conversations"
            :key="c.id"
            class="group flex items-center gap-2 px-3 py-2 rounded-md text-sm cursor-pointer transition-all hover:bg-accent"
            :class="{ 'bg-accent': c.id === activeId }"
            @click="selectConversation(c.id)"
          >
            <MessageSquare class="w-3.5 h-3.5 shrink-0 text-muted-foreground" />
            <span class="flex-1 truncate">{{ c.title }}</span>
            <span class="text-[10px] text-muted-foreground group-hover:hidden">{{ formatTime(c.updatedAt) }}</span>
            <button
              class="hidden group-hover:block text-muted-foreground hover:text-destructive"
              @click.stop="deleteConversation(c.id)"
            >
              <Trash2 class="w-3.5 h-3.5" />
            </button>
          </div>
          <div v-if="conversations.length === 0" class="px-3 py-8 text-center text-xs text-muted-foreground">
            还没有历史会话
          </div>
        </div>
      </ScrollArea>
    </aside>

    <!-- 聊天主区 -->
    <div class="flex-1 min-w-0 flex flex-col">
      <header class="px-8 py-5 border-b border-border bg-card/30 backdrop-blur-md flex items-center justify-between shrink-0">
        <div>
          <h1 class="text-xl font-semibold tracking-tight">AI 助手</h1>
          <p class="text-xs text-muted-foreground mt-1">用自然语言操作 Inbox / Issue / Project / Knowledge</p>
        </div>
        <Button variant="outline" size="sm" @click="showMemories = true">
          <Brain class="w-4 h-4 mr-2" />
          长期记忆
          <span v-if="memories.length" class="ml-1 text-xs text-muted-foreground">({{ memories.length }})</span>
        </Button>
      </header>

      <div ref="listRef" class="flex-1 min-h-0">
        <ScrollArea class="h-full px-8">
          <div class="max-w-3xl mx-auto py-6 space-y-6">
            <!-- 空状态 -->
            <div v-if="messages.length === 0 && !loadingHistory" class="flex flex-col items-center justify-center py-24 text-center">
              <div class="w-12 h-12 rounded-2xl bg-primary/10 flex items-center justify-center text-primary mb-4">
                <Sparkles class="w-6 h-6" />
              </div>
              <h2 class="text-lg font-semibold">有什么可以帮你的？</h2>
              <p class="text-sm text-muted-foreground mt-2 mb-8">试试这些指令：</p>
              <div class="w-full max-w-md space-y-2">
                <button
                  v-for="ex in examples"
                  :key="ex"
                  class="w-full text-left px-4 py-3 rounded-xl bg-secondary/30 hover:bg-secondary/50 border border-border/40 text-sm text-muted-foreground hover:text-foreground transition-all"
                  @click="handleExample(ex)"
                >
                  {{ ex }}
                </button>
              </div>
            </div>

            <!-- 历史加载中 -->
            <div v-if="loadingHistory" class="flex justify-center py-16 text-muted-foreground">
              <Loader2 class="w-5 h-5 animate-spin" />
            </div>

            <!-- 消息列表 -->
            <div
              v-for="m in messages"
              :key="m.id"
              class="flex"
              :class="m.role === 'user' ? 'justify-end' : 'justify-start'"
            >
              <!-- 用户消息 -->
              <div
                v-if="m.role === 'user'"
                class="max-w-[80%] bg-primary/10 rounded-2xl px-4 py-3 text-sm whitespace-pre-wrap break-words"
              >
                {{ m.content }}
              </div>

              <!-- 助手消息 -->
              <div v-else class="max-w-[85%] space-y-2">
                <div v-if="m.toolCalls.length" class="space-y-1 px-1">
                  <div
                    v-for="(t, i) in m.toolCalls"
                    :key="i"
                    class="flex items-center gap-2 text-xs text-muted-foreground"
                  >
                    <Wrench class="w-3 h-3 shrink-0" />
                    <span>调用工具：{{ t }}</span>
                  </div>
                </div>

                <!-- 危险操作人工确认卡片（confirm 帧挂起期间展示） -->
                <div
                  v-if="m.streaming && pendingConfirm"
                  class="rounded-xl border border-destructive/40 bg-destructive/5 px-4 py-3 space-y-3"
                >
                  <div class="flex items-start gap-2 text-sm">
                    <AlertTriangle class="w-4 h-4 mt-0.5 shrink-0 text-destructive" />
                    <div class="min-w-0">
                      <p class="font-medium">待确认：{{ pendingConfirm.action }}</p>
                      <p class="text-xs text-muted-foreground mt-0.5 break-words whitespace-pre-wrap">{{ pendingConfirm.detail }}</p>
                    </div>
                  </div>
                  <div class="flex items-center gap-2 pl-6">
                    <Button size="sm" variant="destructive" @click="respondConfirm(true)">确认执行</Button>
                    <Button size="sm" variant="outline" @click="respondConfirm(false)">取消</Button>
                  </div>
                </div>

                <div
                  class="bg-secondary/30 rounded-2xl px-4 py-3 text-sm"
                  :class="{ 'border border-destructive/40': m.error }"
                >
                  <MarkdownRenderer v-if="m.content" :content="m.content" />
                  <div
                    v-if="m.streaming"
                    class="flex items-center gap-2 text-muted-foreground"
                    :class="{ 'mt-2': m.content }"
                  >
                    <Loader2 class="w-3.5 h-3.5 animate-spin" />
                    <span v-if="!m.content" class="text-xs">{{ pendingConfirm ? '等待确认…' : '思考中…' }}</span>
                  </div>
                </div>

                <!-- 失败消息的手动重试（仅最后一条出错且不在流式中时展示） -->
                <Button
                  v-if="m.error && !m.streaming && m.id === lastMessageId"
                  variant="outline"
                  size="sm"
                  class="h-7 px-2.5 text-xs text-muted-foreground"
                  @click="retryLastFailed"
                >
                  <RotateCcw class="w-3 h-3 mr-1" />
                  重试
                </Button>
              </div>
            </div>
          </div>
        </ScrollArea>
      </div>

      <!-- 输入区 -->
      <div class="shrink-0 border-t border-border bg-card/30 px-8 py-4">
        <div class="max-w-3xl mx-auto flex items-end gap-3">
          <Textarea
            v-model="input"
            rows="1"
            placeholder="输入消息，Enter 发送，Shift+Enter 换行…"
            class="flex-1 min-h-[44px] max-h-40 resize-none bg-secondary/50 border-none"
            :disabled="sending"
            @keydown="handleKeydown"
          />
          <Button v-if="sending" size="icon" variant="outline" title="停止生成" @click="stop">
            <Square class="w-4 h-4" />
          </Button>
          <Button v-else size="icon" :disabled="!input.trim()" @click="handleSend">
            <Send class="w-4 h-4" />
          </Button>
        </div>
      </div>
    </div>

    <!-- 长期记忆管理 -->
    <Dialog v-model:open="showMemories">
      <DialogContent class="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>长期记忆</DialogTitle>
        </DialogHeader>
        <p class="text-xs text-muted-foreground">
          跨会话生效的记忆，由 AI 在对话中通过 saveMemory 工具主动保存，会注入到每次对话的上下文中。
        </p>
        <ScrollArea class="max-h-80">
          <div class="space-y-2 pr-3">
            <div
              v-for="m in memories"
              :key="m.id"
              class="group flex items-start gap-2 px-3 py-2 rounded-lg bg-secondary/30 text-sm"
            >
              <Brain class="w-3.5 h-3.5 mt-0.5 shrink-0 text-muted-foreground" />
              <span class="flex-1 break-words">{{ m.content }}</span>
              <button
                class="opacity-0 group-hover:opacity-100 text-muted-foreground hover:text-destructive transition-opacity"
                @click="deleteMemory(m.id)"
              >
                <Trash2 class="w-3.5 h-3.5" />
              </button>
            </div>
            <div v-if="memories.length === 0" class="py-8 text-center text-xs text-muted-foreground">
              还没有长期记忆。对 AI 说「记住：…」即可保存。
            </div>
          </div>
        </ScrollArea>
      </DialogContent>
    </Dialog>
  </div>
</template>
