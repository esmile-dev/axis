<script setup lang="ts">
import { Command, Search, Inbox, CheckSquare, Kanban, BookOpen, Plus } from 'lucide-vue-next'

const isOpen = ref(false)
const searchQuery = ref('')
const inputRef = ref<HTMLInputElement | null>(null)

const commands = [
  { id: 'inbox', label: 'Quick capture to Inbox', icon: Inbox, action: 'capture', color: 'text-blue-500' },
  { id: 'todo', label: 'Create new Todo', icon: CheckSquare, action: 'todo', color: 'text-green-500' },
  { id: 'kanban', label: 'Create Kanban task', icon: Kanban, action: 'kanban', color: 'text-yellow-500' },
  { id: 'knowledge', label: 'Create Knowledge doc', icon: BookOpen, action: 'knowledge', color: 'text-purple-500' },
]

const filteredCommands = computed(() => {
  if (!searchQuery.value) return commands
  const query = searchQuery.value.toLowerCase()
  return commands.filter(cmd => cmd.label.toLowerCase().includes(query))
})

const emit = defineEmits<{
  (e: 'capture', value: string): void
  (e: 'create-todo'): void
  (e: 'create-kanban'): void
  (e: 'create-knowledge'): void
}>()

function open() {
  isOpen.value = true
  searchQuery.value = ''
  nextTick(() => inputRef.value?.focus())
}

function close() {
  isOpen.value = false
  searchQuery.value = ''
}

function executeCommand(cmd: typeof commands[0]) {
  if (cmd.action === 'capture' && searchQuery.value) {
    emit('capture', searchQuery.value)
  } else if (cmd.action === 'todo') {
    emit('create-todo')
  } else if (cmd.action === 'kanban') {
    emit('create-kanban')
  } else if (cmd.action === 'knowledge') {
    emit('create-knowledge')
  }
  close()
}

const selectedIndex = ref(0)

function onKeyDown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    selectedIndex.value = Math.min(selectedIndex.value + 1, filteredCommands.value.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    selectedIndex.value = Math.max(selectedIndex.value - 1, 0)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const selected = filteredCommands.value[selectedIndex.value]
    if (selected) {
      executeCommand(selected)
    }
  } else if (e.key === 'Escape') {
    close()
  }
}

watch(searchQuery, () => {
  selectedIndex.value = 0
})

onMounted(() => {
  const handleGlobalKeydown = (e: KeyboardEvent) => {
    if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
      e.preventDefault()
      open()
    }
  }
  window.addEventListener('keydown', handleGlobalKeydown)
  onUnmounted(() => window.removeEventListener('keydown', handleGlobalKeydown))
})

defineExpose({ open, close })
</script>

<template>
  <Teleport to="body">
    <Transition name="command">
      <div v-if="isOpen" class="fixed inset-0 z-[100]" @click.self="close">
        <div class="absolute inset-0 bg-black/60 backdrop-blur-sm"></div>
        <div class="absolute left-1/2 top-[20%] -translate-x-1/2 w-full max-w-xl">
          <div 
            class="bg-card border border-border rounded-2xl shadow-2xl overflow-hidden"
            @keydown="onKeyDown"
          >
            <div class="flex items-center gap-3 px-4 py-4 border-b border-border">
              <Search class="w-5 h-5 text-muted-foreground" />
              <input
                ref="inputRef"
                v-model="searchQuery"
                type="text"
                placeholder="Type to capture or search commands..."
                class="flex-1 bg-transparent border-none text-lg focus:outline-none placeholder:text-muted-foreground"
              />
              <kbd class="px-2 py-1 rounded bg-muted text-xs font-mono text-muted-foreground">ESC</kbd>
            </div>
            
            <div class="max-h-80 overflow-y-auto p-2">
              <div
                v-for="(cmd, index) in filteredCommands"
                :key="cmd.id"
                @click="executeCommand(cmd)"
                class="flex items-center gap-3 px-4 py-3 rounded-xl cursor-pointer transition-all"
                :class="index === selectedIndex ? 'bg-primary/10 text-primary' : 'hover:bg-muted'"
              >
                <component :is="cmd.icon" class="w-5 h-5" :class="cmd.color" />
                <span class="flex-1 text-sm font-medium">{{ cmd.label }}</span>
                <kbd v-if="index < 4" class="px-1.5 py-0.5 rounded bg-muted text-[10px] font-mono text-muted-foreground">
                  {{ index + 1 }}
                </kbd>
              </div>
              
              <div v-if="filteredCommands.length === 0" class="text-center py-8 text-muted-foreground text-sm">
                No commands found
              </div>
            </div>
            
            <div class="px-4 py-3 border-t border-border flex items-center gap-4 text-xs text-muted-foreground">
              <span class="flex items-center gap-1">
                <kbd class="px-1.5 py-0.5 rounded bg-muted">↑↓</kbd> Navigate
              </span>
              <span class="flex items-center gap-1">
                <kbd class="px-1.5 py-0.5 rounded bg-muted">↵</kbd> Select
              </span>
              <span class="flex items-center gap-1">
                <kbd class="px-1.5 py-0.5 rounded bg-muted">ESC</kbd> Close
              </span>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.command-enter-active,
.command-leave-active {
  transition: all 0.2s ease;
}
.command-enter-from,
.command-leave-to {
  opacity: 0;
}
.command-enter-from .absolute.left-1\/2,
.command-leave-to .absolute.left-1\/2 {
  transform: translate(-50%, -20px);
  opacity: 0;
}
</style>
