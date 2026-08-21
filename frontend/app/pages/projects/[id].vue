<script setup lang="ts">
import { Plus, GripVertical, Sparkles, Cloud, CloudOff, ArrowLeft, Bug, Lightbulb, Wrench, AlertCircle, Circle, CircleDot, CircleCheck, CircleX, FolderGit2 } from 'lucide-vue-next'
import IssueCreator from '@/components/IssueCreator.vue'

type IssueStatus = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'
type IssuePriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
type IssueType = 'BUG' | 'FEATURE' | 'IMPROVEMENT'
type ProjectStatus = 'PLANNING' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED'

interface Project {
  id: string
  name: string
  description: string | null
  repoPath: string | null
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
  { status: 'DONE', label: 'Done', color: 'bg-green-500' },
  { status: 'CANCELLED', label: 'Cancelled', color: 'bg-red-500' }
]

const statusConfig: Record<string, { label: string; color: string; icon: any }> = {
  TODO: { label: 'Todo', color: 'text-gray-400', icon: Circle },
  IN_PROGRESS: { label: 'In Progress', color: 'text-yellow-400', icon: CircleDot },
  DONE: { label: 'Done', color: 'text-green-400', icon: CircleCheck },
  CANCELLED: { label: 'Cancelled', color: 'text-red-400', icon: CircleX }
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
    'DONE': [],
    'CANCELLED': []
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

// AI 扩写失败：记录失败 issue id，卡片上内联提示（下次尝试自动清除）
const expandError = ref<string | null>(null)
const isExpanding = ref(false)
const expandingIssueId = ref<string | null>(null)

async function aiExpand(issue: Issue) {
  isExpanding.value = true
  expandingIssueId.value = issue.id
  expandError.value = null

  try {
    const { public: { apiBase } } = useRuntimeConfig()
    const response = await fetch(`${apiBase}/api/agent/expand`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: issue.title })
    })
    const reader = response.body?.getReader()
    if (!reader) return

    // 单个 decoder 复用 + stream:true：多字节 UTF-8 字符跨 chunk 时不乱码
    const decoder = new TextDecoder()
    let raw = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      raw += decoder.decode(value, { stream: true })
    }
    raw += decoder.decode()

    // /api/agent/expand 返回 SSE（data:<chunk>\n\n），剥离帧还原纯文本
    const result = raw
      .split('\n')
      .filter(l => l.startsWith('data:'))
      .map(l => l.slice(5))
      .join('')

    // 流中途失败会产出空文本——不能用空串覆盖 description
    if (!result.trim()) {
      throw new Error('AI 扩写返回为空')
    }

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
    expandError.value = issue.id
  } finally {
    isExpanding.value = false
    expandingIssueId.value = null
  }
}

const nameInput = ref('')
const repoPathInput = ref('')

function blurActive(event: KeyboardEvent) {
  (event.target as HTMLInputElement).blur()
}

function cancelNameEdit(event: KeyboardEvent) {
  nameInput.value = project.value?.name ?? ''
  blurActive(event)
}

async function saveName() {
  if (!project.value) return
  const value = nameInput.value.trim()
  if (!value || value === project.value.name) {
    nameInput.value = project.value.name
    return
  }
  try {
    const updated = await api<Project>(`/api/projects/${projectId}`, {
      method: 'PATCH',
      body: { name: value }
    })
    project.value.name = updated.name
  } catch (e) {
    console.error('Failed to rename project', e)
    nameInput.value = project.value.name
  }
}

function cancelRepoPathEdit(event: KeyboardEvent) {
  repoPathInput.value = project.value?.repoPath ?? ''
  blurActive(event)
}

async function saveRepoPath() {
  if (!project.value) return
  const value = repoPathInput.value.trim()
  if (value === (project.value.repoPath ?? '')) return
  try {
    const updated = await api<Project>(`/api/projects/${projectId}`, {
      method: 'PATCH',
      body: { repoPath: value }
    })
    project.value.repoPath = updated.repoPath
  } catch (e) {
    console.error('Failed to save repo path', e)
    repoPathInput.value = project.value.repoPath ?? ''
  }
}

async function loadProject() {
  try {
    const projects = await api<Project[]>('/api/projects')
    project.value = projects.find(p => p.id === projectId) || null
    nameInput.value = project.value?.name ?? ''
    repoPathInput.value = project.value?.repoPath ?? ''
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
          <input
            v-model="nameInput"
            @keydown.enter="blurActive"
            @keydown.esc="cancelNameEdit"
            @blur="saveName"
            type="text"
            placeholder="Project name"
            class="text-3xl font-bold tracking-tight bg-transparent border-none focus:ring-0 focus:outline-none p-0 w-full placeholder:text-muted-foreground/50"
          />
          <p v-if="project?.description" class="text-muted-foreground">{{ project.description }}</p>
          <div v-if="project" class="flex items-center gap-1.5 mt-1">
            <FolderGit2 class="w-3.5 h-3.5 text-muted-foreground flex-shrink-0" />
            <input
              v-model="repoPathInput"
              @keydown.enter="blurActive"
              @keydown.esc="cancelRepoPathEdit"
              @blur="saveRepoPath"
              type="text"
              placeholder="Local repo path (for agent dispatch)"
              class="bg-transparent border-none text-xs text-muted-foreground focus:text-foreground focus:ring-0 focus:outline-none p-0 placeholder:text-muted-foreground/50 w-80"
            />
          </div>
        </div>
      </div>
      <div class="flex items-center gap-4">
        <div class="flex items-center gap-2 text-xs text-muted-foreground">
          <CloudOff v-if="localFirst.isSyncing.value" class="w-4 h-4 animate-pulse" />
          <CloudOff v-else-if="localFirst.syncError.value" class="w-4 h-4 text-destructive" />
          <Cloud v-else class="w-4 h-4" />
          <span :class="{ 'text-destructive': localFirst.syncError.value && !localFirst.isSyncing.value }">{{ localFirst.isSyncing.value ? 'Syncing...' : (localFirst.syncError.value ? 'Sync failed' : 'Synced') }}</span>
        </div>
        <IssueCreator
          v-model:open="showIssueCreator"
          :project-id="projectId"
          @create="handleIssueCreate"
        >
          <button
            class="flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-all"
          >
            <Plus class="w-4 h-4" />
            New Issue
          </button>
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
                <span v-if="expandError === issue.id" class="text-[10px] text-destructive">扩写失败，请重试</span>
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
