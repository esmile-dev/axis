<script setup lang="ts">
import { Plus, FolderOpen, Cloud, CloudOff, MoreHorizontal, Trash2, Archive, Play, Eye } from 'lucide-vue-next'

type ProjectStatus = 'PLANNING' | 'ACTIVE' | 'COMPLETED' | 'ARCHIVED'

interface Project {
  id: string
  name: string
  description: string | null
  status: ProjectStatus
  order: number
  createdAt: string
  updatedAt: string
  issueCount?: number
}

const api = useApi()
const localFirst = useLocalFirst<Project>('projects', () => api<Project[]>('/api/projects'))
const optimistic = useOptimistic(localFirst)

const showSlidePanel = ref(false)
const newProject = ref({ name: '', description: '' })

const statusConfig: Record<string, { label: string; color: string; icon: any }> = {
  PLANNING: { label: 'Planning', color: 'bg-yellow-500/20 text-yellow-400', icon: Eye },
  ACTIVE: { label: 'Active', color: 'bg-blue-500/20 text-blue-400', icon: Play },
  COMPLETED: { label: 'Completed', color: 'bg-green-500/20 text-green-400', icon: Archive },
  ARCHIVED: { label: 'Archived', color: 'bg-gray-500/20 text-gray-400', icon: Archive }
}

function generateTempId(): string {
  return `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

function openCreatePanel() {
  newProject.value = { name: '', description: '' }
  showSlidePanel.value = true
}

async function createProject() {
  if (!newProject.value.name.trim()) return
  
  const tempProject: Project = {
    id: generateTempId(),
    name: newProject.value.name,
    description: newProject.value.description || null,
    status: 'PLANNING',
    order: 0,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }
  
  showSlidePanel.value = false
  newProject.value = { name: '', description: '' }
  
  await optimistic.optimisticAdd(
    async (project) => {
      return await api<Project>('/api/projects', {
        method: 'POST',
        body: {
          name: project.name,
          description: project.description,
          status: project.status
        }
      })
    },
    tempProject
  )
}

async function deleteProject(id: string) {
  await optimistic.optimisticDelete(
    async (projectId) => {
      await api(`/api/projects/${projectId}`, { method: 'DELETE' })
    },
    id
  )
}

async function updateProjectStatus(id: string, status: ProjectStatus) {
  await optimistic.optimisticUpdate(
    async (projectId, updates) => {
      return await api<Project>(`/api/projects/${projectId}`, {
        method: 'PATCH',
        body: updates
      })
    },
    id,
    { status }
  )
}

onMounted(() => localFirst.init())
</script>

<template>
  <div class="p-8 max-w-6xl mx-auto">
    <header class="mb-10 flex items-center justify-between">
      <div>
        <h1 class="text-3xl font-bold tracking-tight mb-2">Projects</h1>
        <p class="text-muted-foreground">Manage your projects and track progress.</p>
      </div>
      <div class="flex items-center gap-4">
        <div class="flex items-center gap-2 text-xs text-muted-foreground">
          <CloudOff v-if="localFirst.isSyncing.value" class="w-4 h-4 animate-pulse" />
          <CloudOff v-else-if="localFirst.syncError.value" class="w-4 h-4 text-destructive" />
          <Cloud v-else class="w-4 h-4" />
          <span :class="{ 'text-destructive': localFirst.syncError.value && !localFirst.isSyncing.value }">{{ localFirst.isSyncing.value ? 'Syncing...' : (localFirst.syncError.value ? 'Sync failed' : 'Synced') }}</span>
        </div>
        <button
          @click="openCreatePanel"
          class="flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-all"
        >
          <Plus class="w-4 h-4" />
          New Project
        </button>
      </div>
    </header>

    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      <NuxtLink
        v-for="project in localFirst.items.value"
        :key="project.id"
        :to="`/projects/${project.id}`"
        class="group p-6 bg-card rounded-xl border border-border hover:border-border/80 transition-all hover:translate-y-[-2px] hover:shadow-lg"
        :class="{ 'opacity-50': optimistic.isPending(project.id) }"
      >
        <div class="flex items-start justify-between mb-4">
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 rounded-lg bg-primary/10 flex items-center justify-center">
              <FolderOpen class="w-5 h-5 text-primary" />
            </div>
            <div>
              <h3 class="font-semibold">{{ project.name }}</h3>
              <span :class="statusConfig[project.status]?.color" class="text-xs px-2 py-0.5 rounded-full">
                {{ statusConfig[project.status]?.label }}
              </span>
            </div>
          </div>
          <div class="opacity-0 group-hover:opacity-100 transition-opacity" @click.prevent.stop>
            <UiDropdownMenu>
              <UiDropdownMenuTrigger as-child>
                <button class="p-1 hover:bg-accent rounded">
                  <MoreHorizontal class="w-4 h-4" />
                </button>
              </UiDropdownMenuTrigger>
              <UiDropdownMenuContent align="end">
                <UiDropdownMenuItem @click="updateProjectStatus(project.id, 'ACTIVE')">
                  <Play class="w-4 h-4 mr-2" /> Start Project
                </UiDropdownMenuItem>
                <UiDropdownMenuItem @click="updateProjectStatus(project.id, 'COMPLETED')">
                  <Archive class="w-4 h-4 mr-2" /> Mark Complete
                </UiDropdownMenuItem>
                <UiDropdownMenuItem @click="updateProjectStatus(project.id, 'ARCHIVED')">
                  <Archive class="w-4 h-4 mr-2" /> Archive
                </UiDropdownMenuItem>
                <UiDropdownMenuItem class="text-destructive" @click="deleteProject(project.id)">
                  <Trash2 class="w-4 h-4 mr-2" /> Delete
                </UiDropdownMenuItem>
              </UiDropdownMenuContent>
            </UiDropdownMenu>
          </div>
        </div>
        
        <p v-if="project.description" class="text-sm text-muted-foreground mb-4 line-clamp-2">
          {{ project.description }}
        </p>
        
        <div class="flex items-center gap-2 text-xs text-muted-foreground">
          <span>{{ project.issueCount ?? 0 }} issues</span>
        </div>
      </NuxtLink>
    </div>

    <div v-if="localFirst.items.value.length === 0" class="text-center py-20 text-muted-foreground">
      <FolderOpen class="w-12 h-12 mx-auto mb-4 opacity-20" />
      <p>No projects yet. Create your first project!</p>
    </div>

    <!-- Slide-over Panel (replaces Dialog per PRD §3.2.2) -->
    <Teleport to="body">
      <Transition name="slide">
        <div v-if="showSlidePanel" class="fixed inset-0 z-50">
          <div class="absolute inset-0 bg-black/50" @click="showSlidePanel = false"></div>
          <div class="absolute right-0 top-0 h-full w-full max-w-md bg-card border-l border-border shadow-xl">
            <div class="flex flex-col h-full">
              <div class="p-6 border-b border-border flex items-center justify-between">
                <h2 class="text-lg font-semibold">New Project</h2>
                <button @click="showSlidePanel = false" class="p-2 hover:bg-muted rounded-lg">
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
              
              <div class="flex-1 p-6 space-y-6 overflow-y-auto">
                <div>
                  <label class="block text-sm font-medium mb-2">Project Name</label>
                  <input
                    v-model="newProject.name"
                    type="text"
                    placeholder="My Awesome Project"
                    class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all"
                    @keyup.enter="createProject"
                  />
                </div>
                
                <div>
                  <label class="block text-sm font-medium mb-2">Description</label>
                  <textarea
                    v-model="newProject.description"
                    rows="4"
                    placeholder="What is this project about?"
                    class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all resize-none"
                  ></textarea>
                </div>
              </div>
              
              <div class="p-6 border-t border-border flex items-center justify-end gap-3">
                <button
                  @click="showSlidePanel = false"
                  class="px-4 py-2 text-sm text-muted-foreground hover:bg-muted rounded-lg transition-all"
                >
                  Cancel
                </button>
                <button
                  @click="createProject"
                  :disabled="!newProject.name.trim()"
                  class="px-6 py-2 bg-primary text-primary-foreground rounded-lg font-medium hover:opacity-90 transition-all disabled:opacity-50"
                >
                  Create Project
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
