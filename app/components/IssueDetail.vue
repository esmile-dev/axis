<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { X, Send, Trash2, ChevronDown, Paperclip, MessageSquare } from 'lucide-vue-next'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Button } from '@/components/ui/button'

const props = defineProps<{
  issueId: string | null
}>()

const emit = defineEmits(['deleted'])

const isOpen = defineModel('open', { type: Boolean, default: false })

const issue = ref<any>(null)
const comments = ref<any[]>([])
const newComment = ref('')
const isSaving = ref(false)
const isDeleting = ref(false)
const showDeleteConfirm = ref(false)

// Selectors data (reusing from IssueCreator)
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

const { data: projects } = useAsyncData('projects', () => $fetch('/api/projects'))

const currentStatus = computed(() => statusOptions.find(o => o.value === issue.value?.status))
const currentPriority = computed(() => priorityOptions.find(o => o.value === issue.value?.priority))
const currentType = computed(() => typeOptions.find(o => o.value === issue.value?.type))
const currentProject = computed(() => projects.value?.find((p: any) => p.id === issue.value?.projectId))

watch(() => props.issueId, async (newId) => {
  if (newId && isOpen.value) {
    await loadIssue(newId)
  }
})

watch(isOpen, async (open) => {
  if (open && props.issueId) {
    await loadIssue(props.issueId)
  } else {
    issue.value = null
    comments.value = []
  }
})

async function loadIssue(id: string) {
  try {
    const data = await $fetch(`/api/issues/${id}`)
    issue.value = data
    comments.value = data.comments || []
  } catch (err) {
    console.error('Failed to load issue:', err)
  }
}

let saveTimeout: any = null
function queueSave() {
  if (saveTimeout) clearTimeout(saveTimeout)
  saveTimeout = setTimeout(() => {
    saveIssue()
  }, 500) // Debounce saves by 500ms
}

async function saveIssue() {
  if (!issue.value || !props.issueId) return
  isSaving.value = true
  try {
    await $fetch(`/api/issues/${props.issueId}`, {
      method: 'PATCH',
      body: {
        title: issue.value.title,
        description: issue.value.description,
        status: issue.value.status,
        priority: issue.value.priority,
        type: issue.value.type,
        projectId: issue.value.projectId
      }
    })
  } catch (err) {
    console.error('Failed to save issue:', err)
  } finally {
    isSaving.value = false
  }
}

async function updateField(field: string, value: any) {
  if (issue.value) {
    issue.value[field] = value
    await saveIssue() // immediate save for dropdowns
  }
}

async function addComment() {
  if (!newComment.value.trim() || !props.issueId) return
  
  try {
    const comment = await $fetch(`/api/issues/${props.issueId}/comments`, {
      method: 'POST',
      body: { content: newComment.value }
    })
    comments.value.unshift(comment)
    newComment.value = ''
  } catch (err) {
    console.error('Failed to add comment:', err)
  }
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
  })
}

async function deleteIssue() {
  if (!props.issueId || isDeleting.value) return
  isDeleting.value = true
  try {
    await $fetch(`/api/issues/${props.issueId}`, { method: 'DELETE' })
    showDeleteConfirm.value = false
    isOpen.value = false
    emit('deleted', props.issueId)
  } catch (err) {
    console.error('Failed to delete issue:', err)
  } finally {
    isDeleting.value = false
  }
}
</script>

<template>
  <Sheet v-model:open="isOpen">
    <SheetContent class="w-full sm:max-w-[600px] p-0 bg-[#1c1c1e] border-l border-border text-foreground flex flex-col h-full shadow-2xl">
      <SheetHeader class="px-6 py-4 border-b border-border/40 flex-shrink-0 flex flex-row items-center justify-between">
        <SheetTitle class="text-sm font-medium text-muted-foreground flex items-center gap-2">
          <span>{{ currentProject?.name || 'Inbox' }}</span>
          <span class="text-border">/</span>
          <span>Issue {{ issue?.id?.slice(-4).toUpperCase() || '...' }}</span>
        </SheetTitle>
        <div class="flex items-center gap-2">
          <span v-if="isSaving" class="text-xs text-muted-foreground animate-pulse">Saving...</span>
          <Button
            variant="ghost"
            size="icon"
            class="h-6 w-6 text-muted-foreground hover:text-red-400"
            @click="showDeleteConfirm = true"
          >
            <Trash2 class="h-4 w-4" />
          </Button>
          <Button variant="ghost" size="icon" class="h-6 w-6 text-muted-foreground hover:text-foreground" :class="{ 'opacity-0': showDeleteConfirm }" @click="isOpen = false">
            <X class="h-4 w-4" />
          </Button>
        </div>
      </SheetHeader>

      <!-- Delete Confirmation Dialog -->
      <Dialog v-model:open="showDeleteConfirm">
        <DialogContent class="bg-[#1c1c1e] border-border text-foreground max-w-sm">
          <DialogHeader>
            <DialogTitle class="text-base">Delete issue?</DialogTitle>
            <DialogDescription class="text-sm text-muted-foreground">
              This action cannot be undone. The issue will be permanently removed.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter class="flex gap-2 justify-end">
            <Button variant="outline" size="sm" class="h-7" @click="showDeleteConfirm = false">
              Cancel
            </Button>
            <Button size="sm" class="h-7 bg-red-600 hover:bg-red-700 text-white" :disabled="isDeleting" @click="deleteIssue">
              Delete
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <div class="flex-1 overflow-y-auto" v-if="issue">
        <div class="px-6 py-6 space-y-6">
          
          <!-- Title & Description -->
          <div class="space-y-4">
            <input
              v-model="issue.title"
              @input="queueSave"
              type="text"
              placeholder="Issue title"
              class="w-full bg-transparent border-none text-2xl font-bold focus:ring-0 focus:outline-none p-0 placeholder:text-muted-foreground/60"
            />
            <textarea
              v-model="issue.description"
              @input="queueSave"
              placeholder="Add description..."
              class="w-full bg-transparent border-none text-sm resize-none min-h-[120px] focus:ring-0 focus:outline-none p-0 placeholder:text-muted-foreground/60"
            ></textarea>
          </div>

          <!-- Properties -->
          <div class="flex flex-wrap items-center gap-2 pt-2 border-t border-border/20">
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
                  :class="[opt.color, issue.status === opt.value ? 'bg-secondary/40' : '']"
                  class="text-xs cursor-pointer"
                  @click="updateField('status', opt.value)"
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
                  :class="issue.priority === opt.value ? 'bg-secondary/40' : ''"
                  class="text-xs cursor-pointer"
                  @click="updateField('priority', opt.value)"
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
                  :class="issue.type === opt.value ? 'bg-secondary/40' : ''"
                  class="text-xs cursor-pointer"
                  @click="updateField('type', opt.value)"
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
                  :class="issue.projectId === null ? 'bg-secondary/40' : ''"
                  class="text-xs cursor-pointer text-muted-foreground"
                  @click="updateField('projectId', null)"
                >
                  No project
                </DropdownMenuItem>
                <DropdownMenuItem
                  v-for="project in projects || []"
                  :key="project.id"
                  :class="issue.projectId === project.id ? 'bg-secondary/40' : ''"
                  class="text-xs cursor-pointer"
                  @click="updateField('projectId', project.id)"
                >
                  {{ project.name }}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>

          <!-- Comments / Activity Section -->
          <div class="pt-8 border-t border-border/20">
            <h3 class="text-sm border-b border-border/20 pb-2 flex items-center gap-2 mb-4 font-medium text-foreground">
              <MessageSquare class="w-4 h-4 text-muted-foreground" /> Activity
            </h3>
            
            <div class="space-y-4">
              <!-- Comment Input -->
              <div class="bg-secondary/20 border border-border/40 rounded-xl p-3 focus-within:ring-1 focus-within:ring-primary focus-within:border-primary transition-all">
                <textarea
                  v-model="newComment"
                  placeholder="Leave a comment..."
                  class="w-full bg-transparent border-none text-sm resize-none focus:ring-0 focus:outline-none p-0 placeholder:text-muted-foreground/60 min-h-[60px]"
                  @keydown.enter.prevent="addComment"
                ></textarea>
                <div class="flex items-center justify-between mt-2 pt-2 border-t border-border/20">
                  <Button variant="ghost" size="icon" class="h-7 w-7 text-muted-foreground hover:text-foreground">
                    <Paperclip class="w-4 h-4" />
                  </Button>
                  <Button 
                    size="sm" 
                    class="h-7 px-3 text-xs bg-[#5e6ad2] hover:bg-[#4b54a8] text-white"
                    :disabled="!newComment.trim()"
                    @click="addComment"
                  >
                    Comment
                  </Button>
                </div>
              </div>

              <!-- Comment List -->
              <div class="space-y-4 mt-6">
                <div v-for="comment in comments" :key="comment.id" class="flex gap-3">
                  <div class="w-8 h-8 rounded-full bg-secondary flex items-center justify-center text-xs font-semibold flex-shrink-0">
                    You
                  </div>
                  <div class="flex-1 space-y-1">
                    <div class="flex items-center gap-2">
                      <span class="text-sm font-medium">You</span>
                      <span class="text-xs text-muted-foreground">{{ formatDate(comment.createdAt) }}</span>
                    </div>
                    <div class="text-sm text-foreground/90 whitespace-pre-wrap">
                      {{ comment.content }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

        </div>
      </div>
      <!-- Loading State -->
      <div v-else class="flex-1 flex flex-col items-center justify-center text-muted-foreground">
        <div class="w-8 h-8 rounded-full border-2 border-muted-foreground border-t-transparent animate-spin mb-4"></div>
        <p class="text-sm">Loading issue details...</p>
      </div>

    </SheetContent>
  </Sheet>
</template>
