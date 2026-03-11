<script setup lang="ts">
import { ref, computed } from 'vue'
import { Plus, Maximize2, X, Paperclip, ChevronDown, Circle, SignalLow, SignalMedium, Signal, AlertTriangle, Bug, Lightbulb, Zap } from 'lucide-vue-next'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  DialogClose,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Button } from '@/components/ui/button'

const isOpen = defineModel('open', { type: Boolean, default: false })
const title = ref('')
const description = ref('')

const status = ref('TODO')
const priority = ref('NONE')
const issueType = ref('FEATURE')
const projectId = ref(null as string | null)

const emit = defineEmits(['create'])

const statusOptions = [
  { value: 'BACKLOG', label: 'Backlog', color: 'text-muted-foreground' },
  { value: 'TODO', label: 'Todo', color: 'text-muted-foreground' },
  { value: 'IN_PROGRESS', label: 'In Progress', color: 'text-yellow-400' },
  { value: 'DONE', label: 'Done', color: 'text-green-400' },
  { value: 'CANCELLED', label: 'Cancelled', color: 'text-red-400' },
]

const priorityOptions = [
  { value: 'NONE', label: 'No priority', icon: '—' },
  { value: 'LOW', label: 'Low', icon: '↓' },
  { value: 'MEDIUM', label: 'Medium', icon: '→' },
  { value: 'HIGH', label: 'High', icon: '↑' },
  { value: 'URGENT', label: 'Urgent', icon: '⚡' },
]

const typeOptions = [
  { value: 'FEATURE', label: 'Feature', emoji: '✨' },
  { value: 'BUG', label: 'Bug', emoji: '🐛' },
  { value: 'IMPROVEMENT', label: 'Improvement', emoji: '🔧' },
]

const currentStatus = computed(() => statusOptions.find(o => o.value === status.value))
const currentPriority = computed(() => priorityOptions.find(o => o.value === priority.value))
const currentType = computed(() => typeOptions.find(o => o.value === issueType.value))

const { data: projects } = useAsyncData('projects', () => $fetch('/api/projects'))
const currentProject = computed(() => projects.value?.find((p: any) => p.id === projectId.value))

function handleCreate() {
  if (!title.value.trim()) return

  emit('create', {
    title: title.value,
    description: description.value,
    status: status.value,
    priority: priority.value,
    type: issueType.value,
    projectId: projectId.value
  })

  // Reset and close
  title.value = ''
  description.value = ''
  status.value = 'TODO'
  priority.value = 'NONE'
  issueType.value = 'FEATURE'
  projectId.value = null
  isOpen.value = false
}
</script>

<template>
  <Dialog v-model:open="isOpen">
    <DialogTrigger as-child>
      <slot>
        <Button class="bg-[#5e6ad2] hover:bg-[#4b54a8] text-white">
          <Plus class="w-4 h-4 mr-2" />
          Create new issue
        </Button>
      </slot>
    </DialogTrigger>
    <DialogContent class="sm:max-w-[700px] p-0 gap-0 bg-[#1c1c1e] border-border text-foreground overflow-hidden shadow-2xl origin-center data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[state=closed]:slide-out-to-left-1/2 data-[state=closed]:slide-out-to-top-[48%] data-[state=open]:slide-in-from-left-1/2 data-[state=open]:slide-in-from-top-[48%] duration-200">
      <DialogHeader class="px-5 py-4 flex flex-row items-center justify-between border-b border-border/40">
        <DialogTitle class="text-sm font-medium">New issue</DialogTitle>
        <div class="flex items-center gap-1">
          <DialogClose as-child>
            <Button variant="ghost" size="icon" class="h-6 w-6 text-muted-foreground hover:text-foreground">
              <X class="h-4 w-4" />
            </Button>
          </DialogClose>
        </div>
      </DialogHeader>

      <div class="px-5 py-4 space-y-4">
        <input
          v-model="title"
          type="text"
          placeholder="Issue title"
          class="w-full bg-transparent border-none text-xl font-medium focus:ring-0 focus:outline-none p-0 placeholder:text-muted-foreground/60"
        />
        <textarea
          v-model="description"
          placeholder="Add description..."
          class="w-full bg-transparent border-none text-sm resize-none min-h-[100px] focus:ring-0 focus:outline-none p-0 placeholder:text-muted-foreground/60"
        ></textarea>
      </div>

      <div class="px-5 pb-4 flex items-center gap-2 overflow-x-auto">
        <!-- Status Dropdown -->
        <DropdownMenu>
          <DropdownMenuTrigger as-child>
            <Button variant="outline" size="sm" class="h-7 px-2.5 text-xs bg-secondary/20 border-border/40 hover:bg-secondary/40 whitespace-nowrap">
              <span class="w-3 h-3 rounded-full border border-dashed border-muted-foreground mr-1.5"></span>
              {{ currentStatus?.label || 'Status' }}
              <ChevronDown class="w-3 h-3 ml-1 text-muted-foreground" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" class="w-40 bg-[#1c1c1e] border-border">
            <DropdownMenuItem
              v-for="opt in statusOptions"
              :key="opt.value"
              :class="[opt.color, status === opt.value ? 'bg-secondary/40' : '']"
              class="text-xs cursor-pointer"
              @click="status = opt.value"
            >
              <span class="w-2 h-2 rounded-full mr-2" :class="opt.color.replace('text-', 'bg-')"></span>
              {{ opt.label }}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>

        <!-- Priority Dropdown -->
        <DropdownMenu>
          <DropdownMenuTrigger as-child>
            <Button variant="outline" size="sm" class="h-7 px-2.5 text-xs bg-secondary/20 border-border/40 hover:bg-secondary/40 whitespace-nowrap">
              <span class="mr-1.5">{{ currentPriority?.icon || '—' }}</span>
              {{ currentPriority?.label || 'Priority' }}
              <ChevronDown class="w-3 h-3 ml-1 text-muted-foreground" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" class="w-40 bg-[#1c1c1e] border-border">
            <DropdownMenuItem
              v-for="opt in priorityOptions"
              :key="opt.value"
              :class="priority === opt.value ? 'bg-secondary/40' : ''"
              class="text-xs cursor-pointer"
              @click="priority = opt.value"
            >
              <span class="mr-2">{{ opt.icon }}</span>
              {{ opt.label }}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>

        <!-- Type Dropdown -->
        <DropdownMenu>
          <DropdownMenuTrigger as-child>
            <Button variant="outline" size="sm" class="h-7 px-2.5 text-xs bg-secondary/20 border-border/40 hover:bg-secondary/40 whitespace-nowrap">
              <span class="mr-1.5">{{ currentType?.emoji || '✨' }}</span>
              {{ currentType?.label || 'Type' }}
              <ChevronDown class="w-3 h-3 ml-1 text-muted-foreground" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" class="w-40 bg-[#1c1c1e] border-border">
            <DropdownMenuItem
              v-for="opt in typeOptions"
              :key="opt.value"
              :class="issueType === opt.value ? 'bg-secondary/40' : ''"
              class="text-xs cursor-pointer"
              @click="issueType = opt.value"
            >
              <span class="mr-2">{{ opt.emoji }}</span>
              {{ opt.label }}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>

        <!-- Project Dropdown -->
        <DropdownMenu>
          <DropdownMenuTrigger as-child>
            <Button variant="outline" size="sm" class="h-7 px-2.5 text-xs bg-secondary/20 border-border/40 hover:bg-secondary/40 whitespace-nowrap">
              <span class="text-muted-foreground mr-1.5">⬡</span>
              {{ currentProject?.name || 'Project' }}
              <ChevronDown class="w-3 h-3 ml-1 text-muted-foreground" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" class="w-40 bg-[#1c1c1e] border-border max-h-48 overflow-y-auto">
            <DropdownMenuItem
              :class="projectId === null ? 'bg-secondary/40' : ''"
              class="text-xs cursor-pointer text-muted-foreground"
              @click="projectId = null"
            >
              No project
            </DropdownMenuItem>
            <DropdownMenuItem
              v-for="project in projects || []"
              :key="project.id"
              :class="projectId === project.id ? 'bg-secondary/40' : ''"
              class="text-xs cursor-pointer"
              @click="projectId = project.id"
            >
              {{ project.name }}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      <div class="px-5 py-3 bg-[#18181a] border-t border-border/40 flex items-center justify-between">
        <Button variant="ghost" size="icon" class="h-7 w-7 text-muted-foreground hover:text-foreground hover:bg-secondary/40">
          <Paperclip class="h-4 w-4" />
        </Button>
        <div class="flex items-center gap-3">
          <div class="flex items-center gap-2 text-xs text-muted-foreground">
            <input type="checkbox" class="rounded bg-secondary/40 border-border/40 accent-primary" />
            Create more
          </div>
          <Button
            @click="handleCreate"
            :disabled="!title.trim()"
            class="bg-[#5e6ad2] hover:bg-[#4b54a8] text-white h-7 px-3 text-xs font-medium"
          >
            Create issue
          </Button>
        </div>
      </div>
    </DialogContent>
  </Dialog>
</template>
