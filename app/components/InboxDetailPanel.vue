<script setup lang="ts">
import { CircleCheck, Circle, ArrowRightLeft, FolderPlus, Clock } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'

interface InboxItem {
  id: string
  content: string
  status: 'TODO' | 'DONE'
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

    <!-- Content Editor -->
    <div class="flex-1 p-6">
      <textarea
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