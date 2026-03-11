<script setup lang="ts">
import { Trash2, CheckCircle2, Circle, Cloud, CloudOff } from 'lucide-vue-next'
import { useMagicKeys } from '@vueuse/core'
import IssueCreator from '@/components/IssueCreator.vue'
import IssueDetail from '@/components/IssueDetail.vue'

const localFirst = useLocalFirst('issues-todo', async () => {
    return await $fetch('/api/issues', { query: { projectId: 'none' } });
})
const optimistic = useOptimistic(localFirst)

const isCreatorOpen = ref(false)
const isDetailOpen = ref(false)
const selectedIssueId = ref<string | null>(null)

const { c } = useMagicKeys()

watch(() => c?.value, (v) => {
  if (v && !isCreatorOpen.value) {
    // Only open if not typing in an input
    const activeElement = document.activeElement
    const isInput = activeElement && ['INPUT', 'TEXTAREA'].includes(activeElement.tagName)
    if (!isInput) {
      isCreatorOpen.value = true
    }
  }
})

async function handleCreateIssue(payload: any) {
  const tempItem = {
    id: generateTempId(),
    ...payload,
    status: payload.status || 'TODO',
    priority: payload.priority || 'NONE',
    type: payload.type || 'FEATURE',
    order: 0,
    projectId: payload.projectId || null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }
  
  await optimistic.optimisticAdd(
    async (item: any) => {
      return await $fetch('/api/issues', {
        method: 'POST',
        body: payload
      })
    },
    tempItem
  )
}

function generateTempId(): string {
  return `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

async function toggleTodo(issue: any) {
  const newStatus = issue.status === 'DONE' ? 'TODO' : 'DONE'
  await optimistic.optimisticUpdate(
    async (id: string, updates: any) => {
      return await $fetch(`/api/issues/${id}`, {
        method: 'PATCH',
        body: updates
      })
    },
    issue.id,
    { status: newStatus }
  )
}

async function deleteTodo(id: string) {
  await optimistic.optimisticDelete(
    async (itemId) => {
      await $fetch(`/api/issues/${itemId}`, { method: 'DELETE' })
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
        <h1 class="text-3xl font-bold tracking-tight mb-2">Todo</h1>
        <p class="text-muted-foreground">Stay on top of your daily tasks.</p>
      </div>
      <div class="flex items-center gap-2 text-xs text-muted-foreground">
        <Cloud v-if="!localFirst.isSyncing.value" class="w-4 h-4" />
        <CloudOff v-else class="w-4 h-4 animate-pulse" />
        <span>{{ localFirst.isSyncing.value ? 'Syncing...' : 'Synced' }}</span>
      </div>
    </header>

    <div class="mb-8 flex justify-center">
      <IssueCreator v-model:open="isCreatorOpen" @create="handleCreateIssue">
        <button class="w-full bg-secondary/50 border-none rounded-xl px-6 py-4 text-lg text-left text-muted-foreground hover:bg-secondary/70 transition-all flex items-center justify-between group">
          <span>Add a new task...</span>
          <div class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
            <span class="text-xs bg-background px-2 py-1 rounded shadow-sm border border-border">Press C</span>
          </div>
        </button>
      </IssueCreator>
    </div>

    <div class="space-y-2">
      <TransitionGroup name="list">
        <div
          v-for="issue in localFirst.items.value"
          :key="issue.id"
          class="flex items-center gap-4 p-4 bg-card rounded-xl border border-border group transition-all hover:bg-accent/50"
          :class="{ 'opacity-50': optimistic.isPending(issue.id) }"
        >
          <button 
            @click.stop="toggleTodo(issue)" 
            :disabled="optimistic.isPending(issue.id)"
            class="text-muted-foreground hover:text-primary transition-colors flex-shrink-0"
          >
            <CheckCircle2 v-if="issue.status === 'DONE'" class="w-5 h-5 text-green-500" />
            <Circle v-else class="w-5 h-5" />
          </button>
          
          <button
            class="flex-1 text-sm transition-all text-left truncate"
            :class="{ 'line-through text-muted-foreground': issue.status === 'DONE' }"
            @click="selectedIssueId = issue.id; isDetailOpen = true"
          >
            {{ issue.title }}
          </button>

          <button
            @click.stop="deleteTodo(issue.id)"
            :disabled="optimistic.isPending(issue.id)"
            class="opacity-0 group-hover:opacity-100 p-2 text-muted-foreground hover:text-destructive transition-all flex-shrink-0"
          >
            <Trash2 class="w-4 h-4" />
          </button>
        </div>
      </TransitionGroup>

      <div v-if="localFirst.items.value.length === 0" class="text-center py-20 text-muted-foreground">
        <CheckCircle2 class="w-12 h-12 mx-auto mb-4 opacity-20" />
        <p>All caught up! Nothing to do right now.</p>
        <p class="text-sm mt-2 opacity-50">Press <kbd class="px-2 py-0.5 bg-secondary/50 rounded-md border border-border">C</kbd> to create a new task</p>
      </div>
    </div>

    <IssueDetail v-model:open="isDetailOpen" :issue-id="selectedIssueId" />
  </div>
</template>

<style scoped>
.list-enter-active,
.list-leave-active {
  transition: all 0.2s ease;
}
.list-enter-from {
  opacity: 0;
  transform: translateX(-10px);
}
.list-leave-to {
  opacity: 0;
  transform: scale(0.95);
}
</style>
