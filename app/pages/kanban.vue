<script setup lang="ts">
import { Plus, GripVertical, Sparkles, Cloud, CloudOff } from 'lucide-vue-next'

interface KanbanTask {
  id: string
  title: string
  description?: string
  status: string
  order: number
  createdAt: string
  updatedAt: string
}

const localFirst = useLocalFirst<KanbanTask>('kanban', () => $fetch<KanbanTask[]>('/api/kanban'))
const optimistic = useOptimistic(localFirst)

const columns = ['Todo', 'In Progress', 'Done'] as const
type ColumnStatus = typeof columns[number]

const tasksByStatus = computed(() => {
  const groups: Record<ColumnStatus, KanbanTask[]> = {
    'Todo': [],
    'In Progress': [],
    'Done': []
  }
  localFirst.items.value.forEach((task: KanbanTask) => {
    if (groups[task.status as ColumnStatus]) {
      groups[task.status as ColumnStatus].push(task)
    }
  })
  return groups
})

const draggedTask = ref<KanbanTask | null>(null)
const dragOverColumn = ref<string | null>(null)

const showSlidePanel = ref(false)
const editingTask = ref<KanbanTask | null>(null)
const editForm = ref({ title: '', description: '' })

function generateTempId(): string {
  return `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

function openNewTaskPanel(status: string) {
  editingTask.value = null
  editForm.value = { title: '', description: '' }
  showSlidePanel.value = true
}

function openEditPanel(task: KanbanTask) {
  editingTask.value = task
  editForm.value = { title: task.title, description: task.description || '' }
  showSlidePanel.value = true
}

async function saveTask() {
  if (!editForm.value.title.trim()) return
  
  if (editingTask.value) {
    await optimistic.optimisticUpdate(
      async (id, updates) => {
        return await $fetch<KanbanTask>(`/api/kanban/${id}`, {
          method: 'PATCH',
          body: updates
        })
      },
      editingTask.value.id,
      { title: editForm.value.title, description: editForm.value.description }
    )
  } else {
    const tempItem: KanbanTask = {
      id: generateTempId(),
      title: editForm.value.title,
      description: editForm.value.description,
      status: 'Todo',
      order: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    }
    
    await optimistic.optimisticAdd(
      async (item) => {
        return await $fetch<KanbanTask>('/api/kanban', {
          method: 'POST',
          body: { title: item.title, description: item.description, status: item.status }
        })
      },
      tempItem
    )
  }
  
  showSlidePanel.value = false
}

async function deleteTask(id: string) {
  await optimistic.optimisticDelete(
    async (itemId) => {
      await $fetch(`/api/kanban/${itemId}`, { method: 'DELETE' })
    },
    id
  )
  showSlidePanel.value = false
}

function onDragStart(task: KanbanTask) {
  draggedTask.value = task
}

function onDragOver(e: DragEvent, status: string) {
  e.preventDefault()
  dragOverColumn.value = status
}

function onDragLeave() {
  dragOverColumn.value = null
}

async function onDrop(status: string) {
  if (!draggedTask.value || draggedTask.value.status === status) {
    draggedTask.value = null
    dragOverColumn.value = null
    return
  }
  
  await optimistic.optimisticUpdate(
    async (id, updates) => {
      return await $fetch<KanbanTask>(`/api/kanban/${id}`, {
        method: 'PATCH',
        body: updates
      })
    },
    draggedTask.value.id,
    { status }
  )
  
  draggedTask.value = null
  dragOverColumn.value = null
}

const isExpanding = ref(false)

function getAISettings() {
  if (import.meta.client) {
    return {
      apiKey: localStorage.getItem('axis_ai_api_key') || '',
      endpoint: localStorage.getItem('axis_ai_endpoint') || 'https://api.openai.com/v1',
      model: localStorage.getItem('axis_ai_model') || 'gpt-4o-mini'
    }
  }
  return { apiKey: '', endpoint: 'https://api.openai.com/v1', model: 'gpt-4o-mini' }
}

async function aiExpand(task: KanbanTask) {
  isExpanding.value = true
  
  try {
    const aiSettings = getAISettings()
    const response = await fetch('/api/ai/expand', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: task.title,
        ...aiSettings
      })
    })
    const reader = response.body?.getReader()
    if (!reader) return

    let result = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      result += new TextDecoder().decode(value)
    }
    
    await optimistic.optimisticUpdate(
      async (id, updates) => {
        return await $fetch<KanbanTask>(`/api/kanban/${id}`, {
          method: 'PATCH',
          body: updates
        })
      },
      task.id,
      { description: result }
    )
  } catch (e) {
    console.error('AI Expansion failed', e)
  } finally {
    isExpanding.value = false
  }
}

onMounted(() => localFirst.init())
</script>

<template>
  <div class="h-full flex flex-col">
    <header class="p-8 border-b border-border bg-card/30 backdrop-blur-md flex items-center justify-between">
      <div>
        <h1 class="text-3xl font-bold tracking-tight mb-2">Kanban</h1>
        <p class="text-muted-foreground">Move ideas through the pipeline.</p>
      </div>
      <div class="flex items-center gap-2 text-xs text-muted-foreground">
        <Cloud v-if="!localFirst.isSyncing.value" class="w-4 h-4" />
        <CloudOff v-else class="w-4 h-4 animate-pulse" />
        <span>{{ localFirst.isSyncing.value ? 'Syncing...' : 'Synced' }}</span>
      </div>
    </header>

    <div class="flex-1 overflow-x-auto p-8">
      <div class="flex gap-6 min-w-max h-full">
        <div
          v-for="col in columns"
          :key="col"
          class="w-80 flex flex-col bg-secondary/20 rounded-2xl border border-border/50 transition-all"
          :class="{ 'ring-2 ring-primary/50 bg-primary/5': dragOverColumn === col }"
          @dragover="onDragOver($event, col)"
          @dragleave="onDragLeave"
          @drop="onDrop(col)"
        >
          <div class="p-4 flex items-center justify-between">
            <h3 class="font-semibold text-sm flex items-center gap-2">
              <span class="w-2 h-2 rounded-full" :class="{
                'bg-blue-500': col === 'Todo',
                'bg-yellow-500': col === 'In Progress',
                'bg-green-500': col === 'Done'
              }"></span>
              {{ col }}
              <span class="text-muted-foreground text-xs bg-muted px-1.5 py-0.5 rounded ml-2">
                {{ tasksByStatus[col].length }}
              </span>
            </h3>
            <button @click="openNewTaskPanel(col)" class="p-1 hover:bg-muted rounded transition-colors">
              <Plus class="w-4 h-4" />
            </button>
          </div>

          <div class="flex-1 p-4 space-y-4 overflow-y-auto">
            <div
              v-for="task in tasksByStatus[col]"
              :key="task.id"
              draggable="true"
              @dragstart="onDragStart(task)"
              @click="openEditPanel(task)"
              class="group relative bg-card border border-border rounded-xl p-4 shadow-sm hover:shadow-md transition-all hover:border-border/80 cursor-pointer"
              :class="{ 'opacity-50': optimistic.isPending(task.id) }"
            >
              <div class="flex items-start justify-between mb-2">
                <span class="text-sm font-medium leading-tight">{{ task.title }}</span>
                <GripVertical class="w-3 h-3 text-muted-foreground opacity-0 group-hover:opacity-100" />
              </div>
              
              <div v-if="task.description" class="text-xs text-muted-foreground line-clamp-2 mb-3">
                {{ task.description }}
              </div>

              <div class="flex items-center gap-2 mt-4">
                <button
                  @click.stop="aiExpand(task)"
                  :disabled="isExpanding"
                  class="flex items-center gap-1.5 px-2 py-1 rounded-md bg-primary/5 text-[10px] font-bold text-primary hover:bg-primary/10 transition-all border border-primary/20"
                >
                  <Sparkles class="w-3 h-3" :class="{ 'animate-pulse': isExpanding }" />
                  AI EXPAND
                </button>
              </div>
            </div>
            
            <div v-if="tasksByStatus[col].length === 0" class="border-2 border-dashed border-border/30 rounded-xl h-24 flex items-center justify-center text-xs text-muted-foreground/50">
              Drop tasks here
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Slide-over Panel -->
    <Teleport to="body">
      <Transition name="slide">
        <div v-if="showSlidePanel" class="fixed inset-0 z-50">
          <div class="absolute inset-0 bg-black/50" @click="showSlidePanel = false"></div>
          <div class="absolute right-0 top-0 h-full w-full max-w-md bg-card border-l border-border shadow-xl">
            <div class="flex flex-col h-full">
              <div class="p-6 border-b border-border flex items-center justify-between">
                <h2 class="text-lg font-semibold">{{ editingTask ? 'Edit Task' : 'New Task' }}</h2>
                <button @click="showSlidePanel = false" class="p-2 hover:bg-muted rounded-lg">
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
              
              <div class="flex-1 p-6 space-y-6 overflow-y-auto">
                <div>
                  <label class="block text-sm font-medium mb-2">Title</label>
                  <input
                    v-model="editForm.title"
                    type="text"
                    placeholder="Task title..."
                    class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all"
                  />
                </div>
                
                <div>
                  <label class="block text-sm font-medium mb-2">Description</label>
                  <textarea
                    v-model="editForm.description"
                    rows="6"
                    placeholder="Task description (Markdown supported)..."
                    class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all resize-none"
                  ></textarea>
                </div>
              </div>
              
              <div class="p-6 border-t border-border flex items-center justify-between">
                <button
                  v-if="editingTask"
                  @click="deleteTask(editingTask.id)"
                  class="px-4 py-2 text-sm text-destructive hover:bg-destructive/10 rounded-lg transition-all"
                >
                  Delete
                </button>
                <div v-else></div>
                <button
                  @click="saveTask"
                  :disabled="!editForm.title.trim()"
                  class="px-6 py-2 bg-primary text-primary-foreground rounded-lg font-medium hover:opacity-90 transition-all disabled:opacity-50"
                >
                  {{ editingTask ? 'Save Changes' : 'Create Task' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.slide-enter-active,
.slide-leave-active {
  transition: all 0.3s ease;
}
.slide-enter-from,
.slide-leave-to {
  opacity: 0;
}
.slide-enter-from .absolute.right-0,
.slide-leave-to .absolute.right-0 {
  transform: translateX(100%);
}
</style>
