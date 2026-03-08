<script setup lang="ts">
import { Trash2, CheckCircle2, Circle, Cloud, CloudOff } from 'lucide-vue-next'

interface TodoItem {
  id: string
  title: string
  completed: boolean
  createdAt: string
  updatedAt: string
}

const localFirst = useLocalFirst<TodoItem>('todo', () => $fetch<TodoItem[]>('/api/todo'))
const optimistic = useOptimistic(localFirst)

const newTodoTitle = ref('')

function generateTempId(): string {
  return `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

async function addTodo() {
  if (!newTodoTitle.value.trim()) return
  
  const tempItem: TodoItem = {
    id: generateTempId(),
    title: newTodoTitle.value,
    completed: false,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }
  
  newTodoTitle.value = ''
  
  await optimistic.optimisticAdd(
    async (item) => {
      return await $fetch<TodoItem>('/api/todo', {
        method: 'POST',
        body: { title: item.title }
      })
    },
    tempItem
  )
}

async function toggleTodo(todo: TodoItem) {
  await optimistic.optimisticUpdate(
    async (id, updates) => {
      return await $fetch<TodoItem>(`/api/todo/${id}`, {
        method: 'PATCH',
        body: updates
      })
    },
    todo.id,
    { completed: !todo.completed }
  )
}

async function deleteTodo(id: string) {
  await optimistic.optimisticDelete(
    async (itemId) => {
      await $fetch(`/api/todo/${itemId}`, { method: 'DELETE' })
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

    <div class="relative mb-8 group">
      <input
        v-model="newTodoTitle"
        @keyup.enter="addTodo"
        type="text"
        placeholder="Add a new task..."
        class="w-full bg-secondary/50 border-none rounded-xl px-6 py-4 text-lg focus:ring-2 focus:ring-ring transition-all placeholder:text-muted-foreground"
      />
    </div>

    <div class="space-y-2">
      <TransitionGroup name="list">
        <div
          v-for="todo in localFirst.items.value"
          :key="todo.id"
          class="flex items-center gap-4 p-4 bg-card rounded-xl border border-border group transition-all hover:bg-accent/50"
          :class="{ 'opacity-50': optimistic.isPending(todo.id) }"
        >
          <button 
            @click="toggleTodo(todo)" 
            :disabled="optimistic.isPending(todo.id)"
            class="text-muted-foreground hover:text-primary transition-colors"
          >
            <CheckCircle2 v-if="todo.completed" class="w-5 h-5 text-green-500" />
            <Circle v-else class="w-5 h-5" />
          </button>
          
          <span
            class="flex-1 text-sm transition-all"
            :class="{ 'line-through text-muted-foreground': todo.completed }"
          >
            {{ todo.title }}
          </span>

          <button
            @click="deleteTodo(todo.id)"
            :disabled="optimistic.isPending(todo.id)"
            class="opacity-0 group-hover:opacity-100 p-2 text-muted-foreground hover:text-destructive transition-all"
          >
            <Trash2 class="w-4 h-4" />
          </button>
        </div>
      </TransitionGroup>

      <div v-if="localFirst.items.value.length === 0" class="text-center py-20 text-muted-foreground">
        <CheckCircle2 class="w-12 h-12 mx-auto mb-4 opacity-20" />
        <p>All caught up! Nothing to do right now.</p>
      </div>
    </div>
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
