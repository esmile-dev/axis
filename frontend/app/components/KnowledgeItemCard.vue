<script setup lang="ts">
import { FileText, BookOpen, StickyNote, Podcast, Video, GraduationCap } from 'lucide-vue-next'
import type { ArtifactStatus, KnowledgeItemSummary, KnowledgeStatus, KnowledgeType } from '@/types'

const props = defineProps<{
  item: KnowledgeItemSummary
  isSelected: boolean
}>()

const emit = defineEmits<{
  select: [id: string]
}>()

const typeIcons: Record<KnowledgeType, unknown> = {
  ARTICLE: FileText,
  BOOK: BookOpen,
  NOTE: StickyNote,
  PODCAST: Podcast,
  VIDEO: Video,
  TUTORIAL: GraduationCap,
}

const statusLabels: Record<KnowledgeStatus, string> = {
  UNREAD: '未读',
  READING: '在读',
  DONE: '已读',
  ARCHIVED: '归档',
}

const statusClasses: Record<KnowledgeStatus, string> = {
  UNREAD: 'bg-blue-500/15 text-blue-400 border-blue-500/30',
  READING: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
  DONE: 'bg-green-500/15 text-green-400 border-green-500/30',
  ARCHIVED: 'bg-zinc-500/15 text-zinc-400 border-zinc-500/30',
}

const typeIcon = computed(() => typeIcons[props.item.type] ?? FileText)
const statusLabel = computed(() => statusLabels[props.item.status] ?? props.item.status)
const statusClass = computed(() => statusClasses[props.item.status] ?? '')

function artifactDotClass(status: ArtifactStatus): string {
  switch (status) {
    case 'DONE': return 'bg-green-500'
    case 'FAILED': return 'bg-red-500'
    case 'GENERATING': return 'bg-zinc-500 animate-pulse'
    default: return 'bg-zinc-600'
  }
}

function artifactLabel(kind: '总结' | '脑图', status: ArtifactStatus): string {
  const map: Record<ArtifactStatus, string> = {
    PENDING: '待生成',
    GENERATING: '生成中',
    DONE: '已生成',
    FAILED: '生成失败',
  }
  return `${kind}${map[status] ?? status}`
}

function formatRelativeTime(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)
  const diffHours = Math.floor(diffMs / 3600000)
  const diffDays = Math.floor(diffMs / 86400000)

  if (diffMins < 1) return 'just now'
  if (diffMins < 60) return `${diffMins}m ago`
  if (diffHours < 24) return `${diffHours}h ago`
  if (diffDays < 7) return `${diffDays}d ago`
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}
</script>

<template>
  <div
    class="px-4 py-3 cursor-pointer transition-all"
    :class="[
      isSelected ? 'bg-primary/10 border-l-2 border-l-primary' : 'hover:bg-secondary/30 border-l-2 border-l-transparent'
    ]"
    @click="emit('select', item.id)"
  >
    <div class="flex items-start gap-3">
      <component :is="typeIcon" class="w-4 h-4 mt-0.5 flex-shrink-0 text-muted-foreground" />

      <div class="flex-1 min-w-0">
        <p class="text-sm leading-snug line-clamp-2">{{ item.title }}</p>

        <!-- Progress bar -->
        <div v-if="item.progress > 0" class="mt-2 h-1 rounded-full bg-secondary/40 overflow-hidden">
          <div class="h-full rounded-full bg-primary" :style="{ width: `${Math.min(item.progress, 100)}%` }" />
        </div>

        <div class="mt-1.5 flex items-center gap-2">
          <!-- Status badge -->
          <span class="px-1.5 py-0.5 rounded border text-[10px] leading-none" :class="statusClass">
            {{ statusLabel }}
          </span>

          <!-- Artifact status dots -->
          <span class="flex items-center gap-1.5">
            <span
              class="w-1.5 h-1.5 rounded-full"
              :class="artifactDotClass(item.summaryStatus)"
              :title="artifactLabel('总结', item.summaryStatus)"
            />
            <span
              class="w-1.5 h-1.5 rounded-full"
              :class="artifactDotClass(item.mindmapStatus)"
              :title="artifactLabel('脑图', item.mindmapStatus)"
            />
          </span>

          <span class="ml-auto text-xs text-muted-foreground flex-shrink-0">
            {{ formatRelativeTime(item.updatedAt) }}
          </span>
        </div>
      </div>
    </div>
  </div>
</template>
