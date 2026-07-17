<script setup lang="ts">
import { Plus, GripVertical, Sparkles, Cloud, CloudOff, ArrowLeft, Bug, Lightbulb, Wrench, AlertCircle, Circle, CircleDot, CircleCheck } from 'lucide-vue-next'
import IssueCreator from '@/components/IssueCreator.vue'

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
const api = useApi()
const localFirst = useLocalFirst<Issue>(`issues_${projectId}`, () =>
  api<Issue[]>('/api/issues', { query: { projectId } })
)
const optimistic = useOptimistic(localFirst)

const columns: { status: string; label: string; color: string }[] = [
  { status: 'TODO', label: 'Todo', color: 'bg-blue-500' },
  { status: 'IN_PROGRESS', label: 'In Progress', color: 'bg-yellow-500' },
  { status: 'DONE', label: 'Done', color: 'bg-green-500' }
]

const statusConfig: Record<string, { label: string; color: string; icon: any }> = {
  TODO: { label: 'Todo', color: 'text-gray-400', icon: Circle },
  IN_PROGRESS: { label: 'In Progress', color: 'text-yellow-400', icon: CircleDot },
  DONE: { label: 'Done', color: 'text-green-400', icon: CircleCheck }
}

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

const showIssueCreator = ref(false)

function generateTempId(): string {
  return `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

function openNewIssuePanel() {
  showIssueCreator.value = true
}

async function handleIssueCreate(issueData: {
  title: string
  description: string
  status: string
  priority: string
  type: string
  projectId: string | null
}) {
  const tempItem: Issue = {
    id: generateTempId(),
    title: issueData.title,
    description: issueData.description,
    status: issueData.status as IssueStatus || 'TODO',
    priority: issueData.priority as IssuePriority,
    type: issueData.type as IssueType,
    order: 0,
    projectId,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }

  await optimistic.optimisticAdd(
    async (item) => {
      return await api<Issue>('/api/issues', {
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

  // 不跳转，留在看板页面
  showIssueCreator.value = false
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
      return await api<Issue>(`/api/issues/${id}`, {
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

async function aiExpand(issue: Issue) {
  isExpanding.value = true
  expandingIssueId.value = issue.id

  try {
    const { public: { apiBase } } = useRuntimeConfig()
    const response = await fetch(`${apiBase}/api/agent/expand`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: issue.title })
    })
    const reader = response.body?.getReader()
    if (!reader) return

    let raw = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      raw += new TextDecoder().decode(value)
    }

    // /api/agent/expand 返回 SSE（data:<chunk>\n\n），剥离帧还原纯文本
    const result = raw
      .split('\n')
      .filter(l => l.startsWith('data:'))
      .map(l => l.slice(5))
      .join('')

    await optimistic.optimisticUpdate(
      async (id, updates) => {
        return await api<Issue>(`/api/issues/${id}`, {
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
    const projects = await api<Project[]>('/api/projects')
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
        <IssueCreator
          v-model:open="showIssueCreator"
          :project-id="projectId"
          @create="handleIssueCreate"
        >
          <template #trigger>
            <button
              class="flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-all"
            >
              <Plus class="w-4 h-4" />
              New Issue
            </button>
          </template>
        </IssueCreator>
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
            <NuxtLink
              v-for="issue in issuesByStatus[col.status] || []"
              :key="issue.id"
              :to="`/issues/${issue.id}`"
              draggable="true"
              @dragstart="onDragStart(issue)"
              class="group relative bg-card border border-border rounded-xl p-4 shadow-sm hover:shadow-md transition-all hover:border-border/80 cursor-pointer block"
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
                <component
                  :is="statusConfig[issue.status]?.icon"
                  :class="statusConfig[issue.status]?.color"
                  class="w-3.5 h-3.5"
                />
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
                  @click.prevent.stop="aiExpand(issue)"
                  :disabled="isExpanding"
                  class="flex items-center gap-1.5 px-2 py-1 rounded-md bg-primary/5 text-[10px] font-bold text-primary hover:bg-primary/10 transition-all border border-primary/20"
                >
                  <Sparkles class="w-3 h-3" :class="{ 'animate-pulse': isExpanding }" />
                  AI EXPAND
                </button>
              </div>
            </NuxtLink>
            
            <div v-if="(issuesByStatus[col.status]?.length || 0) === 0" class="border-2 border-dashed border-border/30 rounded-xl h-24 flex items-center justify-center text-xs text-muted-foreground/50">
              Drop issues here
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
