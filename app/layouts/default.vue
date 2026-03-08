<script setup lang="ts">
import { Inbox, Kanban, CheckSquare, BookOpen, Settings, Command } from 'lucide-vue-next'

const navItems = [
  { name: 'Inbox', path: '/', icon: Inbox },
  { name: 'Todo', path: '/todo', icon: CheckSquare },
  { name: 'Kanban', path: '/kanban', icon: Kanban },
  { name: 'Knowledge', path: '/knowledge', icon: BookOpen },
]

const commandPaletteRef = ref<InstanceType<typeof CommandPalette> | null>(null)
const route = useRoute()

async function handleCapture(content: string) {
  await $fetch('/api/inbox', {
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

function handleCreateKanban() {
  navigateTo('/kanban')
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

      <nav class="flex-1 px-4 space-y-1">
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
      @create-kanban="handleCreateKanban"
      @create-knowledge="handleCreateKnowledge"
    />
  </div>
</template>

<style scoped>
.router-link-active {
  position: relative;
}
</style>
