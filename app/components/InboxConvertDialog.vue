<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { X, ChevronDown, ArrowRightLeft, FolderPlus } from 'lucide-vue-next'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogClose,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Button } from '@/components/ui/button'

const props = defineProps<{
  open: boolean
  itemId: string | null
  itemContent: string
  mode: 'issue' | 'project'
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'update:mode': [value: 'issue' | 'project']
  convertToIssue: [data: { title: string; description: string; priority: string; type: string; projectId: string | null; deleteOriginal: boolean }]
  convertToProject: [data: { name: string; description: string; deleteOriginal: boolean }]
}>()

const isOpen = computed({
  get: () => props.open,
  set: (val) => emit('update:open', val)
})

const currentMode = computed({
  get: () => props.mode,
  set: (val) => emit('update:mode', val)
})

// Issue fields
const issueTitle = ref('')
const issueDescription = ref('')
const issuePriority = ref('MEDIUM')
const issueType = ref('FEATURE')
const issueProjectId = ref<string | null>(null)

// Project fields
const projectName = ref('')
const projectDescription = ref('')

// Common
const deleteOriginal = ref(true)

// Options
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

const { data: projects } = useAsyncData('projects-for-convert', () => $fetch('/api/projects'))

const currentPriority = computed(() => priorityOptions.find(o => o.value === issuePriority.value))
const currentType = computed(() => typeOptions.find(o => o.value === issueType.value))
const currentProject = computed(() => projects.value?.find((p: any) => p.id === issueProjectId.value))

// Initialize title from item content
watch(() => props.itemContent, (content) => {
  if (content) {
    issueTitle.value = content
    projectName.value = content
  }
}, { immediate: true })

function handleConvertToIssue() {
  emit('convertToIssue', {
    title: issueTitle.value,
    description: issueDescription.value,
    priority: issuePriority.value,
    type: issueType.value,
    projectId: issueProjectId.value,
    deleteOriginal: deleteOriginal.value
  })
  resetAndClose()
}

function handleConvertToProject() {
  emit('convertToProject', {
    name: projectName.value,
    description: projectDescription.value,
    deleteOriginal: deleteOriginal.value
  })
  resetAndClose()
}

function resetAndClose() {
  issueTitle.value = ''
  issueDescription.value = ''
  issuePriority.value = 'MEDIUM'
  issueType.value = 'FEATURE'
  issueProjectId.value = null
  projectName.value = ''
  projectDescription.value = ''
  deleteOriginal.value = true
  isOpen.value = false
}
</script>

<template>
  <Dialog v-model:open="isOpen">
    <DialogContent class="sm:max-w-[500px] p-0 gap-0 bg-[#1c1c1e] border-border text-foreground overflow-hidden shadow-2xl">
      <DialogHeader class="px-5 py-4 flex flex-row items-center justify-between border-b border-border/40">
        <DialogTitle class="text-sm font-medium flex items-center gap-2">
          <ArrowRightLeft class="w-4 h-4 text-muted-foreground" />
          Convert Inbox Item
        </DialogTitle>
        <DialogClose as-child>
          <Button variant="ghost" size="icon" class="h-6 w-6 text-muted-foreground hover:text-foreground">
            <X class="h-4 w-4" />
          </Button>
        </DialogClose>
      </DialogHeader>

      <!-- Mode Toggle -->
      <div class="px-5 pt-4">
        <div class="flex gap-1 p-1 bg-secondary/20 rounded-lg">
          <button
            @click="currentMode = 'issue'"
            class="flex-1 flex items-center justify-center gap-1.5 px-3 py-1.5 text-xs rounded-md transition-colors"
            :class="currentMode === 'issue' ? 'bg-[#5e6ad2] text-white' : 'text-muted-foreground hover:text-foreground'"
          >
            <ArrowRightLeft class="w-3 h-3" />
            Issue
          </button>
          <button
            @click="currentMode = 'project'"
            class="flex-1 flex items-center justify-center gap-1.5 px-3 py-1.5 text-xs rounded-md transition-colors"
            :class="currentMode === 'project' ? 'bg-[#5e6ad2] text-white' : 'text-muted-foreground hover:text-foreground'"
          >
            <FolderPlus class="w-3 h-3" />
            Project
          </button>
        </div>
      </div>

      <!-- Issue Form -->
      <div v-if="currentMode === 'issue'" class="px-5 py-4 space-y-4">
        <input
          v-model="issueTitle"
          type="text"
          placeholder="Issue title"
          class="w-full bg-secondary/20 border border-border/40 rounded-lg px-3 py-2 text-sm focus:ring-1 focus:ring-primary focus:border-primary transition-all placeholder:text-muted-foreground/60"
        />
        <textarea
          v-model="issueDescription"
          placeholder="Description (optional)"
          class="w-full bg-secondary/20 border border-border/40 rounded-lg px-3 py-2 text-sm resize-none min-h-[80px] focus:ring-1 focus:ring-primary focus:border-primary transition-all placeholder:text-muted-foreground/60"
        ></textarea>

        <!-- Properties -->
        <div class="flex flex-wrap gap-2">
          <!-- Priority -->
          <DropdownMenu>
            <DropdownMenuTrigger as-child>
              <Button variant="outline" size="sm" class="h-7 px-2.5 text-xs bg-secondary/20 border-border/40 hover:bg-secondary/40">
                <span class="mr-1.5">{{ currentPriority?.icon }}</span>
                {{ currentPriority?.label }}
                <ChevronDown class="w-3 h-3 ml-1 text-muted-foreground" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" class="w-36 bg-[#1c1c1e] border-border">
              <DropdownMenuItem
                v-for="opt in priorityOptions"
                :key="opt.value"
                :class="issuePriority === opt.value ? 'bg-secondary/40' : ''"
                class="text-xs cursor-pointer"
                @click="issuePriority = opt.value"
              >
                {{ opt.icon }} {{ opt.label }}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>

          <!-- Type -->
          <DropdownMenu>
            <DropdownMenuTrigger as-child>
              <Button variant="outline" size="sm" class="h-7 px-2.5 text-xs bg-secondary/20 border-border/40 hover:bg-secondary/40">
                <span class="mr-1.5">{{ currentType?.emoji }}</span>
                {{ currentType?.label }}
                <ChevronDown class="w-3 h-3 ml-1 text-muted-foreground" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" class="w-36 bg-[#1c1c1e] border-border">
              <DropdownMenuItem
                v-for="opt in typeOptions"
                :key="opt.value"
                :class="issueType === opt.value ? 'bg-secondary/40' : ''"
                class="text-xs cursor-pointer"
                @click="issueType = opt.value"
              >
                {{ opt.emoji }} {{ opt.label }}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>

          <!-- Project -->
          <DropdownMenu>
            <DropdownMenuTrigger as-child>
              <Button variant="outline" size="sm" class="h-7 px-2.5 text-xs bg-secondary/20 border-border/40 hover:bg-secondary/40">
                <span class="text-muted-foreground mr-1.5">⬡</span>
                {{ currentProject?.name || 'Project' }}
                <ChevronDown class="w-3 h-3 ml-1 text-muted-foreground" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" class="w-36 bg-[#1c1c1e] border-border max-h-40 overflow-y-auto">
              <DropdownMenuItem
                :class="issueProjectId === null ? 'bg-secondary/40' : ''"
                class="text-xs cursor-pointer text-muted-foreground"
                @click="issueProjectId = null"
              >
                No project
              </DropdownMenuItem>
              <DropdownMenuItem
                v-for="project in projects || []"
                :key="project.id"
                :class="issueProjectId === project.id ? 'bg-secondary/40' : ''"
                class="text-xs cursor-pointer"
                @click="issueProjectId = project.id"
              >
                {{ project.name }}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>

      <!-- Project Form -->
      <div v-else class="px-5 py-4 space-y-4">
        <input
          v-model="projectName"
          type="text"
          placeholder="Project name"
          class="w-full bg-secondary/20 border border-border/40 rounded-lg px-3 py-2 text-sm focus:ring-1 focus:ring-primary focus:border-primary transition-all placeholder:text-muted-foreground/60"
        />
        <textarea
          v-model="projectDescription"
          placeholder="Description (optional)"
          class="w-full bg-secondary/20 border border-border/40 rounded-lg px-3 py-2 text-sm resize-none min-h-[80px] focus:ring-1 focus:ring-primary focus:border-primary transition-all placeholder:text-muted-foreground/60"
        ></textarea>
      </div>

      <!-- Footer -->
      <div class="px-5 py-3 bg-[#18181a] border-t border-border/40 flex items-center justify-between">
        <label class="flex items-center gap-2 text-xs text-muted-foreground cursor-pointer">
          <input
            v-model="deleteOriginal"
            type="checkbox"
            class="rounded bg-secondary/40 border-border/40 accent-primary"
          />
          Delete original after convert
        </label>
        <Button
          v-if="currentMode === 'issue'"
          @click="handleConvertToIssue"
          :disabled="!issueTitle.trim()"
          class="bg-[#5e6ad2] hover:bg-[#4b54a8] text-white h-7 px-3 text-xs font-medium"
        >
          Convert to Issue
        </Button>
        <Button
          v-else
          @click="handleConvertToProject"
          :disabled="!projectName.trim()"
          class="bg-[#5e6ad2] hover:bg-[#4b54a8] text-white h-7 px-3 text-xs font-medium"
        >
          Convert to Project
        </Button>
      </div>
    </DialogContent>
  </Dialog>
</template>