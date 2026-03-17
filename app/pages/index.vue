<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { Trash2, Send, Cloud, CloudOff, Search, Filter, Calendar } from 'lucide-vue-next'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import InboxItemCard from '@/components/InboxItemCard.vue'
import InboxDetailPanel from '@/components/InboxDetailPanel.vue'
import InboxConvertDialog from '@/components/InboxConvertDialog.vue'

interface InboxItem {
  id: string
  content: string
  status: 'TODO' | 'DONE'
  createdAt: string
  updatedAt: string
}

const localFirst = useLocalFirst<InboxItem>('inbox', () => $fetch<InboxItem[]>('/api/inbox'))
const optimistic = useOptimistic(localFirst)

// Filter states
const filterStatus = ref<'all' | 'TODO' | 'DONE'>('all')
const filterTime = ref<'all' | 'today' | 'week' | 'month'>('all')
const searchQuery = ref('')

// Selection
const selectedItemId = ref<string | null>(null)
const selectedItem = computed(() => {
  return localFirst.items.value.find(item => item.id === selectedItemId.value) || null
})

// Convert dialog
const convertDialogOpen = ref(false)
const convertMode = ref<'issue' | 'project'>('issue')

// New item input
const newItem = ref('')

// Debounced search
let searchTimeout: ReturnType<typeof setTimeout> | null = null
const debouncedSearch = ref('')

watch(searchQuery, (val) => {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    debouncedSearch.value = val
  }, 300)
})

// Computed filtered items
const filteredItems = computed(() => {
  let items = localFirst.items.value

  // Status filter (frontend)
  if (filterStatus.value !== 'all') {
    items = items.filter(item => item.status === filterStatus.value)
  }

  return items
})

// Fetch with server-side filters
async function fetchItems() {
  const params = new URLSearchParams()
  if (filterStatus.value !== 'all') params.set('status', filterStatus.value)
  if (filterTime.value !== 'all') params.set('timeRange', filterTime.value)
  if (debouncedSearch.value) params.set('search', debouncedSearch.value)

  const query = params.toString()
  return await $fetch<InboxItem[]>(`/api/inbox${query ? `?${query}` : ''}`)
}

// Watch server-side filters and refetch
watch([filterTime, debouncedSearch], () => {
  localFirst.sync()
}, { immediate: false })

// Custom fetch function for server-side filtering
async function refreshWithFilters() {
  localFirst.isSyncing.value = true
  try {
    const items = await fetchItems()
    localFirst.items.value = items
    localFirst.lastSyncAt.value = new Date()
  } catch (err) {
    console.error('Failed to fetch items:', err)
  } finally {
    localFirst.isSyncing.value = false
  }
}

function generateTempId(): string {
  return `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

async function addItem() {
  if (!newItem.value.trim()) return

  const tempItem: InboxItem = {
    id: generateTempId(),
    content: newItem.value,
    status: 'TODO',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }

  newItem.value = ''

  await optimistic.optimisticAdd(
    async (item) => {
      return await $fetch<InboxItem>('/api/inbox', {
        method: 'POST',
        body: { content: item.content }
      })
    },
    tempItem
  )
}

async function deleteItem(id: string) {
  if (selectedItemId.value === id) {
    selectedItemId.value = null
  }
  await optimistic.optimisticDelete(
    async (itemId) => {
      await $fetch(`/api/inbox/${itemId}`, { method: 'DELETE' })
    },
    id
  )
}

async function updateItem(id: string, data: { content?: string; status?: string }) {
  const item = localFirst.items.value.find(i => i.id === id)
  if (!item) return

  // Optimistic update
  const oldItem = { ...item }
  Object.assign(item, data)

  try {
    await $fetch(`/api/inbox/${id}`, {
      method: 'PATCH',
      body: data
    })
  } catch (err) {
    // Rollback
    Object.assign(item, oldItem)
    console.error('Failed to update item:', err)
  }
}

async function toggleItemStatus(id: string) {
  const item = localFirst.items.value.find(i => i.id === id)
  if (!item) return
  const newStatus = item.status === 'TODO' ? 'DONE' : 'TODO'
  await updateItem(id, { status: newStatus })
}

function selectItem(id: string) {
  selectedItemId.value = id
}

function openConvertDialog(mode: 'issue' | 'project') {
  convertMode.value = mode
  convertDialogOpen.value = true
}

async function handleConvertToIssue(data: { title: string; description: string; priority: string; type: string; projectId: string | null; deleteOriginal: boolean }) {
  if (!selectedItemId.value) return

  try {
    const result = await $fetch(`/api/inbox/${selectedItemId.value}/convert`, {
      method: 'POST',
      body: data
    })

    if (data.deleteOriginal) {
      await refreshWithFilters()
      selectedItemId.value = null
    }

    convertDialogOpen.value = false
  } catch (err) {
    console.error('Failed to convert to issue:', err)
  }
}

async function handleConvertToProject(data: { name: string; description: string; deleteOriginal: boolean }) {
  if (!selectedItemId.value) return

  try {
    const result = await $fetch(`/api/inbox/${selectedItemId.value}/convert-project`, {
      method: 'POST',
      body: data
    })

    if (data.deleteOriginal) {
      await refreshWithFilters()
      selectedItemId.value = null
    }

    convertDialogOpen.value = false
  } catch (err) {
    console.error('Failed to convert to project:', err)
  }
}

const statusFilterOptions = [
  { value: 'all', label: 'All' },
  { value: 'TODO', label: 'Todo' },
  { value: 'DONE', label: 'Done' },
]

const timeFilterOptions = [
  { value: 'all', label: 'All time' },
  { value: 'today', label: 'Today' },
  { value: 'week', label: 'This week' },
  { value: 'month', label: 'This month' },
]

const currentStatusLabel = computed(() => statusFilterOptions.find(o => o.value === filterStatus.value)?.label || 'All')
const currentTimeLabel = computed(() => timeFilterOptions.find(o => o.value === filterTime.value)?.label || 'All time')

onMounted(() => localFirst.init())
</script>

<template>
  <div class="h-screen flex flex-col">
    <!-- Header -->
    <header class="px-6 py-4 border-b border-border/40 flex items-center justify-between flex-shrink-0">
      <div>
        <h1 class="text-2xl font-bold tracking-tight">Inbox</h1>
        <p class="text-sm text-muted-foreground">Capture thoughts and inspirations instantly.</p>
      </div>
      <div class="flex items-center gap-2 text-xs text-muted-foreground">
        <Cloud v-if="!localFirst.isSyncing.value" class="w-4 h-4" />
        <CloudOff v-else class="w-4 h-4 animate-pulse" />
        <span>{{ localFirst.isSyncing.value ? 'Syncing...' : 'Synced' }}</span>
      </div>
    </header>

    <!-- Main Content -->
    <div class="flex-1 flex overflow-hidden">
      <!-- Left Panel: List -->
      <div class="w-[400px] border-r border-border/40 flex flex-col">
        <!-- Filters -->
        <div class="p-3 border-b border-border/40 space-y-2">
          <!-- Search -->
          <div class="relative">
            <Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
            <input
              v-model="searchQuery"
              type="text"
              placeholder="Search..."
              class="w-full bg-secondary/20 border border-border/40 rounded-lg pl-9 pr-3 py-2 text-sm focus:ring-1 focus:ring-primary focus:border-primary transition-all placeholder:text-muted-foreground/60"
            />
          </div>

          <!-- Filter Buttons -->
          <div class="flex gap-2">
            <!-- Status Filter -->
            <DropdownMenu>
              <DropdownMenuTrigger as-child>
                <Button variant="outline" size="sm" class="h-7 px-2.5 text-xs bg-secondary/20 border-border/40 hover:bg-secondary/40">
                  <Filter class="w-3 h-3 mr-1.5" />
                  {{ currentStatusLabel }}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" class="w-32 bg-[#1c1c1e] border-border">
                <DropdownMenuItem
                  v-for="opt in statusFilterOptions"
                  :key="opt.value"
                  :class="filterStatus === opt.value ? 'bg-secondary/40' : ''"
                  class="text-xs cursor-pointer"
                  @click="filterStatus = opt.value as any"
                >
                  {{ opt.label }}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>

            <!-- Time Filter -->
            <DropdownMenu>
              <DropdownMenuTrigger as-child>
                <Button variant="outline" size="sm" class="h-7 px-2.5 text-xs bg-secondary/20 border-border/40 hover:bg-secondary/40">
                  <Calendar class="w-3 h-3 mr-1.5" />
                  {{ currentTimeLabel }}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" class="w-32 bg-[#1c1c1e] border-border">
                <DropdownMenuItem
                  v-for="opt in timeFilterOptions"
                  :key="opt.value"
                  :class="filterTime === opt.value ? 'bg-secondary/40' : ''"
                  class="text-xs cursor-pointer"
                  @click="filterTime = opt.value as any"
                >
                  {{ opt.label }}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>

        <!-- List -->
        <ScrollArea class="flex-1">
          <TransitionGroup name="list">
            <InboxItemCard
              v-for="item in filteredItems"
              :key="item.id"
              :item="item"
              :is-selected="selectedItemId === item.id"
              :class="{ 'opacity-50': optimistic.isPending(item.id) }"
              @select="selectItem"
              @toggle-status="toggleItemStatus"
              @delete="deleteItem"
            />
          </TransitionGroup>

          <!-- Empty State -->
          <div v-if="filteredItems.length === 0" class="text-center py-20 text-muted-foreground">
            <Send class="w-12 h-12 mx-auto mb-4 opacity-20" />
            <p class="text-sm">No items found.</p>
            <p class="text-xs mt-1">Start typing below to capture a thought!</p>
          </div>
        </ScrollArea>

        <!-- New Item Input -->
        <div class="p-3 border-t border-border/40">
          <div class="relative group">
            <input
              v-model="newItem"
              @keyup.enter="addItem"
              type="text"
              placeholder="Type a thought and press Enter..."
              class="w-full bg-secondary/20 border border-border/40 rounded-lg px-4 py-3 text-sm focus:ring-1 focus:ring-primary focus:border-primary transition-all placeholder:text-muted-foreground/60"
            />
            <div class="absolute right-3 top-1/2 -translate-y-1/2 opacity-0 group-focus-within:opacity-100 transition-opacity">
              <kbd class="px-1.5 py-0.5 rounded bg-muted text-[10px] font-mono">⏎</kbd>
            </div>
          </div>
        </div>
      </div>

      <!-- Right Panel: Detail -->
      <div class="flex-1 bg-background">
        <InboxDetailPanel
          :item="selectedItem"
          @update="updateItem"
          @convert-to-issue="openConvertDialog('issue')"
          @convert-to-project="openConvertDialog('project')"
          @delete="deleteItem"
        />
      </div>
    </div>

    <!-- Convert Dialog -->
    <InboxConvertDialog
      v-model:open="convertDialogOpen"
      v-model:mode="convertMode"
      :item-id="selectedItemId"
      :item-content="selectedItem?.content || ''"
      @convert-to-issue="handleConvertToIssue"
      @convert-to-project="handleConvertToProject"
    />
  </div>
</template>

<style scoped>
.list-enter-active,
.list-leave-active {
  transition: all 0.3s ease;
}
.list-enter-from {
  opacity: 0;
  transform: translateY(-10px);
}
.list-leave-to {
  opacity: 0;
  transform: translateX(20px);
}
</style>