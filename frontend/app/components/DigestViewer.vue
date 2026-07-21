<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Calendar, RefreshCw, AlertCircle, Loader2, Newspaper } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

interface DigestEntry {
  date: string
  content: string
  articleCount: number
}

interface TriggerResponse {
  executed: boolean
  message: string
  articleCount: number
}

const api = useApi()

const recent = ref<DigestEntry[]>([])
const selectedDate = ref<string | null>(null)
const isLoading = ref(false)
const isRegenerating = ref(false)
const error = ref<string | null>(null)
const statusMessage = ref<string | null>(null)

const today = new Date().toISOString().slice(0, 10)

const selected = computed(() =>
  recent.value.find(e => e.date === selectedDate.value) || null
)

function formatChipLabel(date: string): string {
  if (date === today) return '今日'
  // "2026-07-19" → "07-19"
  return date.slice(5)
}

onMounted(loadRecent)

async function loadRecent() {
  isLoading.value = true
  try {
    const data = await api<DigestEntry[]>('/api/v1/digest/recent?days=7')
    recent.value = data
    if (data.length > 0 && !data.some(e => e.date === selectedDate.value)) {
      selectedDate.value = data[0].date
    }
  } catch (err: any) {
    error.value = err?.message || '加载 digest 失败'
    recent.value = []
  } finally {
    isLoading.value = false
  }
}

async function regenerate() {
  isRegenerating.value = true
  statusMessage.value = null
  error.value = null
  try {
    const result = await api<TriggerResponse>('/api/v1/digest/trigger', { method: 'POST' })
    statusMessage.value = result.executed
      ? `已生成今日 digest（${result.articleCount} 篇）`
      : `今日 digest 已生成（${result.articleCount} 篇）`
    await loadRecent()
  } catch (err: any) {
    error.value = err?.message || '生成 digest 失败'
  } finally {
    isRegenerating.value = false
  }
}
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Header -->
    <div class="px-6 py-3 border-b border-border/40 flex items-center gap-3 flex-shrink-0">
      <div class="flex items-center gap-2">
        <Newspaper class="w-4 h-4 text-primary" />
        <span class="text-sm font-semibold tracking-tight">每日科技摘要</span>
      </div>

      <!-- Date chips -->
      <div v-if="recent.length > 0" class="flex-1 flex items-center gap-1 overflow-x-auto">
        <button
          v-for="entry in recent"
          :key="entry.date"
          class="px-2.5 py-1 rounded-md text-xs whitespace-nowrap transition-colors flex items-center gap-1.5"
          :class="selectedDate === entry.date
            ? 'bg-secondary/70 text-foreground font-medium'
            : 'text-muted-foreground hover:bg-secondary/30 hover:text-foreground'"
          @click="selectedDate = entry.date"
        >
          <span>{{ formatChipLabel(entry.date) }}</span>
          <span class="text-[10px] opacity-70">{{ entry.articleCount }}</span>
        </button>
      </div>
      <div v-else class="flex-1"></div>

      <!-- Regenerate -->
      <Button
        variant="outline"
        size="sm"
        class="h-7 px-2 text-xs bg-secondary/20 border-border/40"
        :disabled="isRegenerating"
        @click="regenerate"
      >
        <Loader2 v-if="isRegenerating" class="w-3.5 h-3.5 animate-spin" />
        <RefreshCw v-else class="w-3.5 h-3.5" />
      </Button>
    </div>

    <!-- Status / Error messages -->
    <div v-if="statusMessage" class="mx-6 mt-3 px-3 py-1.5 rounded-md bg-primary/10 border border-primary/20 text-xs text-primary">
      {{ statusMessage }}
    </div>
    <div v-if="error" class="mx-6 mt-3 px-3 py-1.5 rounded-md bg-destructive/10 border border-destructive/30 text-xs text-destructive flex items-start gap-2">
      <AlertCircle class="w-3.5 h-3.5 mt-0.5 flex-shrink-0" />
      <span>{{ error }}</span>
    </div>

    <!-- Content -->
    <ScrollArea class="flex-1">
      <div v-if="isLoading" class="flex items-center justify-center py-20">
        <Loader2 class="w-5 h-5 animate-spin text-muted-foreground" />
      </div>

      <div v-else-if="recent.length === 0" class="flex flex-col items-center justify-center py-24 text-sm text-muted-foreground">
        <Calendar class="w-10 h-10 mb-3 opacity-20" />
        <p>今日尚无 digest</p>
        <p class="text-xs mt-1 mb-4">点击下方按钮生成，或等待定时任务</p>
        <Button
          variant="outline"
          size="sm"
          class="bg-secondary/20 border-border/40"
          :disabled="isRegenerating"
          @click="regenerate"
        >
          <Loader2 v-if="isRegenerating" class="w-3.5 h-3.5 mr-1.5 animate-spin" />
          <Newspaper v-else class="w-3.5 h-3.5 mr-1.5" />
          {{ isRegenerating ? '生成中…' : '生成今日摘要' }}
        </Button>
      </div>

      <div v-else-if="selected" class="px-6 py-4">
        <MarkdownRenderer :content="selected.content" />
      </div>
    </ScrollArea>
  </div>
</template>