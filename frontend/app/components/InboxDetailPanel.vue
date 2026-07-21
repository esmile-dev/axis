<script setup lang="ts">
import { CircleCheck, Circle, ArrowRightLeft, FolderPlus, Clock, ExternalLink, Newspaper } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'

type DigestCategory = 'AI_FRONTIER' | 'TECH_INDUSTRY' | 'FINANCE_TECH' | 'OTHER'
type ItemType = 'NOTE' | 'DIGEST'

interface InboxItem {
  id: string
  content: string
  status: 'TODO' | 'DONE'
  type?: ItemType
  summary?: string | null
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
      <!-- DIGEST：标题 + 摘要 + 原文链接（只读） -->
      <template v-if="isDigest">
        <div class="flex items-center gap-2 mb-3">
          <span class="inline-flex items-center gap-1.5 text-xs px-2 py-0.5 rounded-md bg-primary/10 text-primary border border-primary/20">
            <Newspaper class="w-3 h-3" />
            Daily Digest
          </span>
          <span v-if="categoryLabel" class="text-xs px-2 py-0.5 rounded-md bg-secondary/30 text-muted-foreground">
            {{ categoryLabel }}
          </span>
          <span v-if="item.sourceName" class="text-xs text-muted-foreground">
            · {{ item.sourceName }}
          </span>
        </div>
        <h1 class="text-2xl font-semibold leading-snug mb-4">{{ item.content }}</h1>
        <p v-if="item.summary" class="text-base text-muted-foreground leading-relaxed mb-6 whitespace-pre-line">
          {{ item.summary }}
        </p>
        <a
          v-if="item.link"
          :href="item.link"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-flex items-center gap-2 text-sm text-primary hover:underline"
        >
          <ExternalLink class="w-4 h-4" />
          阅读原文
        </a>
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
      </div>
    </div>
  </div>

  <!-- Empty State -->
  <div v-else class="h-full flex flex-col items-center justify-center text-muted-foreground">
    <Circle class="w-12 h-12 mb-4 opacity-20" />
    <p class="text-sm">Select an item to view details</p>
  </div>
</template>