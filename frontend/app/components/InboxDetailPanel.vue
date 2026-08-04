<script setup lang="ts">
import { CircleCheck, Circle, ArrowRightLeft, FolderPlus, Clock, ExternalLink, Newspaper, BookOpen } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'

type DigestCategory = 'AI_FRONTIER' | 'TECH_INDUSTRY' | 'FINANCE_TECH' | 'OTHER'
type ItemType = 'NOTE' | 'DIGEST'

interface DigestArticle {
  title: string
  summary: string
  link: string
  sourceName: string
  category: DigestCategory
  publishedAt: string
}

interface InboxItem {
  id: string
  content: string
  status: 'TODO' | 'DONE'
  type?: ItemType
  summary?: string | null
  longText?: string | null
  link?: string | null
  sourceName?: string | null
  category?: DigestCategory | null
  publishedAt?: string | null
  readAt?: string | null
  createdAt: string
  updatedAt: string
}

const props = defineProps<{
  item: InboxItem | null
}>()

const emit = defineEmits<{
  update: [id: string, data: { content?: string; status?: string }]
  convertToIssue: [id: string]
  convertToProject: [id: string]
  convertToKnowledge: [id: string]
  delete: [id: string]
}>()

const localContent = ref('')
let saveTimeout: ReturnType<typeof setTimeout> | null = null

// Sync local content when item changes
watch(() => props.item, (newItem) => {
  if (newItem) {
    localContent.value = newItem.content
  }
}, { immediate: true })

function queueSave() {
  if (saveTimeout) clearTimeout(saveTimeout)
  saveTimeout = setTimeout(() => {
    if (props.item && localContent.value !== props.item.content) {
      emit('update', props.item.id, { content: localContent.value })
    }
  }, 500)
}

function toggleStatus() {
  if (props.item) {
    const newStatus = props.item.status === 'TODO' ? 'DONE' : 'TODO'
    emit('update', props.item.id, { status: newStatus })
  }
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

const isDigest = computed(() => props.item?.type === 'DIGEST')
const categoryLabel = computed(() => {
  if (!props.item?.category) return null
  const map: Record<string, string> = {
    AI_FRONTIER: 'AI 前沿', TECH_INDUSTRY: '技术产业', FINANCE_TECH: '财经科技', OTHER: '其他'
  }
  return map[props.item.category] ?? props.item.category
})

const articleCategoryLabel: Record<string, string> = {
  AI_FRONTIER: 'AI', TECH_INDUSTRY: 'Tech', FINANCE_TECH: '财经', OTHER: '其它'
}
const articleCategoryClasses: Record<string, string> = {
  AI_FRONTIER: 'bg-violet-500/15 text-violet-400 border-violet-500/30',
  TECH_INDUSTRY: 'bg-blue-500/15 text-blue-400 border-blue-500/30',
  FINANCE_TECH: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
  OTHER: 'bg-zinc-500/15 text-zinc-400 border-zinc-500/30',
}

const digestArticles = computed<DigestArticle[]>(() => {
  if (!props.item?.longText) return []
  try {
    const parsed = JSON.parse(props.item.longText)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
})

/** Group articles by section, in importance order. */
const SECTIONS: { key: DigestCategory; label: string }[] = [
  { key: 'AI_FRONTIER', label: 'AI 前沿' },
  { key: 'TECH_INDUSTRY', label: '技术产业' },
  { key: 'FINANCE_TECH', label: '财经科技' },
  { key: 'OTHER', label: '其他简报' },
]
const digestSections = computed(() => {
  const grouped = new Map<DigestCategory, DigestArticle[]>()
  for (const a of digestArticles.value) {
    if (!grouped.has(a.category)) grouped.set(a.category, [])
    grouped.get(a.category)!.push(a)
  }
  return SECTIONS
    .map(s => ({ ...s, articles: grouped.get(s.key) ?? [] }))
    .filter(s => s.articles.length > 0)
})
</script>

<template>
  <div v-if="item" class="h-full flex flex-col">
    <!-- Header -->
    <div class="px-6 py-4 border-b border-border/40 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <button
          @click="toggleStatus"
          class="transition-colors"
          :class="[
            item.status === 'DONE' ? 'text-green-500' : 'text-muted-foreground hover:text-primary'
          ]"
        >
          <CircleCheck v-if="item.status === 'DONE'" class="w-5 h-5" />
          <Circle v-else class="w-5 h-5" />
        </button>
        <span
          class="text-xs px-2 py-0.5 rounded-full"
          :class="[
            item.status === 'DONE'
              ? 'bg-green-500/20 text-green-400'
              : 'bg-primary/20 text-primary'
          ]"
        >
          {{ item.status }}
        </span>
      </div>
      <div class="flex items-center gap-1 text-xs text-muted-foreground">
        <Clock class="w-3 h-3 mr-1" />
        {{ formatDate(item.createdAt) }}
      </div>
    </div>

    <!-- Content -->
    <div class="flex-1 p-6 overflow-auto">
      <!-- DIGEST 聚合：标题 + 文章列表（每篇一张卡片） -->
      <template v-if="isDigest">
        <div class="flex items-center gap-2 mb-3">
          <span class="inline-flex items-center gap-1.5 text-xs px-2 py-0.5 rounded-md bg-primary/10 text-primary border border-primary/20">
            <Newspaper class="w-3 h-3" />
            Daily Digest
          </span>
          <span v-if="item.summary" class="text-xs text-muted-foreground">
            {{ item.summary }}
          </span>
        </div>
        <h1 class="text-2xl font-semibold leading-snug mb-5">{{ item.content }}</h1>
        <div v-if="digestArticles.length === 0" class="text-sm text-muted-foreground">
          今日无新文章
        </div>
        <div v-else class="space-y-6">
          <section v-for="sec in digestSections" :key="sec.key">
            <h2 class="flex items-center gap-2 text-sm font-semibold tracking-tight mb-3 sticky top-0 bg-background/95 backdrop-blur py-2 z-10">
              <span class="w-1 h-4 rounded-sm" :class="articleCategoryClasses[sec.key].split(' ').find(c => c.startsWith('bg-'))"></span>
              {{ sec.label }}
              <span class="text-xs text-muted-foreground font-normal">（{{ sec.articles.length }}）</span>
            </h2>
            <ul class="space-y-2.5">
              <li
                v-for="(a, idx) in sec.articles"
                :key="idx"
                class="p-3.5 rounded-lg border border-border/40 bg-secondary/10 hover:bg-secondary/20 transition-colors"
              >
                <div class="flex items-center gap-2 mb-1">
                  <span v-if="a.sourceName" class="text-[10px] text-muted-foreground">{{ a.sourceName }}</span>
                </div>
                <a
                  v-if="a.link"
                  :href="a.link"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="text-sm font-medium leading-snug hover:text-primary transition-colors"
                >
                  {{ a.title }}
                </a>
                <h3 v-else class="text-sm font-medium leading-snug">{{ a.title }}</h3>
                <p v-if="a.summary" class="text-xs text-muted-foreground mt-1 line-clamp-2">
                  {{ a.summary }}
                </p>
              </li>
            </ul>
          </section>
        </div>
      </template>
      <!-- NOTE：可编辑 textarea -->
      <textarea
        v-else
        v-model="localContent"
        @input="queueSave"
        placeholder="Edit content..."
        class="w-full h-full bg-transparent border-none text-base resize-none focus:ring-0 focus:outline-none p-0 placeholder:text-muted-foreground/60"
      ></textarea>
    </div>

    <!-- Action Footer -->
    <div class="px-6 py-4 border-t border-border/40 space-y-3">
      <p class="text-xs text-muted-foreground mb-2">Convert to:</p>
      <div class="flex gap-2">
        <Button
          variant="outline"
          size="sm"
          class="flex-1 h-9 bg-secondary/30 border-border/40 hover:bg-secondary/50"
          @click="emit('convertToIssue', item.id)"
        >
          <ArrowRightLeft class="w-4 h-4 mr-2" />
          Issue
        </Button>
        <Button
          variant="outline"
          size="sm"
          class="flex-1 h-9 bg-secondary/30 border-border/40 hover:bg-secondary/50"
          @click="emit('convertToProject', item.id)"
        >
          <FolderPlus class="w-4 h-4 mr-2" />
          Project
        </Button>
        <Button
          variant="outline"
          size="sm"
          class="flex-1 h-9 bg-secondary/30 border-border/40 hover:bg-secondary/50"
          @click="emit('convertToKnowledge', item.id)"
        >
          <BookOpen class="w-4 h-4 mr-2" />
          Knowledge
        </Button>
      </div>
    </div>
  </div>

  <!-- Empty State -->
  <div v-else class="h-full flex flex-col items-center justify-center text-muted-foreground">
    <Circle class="w-12 h-12 mb-4 opacity-20" />
    <p class="text-sm">Select an item to view details</p>
  </div>
</template>