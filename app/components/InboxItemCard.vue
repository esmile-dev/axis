<script setup lang="ts">
import { Circle, CircleCheck, Trash2 } from 'lucide-vue-next'

interface InboxItem {
  id: string
  content: string
  status: 'TODO' | 'DONE'
  createdAt: string
  updatedAt: string
}

const props = defineProps<{
  item: InboxItem
  isSelected: boolean
}>()

const emit = defineEmits<{
  select: [id: string]
  toggleStatus: [id: string]
  delete: [id: string]
}>()

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
    class="flex items-center gap-3 px-4 py-3 cursor-pointer transition-all group"
    :class="[
      isSelected ? 'bg-primary/10 border-l-2 border-l-primary' : 'hover:bg-secondary/30 border-l-2 border-l-transparent'
    ]"
    @click="emit('select', item.id)"
  >
    <!-- Status Toggle -->
    <button
      @click.stop="emit('toggleStatus', item.id)"
      class="flex-shrink-0 transition-colors"
      :class="[
        item.status === 'DONE' ? 'text-green-500' : 'text-muted-foreground hover:text-primary'
      ]"
    >
      <CircleCheck v-if="item.status === 'DONE'" class="w-4 h-4" />
      <Circle v-else class="w-4 h-4" />
    </button>

    <!-- Content -->
    <div class="flex-1 min-w-0">
      <p
        class="text-sm truncate"
        :class="[
          item.status === 'DONE' ? 'text-muted-foreground line-through' : 'text-foreground'
        ]"
      >
        {{ item.content }}
      </p>
    </div>

    <!-- Time -->
    <span class="text-xs text-muted-foreground flex-shrink-0">
      {{ formatRelativeTime(item.createdAt) }}
    </span>

    <!-- Delete Button -->
    <button
      @click.stop="emit('delete', item.id)"
      class="opacity-0 group-hover:opacity-100 p-1.5 text-muted-foreground hover:text-destructive transition-all"
    >
      <Trash2 class="w-3.5 h-3.5" />
    </button>
  </div>
</template>