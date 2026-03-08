<script setup lang="ts">
import { Plus, Search, FileText, ChevronRight, Cloud, CloudOff, Trash2 } from 'lucide-vue-next'

interface KnowledgeDoc {
  id: string
  title: string
  content: string
  createdAt: string
  updatedAt: string
}

const localFirst = useLocalFirst<KnowledgeDoc>('knowledge', () => $fetch<KnowledgeDoc[]>('/api/knowledge'))
const optimistic = useOptimistic(localFirst)

const search = ref('')

const showSlidePanel = ref(false)
const editingDoc = ref<KnowledgeDoc | null>(null)
const editForm = ref({ title: '', content: '' })

const filteredDocs = computed(() => {
  if (!search.value) return localFirst.items.value
  const query = search.value.toLowerCase()
  return localFirst.items.value.filter((doc: KnowledgeDoc) => 
    doc.title.toLowerCase().includes(query) || 
    doc.content.toLowerCase().includes(query)
  )
})

function generateTempId(): string {
  return `temp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
}

function openNewDocPanel() {
  editingDoc.value = null
  editForm.value = { title: '', content: '' }
  showSlidePanel.value = true
}

function openEditPanel(doc: KnowledgeDoc) {
  editingDoc.value = doc
  editForm.value = { title: doc.title, content: doc.content }
  showSlidePanel.value = true
}

async function saveDoc() {
  if (!editForm.value.title.trim()) return
  
  if (editingDoc.value) {
    await optimistic.optimisticUpdate(
      async (id, updates) => {
        return await $fetch<KnowledgeDoc>(`/api/knowledge/${id}`, {
          method: 'PATCH',
          body: updates
        })
      },
      editingDoc.value.id,
      { title: editForm.value.title, content: editForm.value.content }
    )
  } else {
    const tempItem: KnowledgeDoc = {
      id: generateTempId(),
      title: editForm.value.title,
      content: editForm.value.content || '# ' + editForm.value.title,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    }
    
    await optimistic.optimisticAdd(
      async (item) => {
        return await $fetch<KnowledgeDoc>('/api/knowledge', {
          method: 'POST',
          body: { title: item.title, content: item.content }
        })
      },
      tempItem
    )
  }
  
  showSlidePanel.value = false
}

async function deleteDoc(id: string) {
  await optimistic.optimisticDelete(
    async (itemId) => {
      await $fetch(`/api/knowledge/${itemId}`, { method: 'DELETE' })
    },
    id
  )
  showSlidePanel.value = false
}

onMounted(() => localFirst.init())
</script>

<template>
  <div class="p-8 max-w-5xl mx-auto h-full flex flex-col">
    <header class="mb-10 flex items-end justify-between">
      <div>
        <h1 class="text-3xl font-bold tracking-tight mb-2">Knowledge Base</h1>
        <p class="text-muted-foreground">Persist your findings and technical notes.</p>
      </div>
      <div class="flex items-center gap-4">
        <div class="flex items-center gap-2 text-xs text-muted-foreground">
          <Cloud v-if="!localFirst.isSyncing.value" class="w-4 h-4" />
          <CloudOff v-else class="w-4 h-4 animate-pulse" />
          <span>{{ localFirst.isSyncing.value ? 'Syncing...' : 'Synced' }}</span>
        </div>
        <button
          @click="openNewDocPanel"
          class="flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-lg font-medium hover:opacity-90 transition-all"
        >
          <Plus class="w-4 h-4" />
          New Document
        </button>
      </div>
    </header>

    <div class="relative mb-8">
      <Search class="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
      <input
        v-model="search"
        type="text"
        placeholder="Search documents..."
        class="w-full bg-secondary/50 border-none rounded-xl pl-11 pr-6 py-3 focus:ring-2 focus:ring-ring transition-all"
      />
    </div>

    <div class="grid grid-cols-1 md:grid-cols-2 gap-4 flex-1 overflow-y-auto">
      <div
        v-for="doc in filteredDocs"
        :key="doc.id"
        @click="openEditPanel(doc)"
        class="group p-5 bg-card border border-border rounded-2xl hover:border-primary/30 transition-all cursor-pointer flex items-center gap-4"
        :class="{ 'opacity-50': optimistic.isPending(doc.id) }"
      >
        <div class="w-10 h-10 rounded-xl bg-primary/10 flex items-center justify-center text-primary">
          <FileText class="w-5 h-5" />
        </div>
        <div class="flex-1 min-w-0">
          <h3 class="font-semibold text-sm truncate">{{ doc.title }}</h3>
          <p class="text-xs text-muted-foreground mt-1">
            Last updated {{ new Date(doc.updatedAt).toLocaleDateString() }}
          </p>
        </div>
        <ChevronRight class="w-4 h-4 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" />
      </div>

      <div v-if="filteredDocs.length === 0" class="md:col-span-2 text-center py-20 text-muted-foreground">
        <FileText class="w-12 h-12 mx-auto mb-4 opacity-20" />
        <p>Your knowledge base is empty. Create your first document!</p>
      </div>
    </div>

    <!-- Slide-over Panel -->
    <Teleport to="body">
      <Transition name="slide">
        <div v-if="showSlidePanel" class="fixed inset-0 z-50">
          <div class="absolute inset-0 bg-black/50" @click="showSlidePanel = false"></div>
          <div class="absolute right-0 top-0 h-full w-full max-w-2xl bg-card border-l border-border shadow-xl">
            <div class="flex flex-col h-full">
              <div class="p-6 border-b border-border flex items-center justify-between">
                <h2 class="text-lg font-semibold">{{ editingDoc ? 'Edit Document' : 'New Document' }}</h2>
                <button @click="showSlidePanel = false" class="p-2 hover:bg-muted rounded-lg">
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
              
              <div class="flex-1 p-6 space-y-6 overflow-y-auto">
                <div>
                  <label class="block text-sm font-medium mb-2">Title</label>
                  <input
                    v-model="editForm.title"
                    type="text"
                    placeholder="Document title..."
                    class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all"
                  />
                </div>
                
                <div>
                  <label class="block text-sm font-medium mb-2">Content (Markdown)</label>
                  <textarea
                    v-model="editForm.content"
                    rows="16"
                    placeholder="# Document title&#10;&#10;Write your content here..."
                    class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all resize-none font-mono text-sm"
                  ></textarea>
                </div>
              </div>
              
              <div class="p-6 border-t border-border flex items-center justify-between">
                <button
                  v-if="editingDoc"
                  @click="deleteDoc(editingDoc.id)"
                  class="px-4 py-2 text-sm text-destructive hover:bg-destructive/10 rounded-lg transition-all flex items-center gap-2"
                >
                  <Trash2 class="w-4 h-4" />
                  Delete
                </button>
                <div v-else></div>
                <button
                  @click="saveDoc"
                  :disabled="!editForm.title.trim()"
                  class="px-6 py-2 bg-primary text-primary-foreground rounded-lg font-medium hover:opacity-90 transition-all disabled:opacity-50"
                >
                  {{ editingDoc ? 'Save Changes' : 'Create Document' }}
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
