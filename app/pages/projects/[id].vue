<script setup lang="ts">
import { Plus, GripVertical, Sparkles, Cloud, CloudOff, ArrowLeft, Bug, Lightbulb, Wrench, AlertCircle, Circle, Clock, CheckCircle } from 'lucide-vue-next'

type IssueStatus = 'TODO' | 'IN_PROGRESS' | 'DONE'
type IssuePriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
type IssueType = 'BUG' | 'FEATURE' | 'IMPROVEMENT'
type ProjectStatus = 'PLANNING' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED'

interface Project {
  id: string
  name: string
  description: string | null
  status: ProjectStatus
  order: number
  createdAt: string
  updatedAt: string
}

interface Issue {
  id: string
  title: string
  description: string | null
  status: IssueStatus
  priority: IssuePriority
  type: IssueType
  order: number
  projectId: string
  createdAt: string
  updatedAt: string
}

const route = useRoute()
const projectId = route.params.id as string

const project = ref<Project | null>(null)
const localFirst = useLocalFirst<Issue>(`issues_${projectId}`, () => 
  $fetch<Issue[]>('/api/issues', { query: { projectId } })
)
const optimistic = useOptimistic(localFirst)

const columns: { status: string; label: string; color: string }[] = [
  { status: 'TODO', label: 'Todo', color: 'bg-blue-500' },
  { status: 'IN_PROGRESS', label: 'In Progress', color: 'bg-yellow-500' },
  { status: 'DONE', label: 'Done', color: 'bg-green-500' }
]

const priorityConfig: Record<string, { label: string; color: string; icon: any }> = {
  LOW: { label: 'Low', color: 'text-gray-400', icon: Circle },
  MEDIUM: { label: 'Medium', color: 'text-blue-400', icon: Circle },
  HIGH: { label: 'High', color: 'text-orange-400', icon: AlertCircle },
  URGENT: { label: 'Urgent', color: 'text-red-400', icon: AlertCircle }
}

const typeConfig: Record<string, { label: string; color: string; icon: any }> = {
  BUG: { label: 'Bug', color: 'bg-red-500/20 text-red-400', icon: Bug },
  FEATURE: { label: 'Feature', color: 'bg-purple-500/20 text-purple-400', icon: Lightbulb },
  IMPROVEMENT: { label: 'Improvement', color: 'bg-cyan-500/20 text-cyan-400', icon: Wrench }
}

const issuesByStatus = computed(() => {
  const groups: Record<string, Issue[]> = {
    'TODO': [],
    'IN_PROGRESS': [],
    'DONE': []
  }
  localFirst.items.value.forEach((issue: Issue) => {
    const statusGroup = groups[issue.status]
    if (statusGroup) {
      statusGroup.push(issue)
    }
  })
  return groups
})

const draggedIssue = ref<Issue | null>(null)
const dragOverColumn = ref<string | null>(null)

const showSlidePanel = ref(false)
const editingIssue = ref<Issue | null>(null)
const editForm = ref({ 
  title: '', 
  description: '', 
  priority: 'MEDIUM' as IssuePriority,
  type: 'FEATURE' as IssueType
})

function generateTempId(): string {
  return `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

function openNewIssuePanel() {
  editingIssue.value = null
  editForm.value = { title: '', description: '', priority: 'MEDIUM', type: 'FEATURE' }
  showSlidePanel.value = true
}

function openEditPanel(issue: Issue) {
  editingIssue.value = issue
  editForm.value = { 
    title: issue.title, 
    description: issue.description || '', 
    priority: issue.priority,
    type: issue.type
  }
  showSlidePanel.value = true
}

async function saveIssue() {
  if (!editForm.value.title.trim()) return
  
  if (editingIssue.value) {
    await optimistic.optimisticUpdate(
      async (id, updates) => {
        return await $fetch<Issue>(`/api/issues/${id}`, {
          method: 'PATCH',
          body: updates
        })
      },
      editingIssue.value.id,
      { 
        title: editForm.value.title, 
        description: editForm.value.description,
        priority: editForm.value.priority,
        type: editForm.value.type
      }
    )
  } else {
    const tempItem: Issue = {
      id: generateTempId(),
      title: editForm.value.title,
      description: editForm.value.description,
      status: 'TODO',
      priority: editForm.value.priority,
      type: editForm.value.type,
      order: 0,
      projectId,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    }
    
    await optimistic.optimisticAdd(
      async (item) => {
        return await $fetch<Issue>('/api/issues', {
          method: 'POST',
          body: { 
            title: item.title, 
            description: item.description, 
            status: item.status,
            priority: item.priority,
            type: item.type,
            projectId
          }
        })
      },
      tempItem
    )
  }
  
  showSlidePanel.value = false
}

async function deleteIssue(id: string) {
  await optimistic.optimisticDelete(
    async (itemId) => {
      await $fetch(`/api/issues/${itemId}`, { method: 'DELETE' })
    },
    id
  )
  showSlidePanel.value = false
}

function onDragStart(issue: Issue) {
  draggedIssue.value = issue
}

function onDragOver(e: DragEvent, status: string) {
  e.preventDefault()
  dragOverColumn.value = status
}

function onDragLeave() {
  dragOverColumn.value = null
}

async function onDrop(status: string) {
  if (!draggedIssue.value || draggedIssue.value.status === status) {
    draggedIssue.value = null
    dragOverColumn.value = null
    return
  }
  
  await optimistic.optimisticUpdate(
    async (id, updates) => {
      return await $fetch<Issue>(`/api/issues/${id}`, {
        method: 'PATCH',
        body: updates
      })
    },
    draggedIssue.value.id,
    { status: status as IssueStatus }
  )
  
  draggedIssue.value = null
  dragOverColumn.value = null
}

const isExpanding = ref(false)
const expandingIssueId = ref<string | null>(null)

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

async function aiExpand(issue: Issue) {
  isExpanding.value = true
  expandingIssueId.value = issue.id
  
  // Open the slide panel to show streaming output
  editingIssue.value = issue
  editForm.value = { 
    title: issue.title, 
    description: '', 
    priority: issue.priority,
    type: issue.type
  }
  showSlidePanel.value = true
  
  try {
    const aiSettings = getAISettings()
    const response = await fetch('/api/ai/expand', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: issue.title,
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
      // Real-time streaming: update description as chunks arrive
      editForm.value.description = result
    }
    
    // Persist the final result to the server
    await optimistic.optimisticUpdate(
      async (id, updates) => {
        return await $fetch<Issue>(`/api/issues/${id}`, {
          method: 'PATCH',
          body: updates
        })
      },
      issue.id,
      { description: result }
    )
  } catch (e) {
    console.error('AI Expansion failed', e)
  } finally {
    isExpanding.value = false
    expandingIssueId.value = null
  }
}

async function loadProject() {
  try {
    const projects = await $fetch<Project[]>('/api/projects')
    project.value = projects.find(p => p.id === projectId) || null
  } catch (e) {
    console.error('Failed to load project', e)
  }
}

onMounted(async () => {
  await loadProject()
  localFirst.init()
})
</script>

<template>
  <div class="h-full flex flex-col">
    <header class="p-8 border-b border-border bg-card/30 backdrop-blur-md flex items-center justify-between">
      <div class="flex items-center gap-4">
        <NuxtLink 
          to="/projects" 
          class="p-2 hover:bg-muted rounded-lg transition-colors"
        >
          <ArrowLeft class="w-5 h-5" />
        </NuxtLink>
        <div>
          <h1 class="text-3xl font-bold tracking-tight">{{ project?.name || 'Loading...' }}</h1>
          <p v-if="project?.description" class="text-muted-foreground">{{ project.description }}</p>
        </div>
      </div>
      <div class="flex items-center gap-4">
        <div class="flex items-center gap-2 text-xs text-muted-foreground">
          <Cloud v-if="!localFirst.isSyncing.value" class="w-4 h-4" />
          <CloudOff v-else class="w-4 h-4 animate-pulse" />
          <span>{{ localFirst.isSyncing.value ? 'Syncing...' : 'Synced' }}</span>
        </div>
        <button
          @click="openNewIssuePanel"
          class="flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-all"
        >
          <Plus class="w-4 h-4" />
          New Issue
        </button>
      </div>
    </header>

    <div class="flex-1 overflow-x-auto p-8">
      <div class="flex gap-6 min-w-max h-full">
        <div
          v-for="col in columns"
          :key="col.status"
          class="w-80 flex flex-col bg-secondary/20 rounded-2xl border border-border/50 transition-all"
          :class="{ 'ring-2 ring-primary/50 bg-primary/5': dragOverColumn === col.status }"
          @dragover="onDragOver($event, col.status)"
          @dragleave="onDragLeave"
          @drop="onDrop(col.status)"
        >
          <div class="p-4 flex items-center justify-between">
            <h3 class="font-semibold text-sm flex items-center gap-2">
              <span class="w-2 h-2 rounded-full" :class="col.color"></span>
              {{ col.label }}
              <span class="text-muted-foreground text-xs bg-muted px-1.5 py-0.5 rounded ml-2">
                {{ issuesByStatus[col.status]?.length || 0 }}
              </span>
            </h3>
          </div>

          <div class="flex-1 p-4 space-y-4 overflow-y-auto">
            <div
              v-for="issue in issuesByStatus[col.status] || []"
              :key="issue.id"
              draggable="true"
              @dragstart="onDragStart(issue)"
              @click="openEditPanel(issue)"
              class="group relative bg-card border border-border rounded-xl p-4 shadow-sm hover:shadow-md transition-all hover:border-border/80 cursor-pointer"
              :class="{ 'opacity-50': optimistic.isPending(issue.id) }"
            >
              <div class="flex items-start justify-between mb-2">
                <span class="text-sm font-medium leading-tight flex-1">{{ issue.title }}</span>
                <GripVertical class="w-3 h-3 text-muted-foreground opacity-0 group-hover:opacity-100 flex-shrink-0" />
              </div>
              
              <div v-if="issue.description" class="text-xs text-muted-foreground line-clamp-2 mb-3">
                {{ issue.description }}
              </div>

              <div class="flex items-center gap-2 flex-wrap">
                <span :class="typeConfig[issue.type]?.color" class="text-[10px] px-2 py-0.5 rounded-full flex items-center gap-1">
                  <component :is="typeConfig[issue.type]?.icon" class="w-3 h-3" />
                  {{ typeConfig[issue.type]?.label }}
                </span>
                <component 
                  :is="priorityConfig[issue.priority]?.icon" 
                  :class="priorityConfig[issue.priority]?.color" 
                  class="w-3 h-3" 
                />
              </div>

              <div class="flex items-center gap-2 mt-3">
                <button
                  @click.stop="aiExpand(issue)"
                  :disabled="isExpanding"
                  class="flex items-center gap-1.5 px-2 py-1 rounded-md bg-primary/5 text-[10px] font-bold text-primary hover:bg-primary/10 transition-all border border-primary/20"
                >
                  <Sparkles class="w-3 h-3" :class="{ 'animate-pulse': isExpanding }" />
                  AI EXPAND
                </button>
              </div>
            </div>
            
            <div v-if="(issuesByStatus[col.status]?.length || 0) === 0" class="border-2 border-dashed border-border/30 rounded-xl h-24 flex items-center justify-center text-xs text-muted-foreground/50">
              Drop issues here
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
                <h2 class="text-lg font-semibold">{{ editingIssue ? 'Edit Issue' : 'New Issue' }}</h2>
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
                    placeholder="Issue title..."
                    class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all"
                  />
                </div>
                
                <div>
                  <div class="flex items-center justify-between mb-2">
                    <label class="block text-sm font-medium">Description</label>
                    <button
                      v-if="editingIssue && !isExpanding"
                      @click="aiExpand(editingIssue)"
                      class="flex items-center gap-1.5 px-2 py-1 rounded-md bg-primary/5 text-[10px] font-bold text-primary hover:bg-primary/10 transition-all border border-primary/20"
                    >
                      <Sparkles class="w-3 h-3" />
                      AI EXPAND
                    </button>
                    <span v-if="isExpanding" class="flex items-center gap-1.5 text-[10px] text-primary animate-pulse">
                      <Sparkles class="w-3 h-3" />
                      Generating...
                    </span>
                  </div>
                  <textarea
                    v-model="editForm.description"
                    rows="10"
                    placeholder="Issue description (Markdown supported)..."
                    class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all resize-none font-mono text-sm"
                    :class="{ 'border-primary/30 ring-1 ring-primary/20': isExpanding }"
                    :readonly="isExpanding"
                  ></textarea>
                </div>

                <div class="grid grid-cols-2 gap-4">
                  <div>
                    <label class="block text-sm font-medium mb-2">Priority</label>
                    <select
                      v-model="editForm.priority"
                      class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all"
                    >
                      <option value="LOW">Low</option>
                      <option value="MEDIUM">Medium</option>
                      <option value="HIGH">High</option>
                      <option value="URGENT">Urgent</option>
                    </select>
                  </div>
                  
                  <div>
                    <label class="block text-sm font-medium mb-2">Type</label>
                    <select
                      v-model="editForm.type"
                      class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all"
                    >
                      <option value="BUG">Bug</option>
                      <option value="FEATURE">Feature</option>
                      <option value="IMPROVEMENT">Improvement</option>
                    </select>
                  </div>
                </div>
              </div>
              
              <div class="p-6 border-t border-border flex items-center justify-between">
                <button
                  v-if="editingIssue"
                  @click="deleteIssue(editingIssue.id)"
                  class="px-4 py-2 text-sm text-destructive hover:bg-destructive/10 rounded-lg transition-all"
                >
                  Delete
                </button>
                <div v-else></div>
                <button
                  @click="saveIssue"
                  :disabled="!editForm.title.trim()"
                  class="px-6 py-2 bg-primary text-primary-foreground rounded-lg font-medium hover:opacity-90 transition-all disabled:opacity-50"
                >
                  {{ editingIssue ? 'Save Changes' : 'Create Issue' }}
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
