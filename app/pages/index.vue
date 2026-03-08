<script setup lang="ts">
import { Trash2, Send, Cloud, CloudOff } from 'lucide-vue-next'

interface InboxItem {
  id: string
  content: string
  createdAt: string
  updatedAt: string
}

const localFirst = useLocalFirst<InboxItem>('inbox', () => $fetch<InboxItem[]>('/api/inbox'))
const optimistic = useOptimistic(localFirst)

const newItem = ref('')

function generateTempId(): string {
  return `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

async function addItem() {
  if (!newItem.value.trim()) return
  
  const tempItem: InboxItem = {
    id: generateTempId(),
    content: newItem.value,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }
  
  newItem.value = ''
  
  await optimistic.optimisticAdd(
    async (item) => {
      return await $fetch<InboxItem>('/api/inbox', {
        method: 'POST',
        body: { content: item.content }
      })
    },
    tempItem
  )
}

async function deleteItem(id: string) {
  await optimistic.optimisticDelete(
    async (itemId) => {
      await $fetch(`/api/inbox/${itemId}`, { method: 'DELETE' })
    },
    id
  )
}

onMounted(() => localFirst.init())
</script>

<template>
  <div class="p-8 max-w-4xl mx-auto">
    <header class="mb-10 flex items-center justify-between">
      <div>
        <h1 class="text-3xl font-bold tracking-tight mb-2">Inbox</h1>
        <p class="text-muted-foreground">Capture thoughts and inspirations instantly.</p>
      </div>
      <div class="flex items-center gap-2 text-xs text-muted-foreground">
        <Cloud v-if="!localFirst.isSyncing.value" class="w-4 h-4" />
        <CloudOff v-else class="w-4 h-4 animate-pulse" />
        <span>{{ localFirst.isSyncing.value ? 'Syncing...' : 'Synced' }}</span>
      </div>
    </header>

    <div class="relative mb-8 group">
      <input
        v-model="newItem"
        @keyup.enter="addItem"
        type="text"
        placeholder="Type a thought and press Enter..."
        class="w-full bg-secondary/50 border-none rounded-xl px-6 py-4 text-lg focus:ring-2 focus:ring-ring transition-all placeholder:text-muted-foreground"
      />
      <div class="absolute right-4 top-1/2 -translate-y-1/2 opacity-0 group-focus-within:opacity-100 transition-opacity">
        <kbd class="px-2 py-1 rounded bg-muted text-xs font-mono">⏎ Enter</kbd>
      </div>
    </div>

    <div class="space-y-4">
      <TransitionGroup name="list">
        <div
          v-for="item in localFirst.items.value"
          :key="item.id"
          class="flex items-center gap-4 p-4 bg-card rounded-xl border border-border group hover:border-border/80 transition-all hover:translate-x-1"
          :class="{ 'opacity-50': optimistic.isPending(item.id) }"
        >
          <div class="flex-1 text-sm leading-relaxed">
            {{ item.content }}
          </div>
          <button
            @click="deleteItem(item.id)"
            :disabled="optimistic.isPending(item.id)"
            class="opacity-0 group-hover:opacity-100 p-2 text-muted-foreground hover:text-destructive transition-all"
          >
            <Trash2 class="w-4 h-4" />
          </button>
        </div>
      </TransitionGroup>

      <div v-if="localFirst.items.value.length === 0" class="text-center py-20 text-muted-foreground">
        <Send class="w-12 h-12 mx-auto mb-4 opacity-20" />
        <p>No thoughts captured yet. Start typing above!</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.list-enter-active,
.list-leave-active {
  transition: all 0.3s ease;
}
.list-enter-from {
  opacity: 0;
  transform: translateY(-20px);
}
.list-leave-to {
  opacity: 0;
  transform: translateX(30px);
}
</style>
