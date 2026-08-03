<script setup lang="ts">
import { Inbox, CheckSquare, BookOpen, Settings, Command, FolderKanban, FolderOpen, ChevronDown, ChevronRight, MessageSquare } from 'lucide-vue-next'

interface Project {
  id: string
  name: string
  status: string
}

const navItems = [
  { name: 'Chat', path: '/chat', icon: MessageSquare },
  { name: 'Inbox', path: '/', icon: Inbox },
  { name: 'Todo', path: '/todo', icon: CheckSquare },
  { name: 'Knowledge', path: '/knowledge', icon: BookOpen },
]

const commandPaletteRef = ref<InstanceType<typeof CommandPalette> | null>(null)
const route = useRoute()
const api = useApi()

// Dynamic project list for sidebar
const projects = ref<Project[]>([])
const projectsExpanded = ref(true)

async function loadProjects() {
  try {
    projects.value = await api<Project[]>('/api/projects')
  } catch (e) {
    console.error('Failed to load projects for sidebar:', e)
  }
}

onMounted(() => loadProjects())

// Refresh projects when navigating back from project pages
watch(() => route.path, () => {
  loadProjects()
})

async function handleCapture(content: string) {
  await api('/api/inbox', {
    method: 'POST',
    body: { content }
  })
  if (route.path === '/') {
    window.location.reload()
  }
}

function handleCreateTodo() {
  navigateTo('/todo')
}

function handleCreateProject() {
  navigateTo('/projects')
}

function handleCreateKnowledge() {
  navigateTo('/knowledge')
}
</script>

<template>
  <div class="flex h-screen bg-background text-foreground font-sans antialiased overflow-hidden">
    <!-- Sidebar -->
    <aside class="w-64 border-r border-border flex flex-col bg-card/50 backdrop-blur-sm">
      <div class="p-6 flex items-center gap-3">
        <div class="w-8 h-8 rounded-lg bg-primary flex items-center justify-center">
          <Command class="w-5 h-5 text-primary-foreground" />
        </div>
        <span class="font-semibold text-lg tracking-tight">AI Station</span>
      </div>

      <nav class="flex-1 px-4 space-y-1 overflow-y-auto">
        <NuxtLink
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium transition-all group hover:bg-accent hover:text-accent-foreground"
          active-class="bg-accent text-accent-foreground shadow-sm"
        >
          <component :is="item.icon" class="w-4 h-4 transition-transform group-hover:scale-110" />
          {{ item.name }}
        </NuxtLink>

        <!-- Projects Section -->
        <div class="pt-2">
          <button
            @click="projectsExpanded = !projectsExpanded"
            class="flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium transition-all w-full hover:bg-accent hover:text-accent-foreground group"
          >
            <FolderKanban class="w-4 h-4 transition-transform group-hover:scale-110" />
            <span class="flex-1 text-left">Projects</span>
            <ChevronDown v-if="projectsExpanded" class="w-3 h-3 text-muted-foreground" />
            <ChevronRight v-else class="w-3 h-3 text-muted-foreground" />
          </button>

          <Transition name="expand">
            <div v-if="projectsExpanded" class="ml-4 space-y-0.5 mt-1">
              <NuxtLink
                to="/projects"
                class="flex items-center gap-2 px-3 py-1.5 rounded-md text-xs font-medium text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-all"
                active-class="bg-accent text-accent-foreground"
              >
                All Projects
              </NuxtLink>
              <NuxtLink
                v-for="project in projects"
                :key="project.id"
                :to="`/projects/${project.id}`"
                class="flex items-center gap-2 px-3 py-1.5 rounded-md text-xs font-medium text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-all truncate"
                active-class="bg-accent text-accent-foreground"
              >
                <FolderOpen class="w-3 h-3 flex-shrink-0" />
                <span class="truncate">{{ project.name }}</span>
              </NuxtLink>
            </div>
          </Transition>
        </div>
      </nav>

      <div class="p-4 border-t border-border">
        <div class="flex items-center gap-2 px-3 py-2 text-xs text-muted-foreground">
          <kbd class="px-2 py-1 rounded bg-muted font-mono">⌘K</kbd>
          <span>Quick capture</span>
        </div>
      </div>

      <div class="p-4 border-t border-border mt-auto">
        <NuxtLink 
          to="/settings"
          class="flex items-center gap-3 px-3 py-2 w-full rounded-md text-sm font-medium text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-all"
        >
          <Settings class="w-4 h-4" />
          Settings
        </NuxtLink>
      </div>
    </aside>

    <!-- Main Content -->
    <main class="flex-1 overflow-auto relative">
      <slot />
    </main>

    <!-- Global Command Palette -->
    <CommandPalette
      ref="commandPaletteRef"
      @capture="handleCapture"
      @create-todo="handleCreateTodo"
      @create-project="handleCreateProject"
      @create-knowledge="handleCreateKnowledge"
    />
  </div>
</template>

<style scoped>
.router-link-active {
  position: relative;
}
.expand-enter-active,
.expand-leave-active {
  transition: all 0.2s ease;
  overflow: hidden;
}
.expand-enter-from,
.expand-leave-to {
  opacity: 0;
  max-height: 0;
}
.expand-enter-to,
.expand-leave-from {
  opacity: 1;
  max-height: 500px;
}
</style>
