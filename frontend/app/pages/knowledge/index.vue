<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import {
  BookOpen, FileText, StickyNote, Search, Plus, Cloud, CloudOff,
  Tag, ExternalLink, ChevronLeft, Construction,
} from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import KnowledgeItemCard from '@/components/KnowledgeItemCard.vue'
import KnowledgeAddDialog from '@/components/KnowledgeAddDialog.vue'
import type { KnowledgeItemDetail, KnowledgeItemSummary, KnowledgeStatus, KnowledgeType } from '@/types'

const api = useApi()
const route = useRoute()
const router = useRouter()

// Filters
const filterStatus = ref<'all' | KnowledgeStatus>('all')
const filterType = ref<'all' | KnowledgeType>('all')
const filterTag = ref<string | null>(null)
const searchQuery = ref('')
const debouncedSearch = ref('')

function buildQuery(): string {
  const params = new URLSearchParams()
  if (filterType.value !== 'all') params.set('type', filterType.value)
  if (filterStatus.value !== 'all') params.set('status', filterStatus.value)
  if (filterTag.value) params.set('tag', filterTag.value)
  if (debouncedSearch.value) params.set('q', debouncedSearch.value)
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

// Local-first list: 首屏读缓存，后台按当前筛选参数同步（筛选变化重新拉取）
const localFirst = useLocalFirst<KnowledgeItemSummary>(
  'knowledge',
  () => api<KnowledgeItemSummary[]>(`/api/knowledge${buildQuery()}`)
)

// Debounced search (300ms)
let searchTimeout: ReturnType<typeof setTimeout> | null = null
watch(searchQuery, (val) => {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    debouncedSearch.value = val
  }, 300)
})

watch([filterStatus, filterType, filterTag, debouncedSearch], () => {
  localFirst.sync()
})

// Selection & detail
const selectedId = ref<string | null>(null)
const detail = ref<KnowledgeItemDetail | null>(null)
const detailLoading = ref(false)

async function selectItem(id: string) {
  if (selectedId.value === id) return
  selectedId.value = id
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await api<KnowledgeItemDetail>(`/api/knowledge/${id}`)
  } catch (err) {
    console.error('Failed to load knowledge item:', err)
  } finally {
    detailLoading.value = false
  }
}

// Add dialog
const addDialogOpen = ref(false)

watch(() => route.query.add, (val) => {
  if (val) addDialogOpen.value = true
}, { immediate: true })

watch(addDialogOpen, (open) => {
  if (!open && route.query.add) {
    const { add: _, ...rest } = route.query
    router.replace({ query: rest })
  }
})

async function handleAdded(item: KnowledgeItemDetail) {
  addDialogOpen.value = false
  await localFirst.sync()
  selectItem(item.id)
}

// Left nav data
const statusNav: { value: 'all' | KnowledgeStatus; label: string }[] = [
  { value: 'all', label: '全部' },
  { value: 'UNREAD', label: '未读' },
  { value: 'READING', label: '在读' },
  { value: 'DONE', label: '已读' },
  { value: 'ARCHIVED', label: '归档' },
]

const typeNav: { value: KnowledgeType; label: string; icon: unknown }[] = [
  { value: 'ARTICLE', label: '文章', icon: FileText },
  { value: 'BOOK', label: '书籍', icon: BookOpen },
  { value: 'NOTE', label: '笔记', icon: StickyNote },
]

const typeLabels: Record<string, string> = {
  ARTICLE: '文章', BOOK: '书籍', NOTE: '笔记',
  PODCAST: '播客', VIDEO: '视频', TUTORIAL: '教程',
}

const statusLabels: Record<string, string> = {
  UNREAD: '未读', READING: '在读', DONE: '已读', ARCHIVED: '归档',
}

const allTags = computed(() => {
  const set = new Set<string>()
  for (const item of localFirst.items.value) {
    item.tags?.forEach(t => set.add(t))
  }
  return [...set].sort()
})

function toggleType(type: KnowledgeType) {
  filterType.value = filterType.value === type ? 'all' : type
}

function toggleTag(tag: string) {
  filterTag.value = filterTag.value === tag ? null : tag
}

const showSkeleton = computed(() => localFirst.isSyncing.value && localFirst.items.value.length === 0)

function formatDateTime(dateStr: string) {
  return new Date(dateStr).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
  })
}

onMounted(() => {
  localFirst.init()
})
</script>

<template>
  <div class="h-screen flex flex-col">
    <!-- Header -->
    <header class="px-6 py-4 border-b border-border/40 flex items-center justify-between flex-shrink-0">
      <div>
        <h1 class="text-2xl font-bold tracking-tight">Knowledge</h1>
        <p class="text-sm text-muted-foreground">Your personal knowledge base.</p>
      </div>
      <div class="flex items-center gap-3">
        <Button
          size="sm"
          class="h-8 px-3 text-xs bg-[#5e6ad2] hover:bg-[#4b54a8] text-white"
          @click="addDialogOpen = true"
        >
          <Plus class="w-3.5 h-3.5 mr-1.5" />
          添加
        </Button>
        <div class="flex items-center gap-2 text-xs text-muted-foreground">
          <CloudOff v-if="localFirst.isSyncing.value" class="w-4 h-4 animate-pulse" />
          <CloudOff v-else-if="localFirst.syncError.value" class="w-4 h-4 text-destructive" />
          <Cloud v-else class="w-4 h-4" />
          <span :class="{ 'text-destructive': localFirst.syncError.value && !localFirst.isSyncing.value }">{{ localFirst.isSyncing.value ? 'Syncing...' : (localFirst.syncError.value ? 'Sync failed' : 'Synced') }}</span>
        </div>
      </div>
    </header>

    <!-- Main Content: three columns -->
    <div class="flex-1 flex overflow-hidden">
      <!-- Left: filter nav -->
      <aside class="hidden md:flex w-[220px] flex-shrink-0 border-r border-border/40 flex-col">
        <ScrollArea class="flex-1">
          <div class="p-3 space-y-5">
            <!-- Status -->
            <div>
              <p class="px-2 mb-1 text-xs font-medium text-muted-foreground">状态</p>
              <button
                v-for="s in statusNav"
                :key="s.value"
                class="w-full flex items-center px-2 py-1.5 rounded-md text-sm transition-colors"
                :class="filterStatus === s.value ? 'bg-accent text-accent-foreground' : 'text-muted-foreground hover:bg-accent/50 hover:text-foreground'"
                @click="filterStatus = s.value"
              >
                {{ s.label }}
              </button>
            </div>

            <!-- Type -->
            <div>
              <p class="px-2 mb-1 text-xs font-medium text-muted-foreground">类型</p>
              <button
                v-for="t in typeNav"
                :key="t.value"
                class="w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-sm transition-colors"
                :class="filterType === t.value ? 'bg-accent text-accent-foreground' : 'text-muted-foreground hover:bg-accent/50 hover:text-foreground'"
                @click="toggleType(t.value)"
              >
                <component :is="t.icon" class="w-3.5 h-3.5" />
                {{ t.label }}
              </button>
            </div>

            <!-- Tags -->
            <div v-if="allTags.length > 0">
              <p class="px-2 mb-1 text-xs font-medium text-muted-foreground">标签</p>
              <button
                v-for="tag in allTags"
                :key="tag"
                class="w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-sm transition-colors truncate"
                :class="filterTag === tag ? 'bg-accent text-accent-foreground' : 'text-muted-foreground hover:bg-accent/50 hover:text-foreground'"
                @click="toggleTag(tag)"
              >
                <Tag class="w-3 h-3 flex-shrink-0" />
                <span class="truncate">{{ tag }}</span>
              </button>
            </div>
          </div>
        </ScrollArea>
      </aside>

      <!-- Middle: item list -->
      <div
        class="w-full md:w-[360px] flex-shrink-0 border-r border-border/40 flex-col"
        :class="selectedId ? 'hidden md:flex' : 'flex'"
      >
        <!-- Search -->
        <div class="p-3 border-b border-border/40">
          <div class="relative">
            <Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
            <input
              v-model="searchQuery"
              type="text"
              placeholder="Search..."
              class="w-full bg-secondary/20 border border-border/40 rounded-lg pl-9 pr-3 py-2 text-sm focus:ring-1 focus:ring-primary focus:border-primary transition-all placeholder:text-muted-foreground/60"
            />
          </div>
        </div>

        <!-- List -->
        <ScrollArea class="flex-1">
          <!-- Skeleton -->
          <div v-if="showSkeleton" class="p-4 space-y-5">
            <div v-for="i in 5" :key="i" class="space-y-2">
              <div class="h-4 w-3/4 rounded bg-secondary/40 animate-pulse" />
              <div class="h-3 w-1/2 rounded bg-secondary/30 animate-pulse" />
            </div>
          </div>

          <template v-else>
            <KnowledgeItemCard
              v-for="item in localFirst.items.value"
              :key="item.id"
              :item="item"
              :is-selected="selectedId === item.id"
              @select="selectItem"
            />

            <!-- Empty State -->
            <div v-if="localFirst.items.value.length === 0" class="text-center py-20 text-muted-foreground">
              <BookOpen class="w-12 h-12 mx-auto mb-4 opacity-20" />
              <p class="text-sm">No items found.</p>
              <p class="text-xs mt-1">Click「+ 添加」or press ⌘K to add one.</p>
            </div>
          </template>
        </ScrollArea>
      </div>

      <!-- Right: basic detail -->
      <div
        class="flex-1 bg-background flex-col overflow-hidden"
        :class="selectedId ? 'flex' : 'hidden md:flex'"
      >
        <ScrollArea class="flex-1">
          <div class="max-w-2xl px-8 py-6">
            <!-- Mobile back -->
            <button
              class="md:hidden flex items-center gap-1 mb-4 text-xs text-muted-foreground hover:text-foreground"
              @click="selectedId = null"
            >
              <ChevronLeft class="w-3.5 h-3.5" />
              返回列表
            </button>

            <!-- No selection -->
            <div v-if="!selectedId" class="h-full flex flex-col items-center justify-center py-40 text-muted-foreground">
              <BookOpen class="w-12 h-12 mb-4 opacity-20" />
              <p class="text-sm">Select an item to view details</p>
            </div>

            <!-- Detail skeleton -->
            <div v-else-if="detailLoading" class="space-y-3">
              <div class="h-7 w-2/3 rounded bg-secondary/40 animate-pulse" />
              <div class="h-4 w-1/3 rounded bg-secondary/30 animate-pulse" />
              <div class="h-4 w-1/2 rounded bg-secondary/30 animate-pulse" />
            </div>

            <template v-else-if="detail">
              <h2 class="text-xl font-bold tracking-tight leading-snug">{{ detail.title }}</h2>

              <!-- Meta -->
              <div class="mt-4 space-y-2 text-sm">
                <div class="flex gap-3">
                  <span class="w-20 flex-shrink-0 text-muted-foreground">类型</span>
                  <span>{{ typeLabels[detail.type] ?? detail.type }}</span>
                </div>
                <div class="flex gap-3">
                  <span class="w-20 flex-shrink-0 text-muted-foreground">状态</span>
                  <span>{{ statusLabels[detail.status] ?? detail.status }}</span>
                </div>
                <div v-if="detail.progress > 0" class="flex gap-3 items-center">
                  <span class="w-20 flex-shrink-0 text-muted-foreground">进度</span>
                  <div class="flex-1 max-w-[200px] h-1.5 rounded-full bg-secondary/40 overflow-hidden">
                    <div class="h-full rounded-full bg-primary" :style="{ width: `${Math.min(detail.progress, 100)}%` }" />
                  </div>
                  <span class="text-xs text-muted-foreground">{{ detail.progress }}%</span>
                </div>
                <div v-if="detail.sourceUrl" class="flex gap-3">
                  <span class="w-20 flex-shrink-0 text-muted-foreground">来源</span>
                  <a
                    :href="detail.sourceUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="inline-flex items-center gap-1 text-primary hover:underline truncate"
                  >
                    <span class="truncate">{{ detail.sourceUrl }}</span>
                    <ExternalLink class="w-3 h-3 flex-shrink-0" />
                  </a>
                </div>
                <div v-if="detail.tags?.length" class="flex gap-3">
                  <span class="w-20 flex-shrink-0 text-muted-foreground">标签</span>
                  <div class="flex flex-wrap gap-1.5">
                    <span
                      v-for="tag in detail.tags"
                      :key="tag"
                      class="px-1.5 py-0.5 rounded border border-border/60 bg-secondary/20 text-xs text-muted-foreground"
                    >
                      {{ tag }}
                    </span>
                  </div>
                </div>
                <div class="flex gap-3">
                  <span class="w-20 flex-shrink-0 text-muted-foreground">创建时间</span>
                  <span class="text-muted-foreground">{{ formatDateTime(detail.createdAt) }}</span>
                </div>
                <div class="flex gap-3">
                  <span class="w-20 flex-shrink-0 text-muted-foreground">更新时间</span>
                  <span class="text-muted-foreground">{{ formatDateTime(detail.updatedAt) }}</span>
                </div>
              </div>

              <!-- T-009 placeholder -->
              <div class="mt-8 border border-dashed border-border/60 rounded-xl p-8 text-center text-muted-foreground">
                <Construction class="w-8 h-8 mx-auto mb-3 opacity-40" />
                <p class="text-sm">详情视图施工中（T-009）</p>
                <p class="text-xs mt-1">原文 / 总结 / 脑图 三视图将在下一任务上线</p>
              </div>
            </template>

            <!-- Load failed -->
            <div v-else class="py-20 text-center text-muted-foreground">
              <p class="text-sm">详情加载失败，请稍后重试</p>
            </div>
          </div>
        </ScrollArea>
      </div>
    </div>

    <KnowledgeAddDialog v-model:open="addDialogOpen" @added="handleAdded" />
  </div>
</template>
