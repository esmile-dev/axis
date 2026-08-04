<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import {
  BookOpen, FileText, StickyNote, Search, Plus, Cloud, CloudOff,
  Tag, ExternalLink, ChevronLeft, ChevronDown, X, Sparkles,
} from 'lucide-vue-next'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import KnowledgeItemCard from '@/components/KnowledgeItemCard.vue'
import KnowledgeAddDialog from '@/components/KnowledgeAddDialog.vue'
import KnowledgeArtifactView from '@/components/KnowledgeArtifactView.vue'
import KnowledgeMindmap from '@/components/KnowledgeMindmap.vue'
import KnowledgeQaPanel from '@/components/KnowledgeQaPanel.vue'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import type { ArtifactStatus, KnowledgeItemDetail, KnowledgeItemSummary, KnowledgeStatus, KnowledgeType } from '@/types'

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

// Detail tabs：脑图依赖 DOM 尺寸，首次切到该 tab 才挂载，之后 v-show 保活不销毁重建
const activeTab = ref<'content' | 'summary' | 'mindmap'>('content')
const mindmapMounted = ref(false)
// AI 问答面板（T-011）：右栏底部可开合；面板组件 :key=detail.id，切条目重挂载即清空重载
const qaOpen = ref(false)
// 原文滚动容器：T-010 滚动进度记录/恢复基于此 ref
const contentScrollRef = ref<HTMLElement | null>(null)

watch(activeTab, (tab) => {
  if (tab === 'mindmap') mindmapMounted.value = true
  // 停留在其他 tab 时打开的详情无法恢复滚动（容器隐藏），切回原文 tab 补一次
  if (tab === 'content' && !progressRestored) restoreScrollPosition()
})

const summaryArtifact = computed(() => detail.value?.artifacts.find(a => a.kind === 'SUMMARY') ?? null)
const mindmapArtifact = computed(() => detail.value?.artifacts.find(a => a.kind === 'MINDMAP') ?? null)

// 产物轮询：PENDING/GENERATING 时每 3s 重拉详情，~90s 超时停止并提示手动刷新
const POLL_INTERVAL_MS = 3000
const POLL_TIMEOUT_MS = 90_000
const pollTimedOut = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null
let pollStartedAt = 0

const isArtifactGenerating = (s: ArtifactStatus) => s === 'PENDING' || s === 'GENERATING'

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPollingIfNeeded() {
  stopPolling()
  const d = detail.value
  if (!d || (!isArtifactGenerating(d.summaryStatus) && !isArtifactGenerating(d.mindmapStatus))) return
  pollStartedAt = Date.now()
  pollTimer = setInterval(pollDetail, POLL_INTERVAL_MS)
}

async function pollDetail() {
  const id = selectedId.value
  if (!id) {
    stopPolling()
    return
  }
  if (Date.now() - pollStartedAt > POLL_TIMEOUT_MS) {
    stopPolling()
    pollTimedOut.value = true
    return
  }
  try {
    const fresh = await api<KnowledgeItemDetail>(`/api/knowledge/${id}`)
    if (selectedId.value !== id) return // 轮询期间已切换条目
    detail.value = fresh
    if (!isArtifactGenerating(fresh.summaryStatus) && !isArtifactGenerating(fresh.mindmapStatus)) stopPolling()
  } catch (err) {
    console.error('Failed to poll knowledge item:', err) // 瞬时失败不中断，下个周期重试
  }
}

// 快速连点只认最后一次选择（递增令牌使在途响应作废）
let selectToken = 0

async function selectItem(id: string) {
  if (selectedId.value === id) return
  const token = ++selectToken
  selectedId.value = id
  detail.value = null
  detailLoading.value = true
  stopPolling()
  cancelPendingProgressSave() // 切条目：取消防抖中未发的进度请求，避免把 A 的进度打到 B 上
  progressRestored = false // 新条目重新允许一次滚动位置恢复
  pollTimedOut.value = false
  tagInput.value = ''
  // 恢复「脑图首次可见才挂载」的不变量（mindmapMounted 跨条目保留会导致在隐藏容器里 0×0 挂载、
  // fit 出 scale 0 的空白脑图）；停留在脑图 tab 时新详情到达即可见，允许直接挂载
  mindmapMounted.value = activeTab.value === 'mindmap'
  try {
    const fresh = await api<KnowledgeItemDetail>(`/api/knowledge/${id}`)
    if (token !== selectToken) return
    detail.value = fresh
    await restoreScrollPosition()
    startPollingIfNeeded()
  } catch (err) {
    if (token !== selectToken) return
    console.error('Failed to load knowledge item:', err)
  } finally {
    if (token === selectToken) detailLoading.value = false
  }
}

watch(selectedId, (id) => {
  if (!id) {
    selectToken++ // 返回列表：作废在途请求并停轮询
    stopPolling()
    cancelPendingProgressSave()
    detail.value = null
  }
})

onBeforeUnmount(() => {
  stopPolling()
  cancelPendingProgressSave()
})

async function regenerateArtifact(kind: 'SUMMARY' | 'MINDMAP') {
  const d = detail.value
  if (!d) return
  try {
    // 202 响应体即最新详情（目标状态已置 GENERATING），随后进入轮询
    const fresh = await api<KnowledgeItemDetail>(`/api/knowledge/${d.id}/artifacts/${kind}/regenerate`, { method: 'POST' })
    if (selectedId.value !== d.id) return
    detail.value = fresh
    pollTimedOut.value = false
    startPollingIfNeeded()
  } catch (err) {
    console.error(`Failed to regenerate ${kind}:`, err)
  }
}

// 状态切换（T-010）：乐观更新详情与列表徽章，失败回滚
const detailStatusOptions: KnowledgeStatus[] = ['UNREAD', 'READING', 'DONE', 'ARCHIVED']

async function updateStatus(status: KnowledgeStatus) {
  const d = detail.value
  if (!d || d.status === status) return
  const previous = d.status
  d.status = status
  localFirst.updateItem(d.id, { status })
  try {
    await api(`/api/knowledge/${d.id}`, { method: 'PATCH', body: { status } })
  } catch (err) {
    if (detail.value === d) d.status = previous
    localFirst.updateItem(d.id, { status: previous })
    console.error('Failed to update knowledge status:', err)
  }
}

// 阅读进度（T-010）：原文滚动 2s 防抖静默 PATCH；打开详情按 progress 恢复滚动位置
const PROGRESS_SAVE_DELAY_MS = 2000
let progressTimer: ReturnType<typeof setTimeout> | null = null
// 每条详情只恢复一次滚动位置：容器隐藏（停留在其他 tab）时恢复无效，切回原文 tab 补一次
let progressRestored = false

function cancelPendingProgressSave() {
  if (progressTimer) {
    clearTimeout(progressTimer)
    progressTimer = null
  }
}

function onContentScroll() {
  const el = contentScrollRef.value
  const id = selectedId.value
  if (!el || !id) return
  const max = el.scrollHeight - el.clientHeight
  if (max <= 0) return
  const progress = Math.min(100, Math.max(0, Math.round((el.scrollTop / max) * 100)))
  cancelPendingProgressSave()
  progressTimer = setTimeout(() => saveProgress(id, progress), PROGRESS_SAVE_DELAY_MS)
}

// 静默更新：不触发列表重排/重拉，仅本地同步列表项进度
async function saveProgress(id: string, progress: number) {
  if (detail.value?.id === id) detail.value.progress = progress
  localFirst.updateItem(id, { progress })
  try {
    await api(`/api/knowledge/${id}`, { method: 'PATCH', body: { progress } })
  } catch (err) {
    console.error('Failed to save reading progress:', err)
  }
}

async function restoreScrollPosition() {
  await nextTick()
  const el = contentScrollRef.value
  const d = detail.value
  if (!el || !d || d.progress <= 0) return
  if (el.clientHeight === 0) return // 容器隐藏（停留在其他 tab）：赋值会被钳为 0，等切回原文 tab 补
  progressRestored = true
  el.scrollTop = (d.progress / 100) * (el.scrollHeight - el.clientHeight)
}

// 标签编辑（T-010）：回车/逗号新增、× 删除，整体替换 PATCH，乐观更新失败回滚
const tagInput = ref('')

async function updateTags(tags: string[]) {
  const d = detail.value
  if (!d) return
  const previous = d.tags
  d.tags = tags
  localFirst.updateItem(d.id, { tags })
  try {
    await api(`/api/knowledge/${d.id}`, { method: 'PATCH', body: { tags } })
  } catch (err) {
    if (detail.value === d) d.tags = previous
    localFirst.updateItem(d.id, { tags: previous })
    console.error('Failed to update tags:', err)
  }
}

function onTagKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ',') {
    e.preventDefault()
    addTag()
  }
}

function addTag() {
  const d = detail.value
  const tag = tagInput.value.replace(/[,，]/g, '').trim()
  if (!d || !tag) return
  tagInput.value = ''
  if (tag.length > 50 || d.tags.includes(tag)) return // 去重、trim、长度 ≤50
  updateTags([...d.tags, tag])
}

function removeTag(tag: string) {
  const d = detail.value
  if (!d) return
  updateTags(d.tags.filter(t => t !== tag))
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

      <!-- Right: detail with 原文/总结/脑图 tabs -->
      <div
        class="flex-1 bg-background flex-col overflow-hidden"
        :class="selectedId ? 'flex' : 'hidden md:flex'"
      >
        <!-- No selection -->
        <div v-if="!selectedId" class="flex-1 flex flex-col items-center justify-center text-muted-foreground">
          <BookOpen class="w-12 h-12 mb-4 opacity-20" />
          <p class="text-sm">Select an item to view details</p>
        </div>

        <!-- Detail skeleton -->
        <div v-else-if="detailLoading" class="flex-1 overflow-y-auto px-8 py-6">
          <div class="max-w-2xl space-y-3">
            <div class="h-7 w-2/3 rounded bg-secondary/40 animate-pulse" />
            <div class="h-4 w-1/3 rounded bg-secondary/30 animate-pulse" />
            <div class="h-4 w-1/2 rounded bg-secondary/30 animate-pulse" />
          </div>
        </div>

        <div v-else-if="detail" class="flex-1 flex flex-col min-h-0">
          <!-- Header -->
          <div class="flex-shrink-0 px-8 pt-6">
            <div class="max-w-2xl">
              <!-- Mobile back -->
              <button
                class="md:hidden flex items-center gap-1 mb-4 text-xs text-muted-foreground hover:text-foreground"
                @click="selectedId = null"
              >
                <ChevronLeft class="w-3.5 h-3.5" />
                返回列表
              </button>

              <div class="flex items-start justify-between gap-4">
                <h2 class="text-xl font-bold tracking-tight leading-snug">{{ detail.title }}</h2>
                <Button
                  variant="outline"
                  size="sm"
                  class="h-7 px-2.5 text-xs shrink-0 bg-secondary/20 border-border/40 hover:bg-secondary/40"
                  :class="{ 'bg-secondary/50 text-foreground': qaOpen }"
                  @click="qaOpen = !qaOpen"
                >
                  <Sparkles class="w-3 h-3 mr-1" />
                  AI 问答
                </Button>
              </div>

              <!-- Meta -->
              <div class="mt-4 space-y-2 text-sm">
                <div class="flex gap-3">
                  <span class="w-20 flex-shrink-0 text-muted-foreground">类型</span>
                  <span>{{ typeLabels[detail.type] ?? detail.type }}</span>
                </div>
                <div class="flex gap-3 items-center">
                  <span class="w-20 flex-shrink-0 text-muted-foreground">状态</span>
                  <DropdownMenu>
                    <DropdownMenuTrigger as-child>
                      <Button variant="outline" size="sm" class="h-7 px-2.5 text-xs bg-secondary/20 border-border/40 hover:bg-secondary/40">
                        {{ statusLabels[detail.status] ?? detail.status }}
                        <ChevronDown class="w-3 h-3 ml-1 text-muted-foreground" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="start" class="w-32 bg-[#1c1c1e] border-border">
                      <DropdownMenuItem
                        v-for="s in detailStatusOptions"
                        :key="s"
                        :class="detail.status === s ? 'bg-secondary/40' : ''"
                        class="text-xs cursor-pointer"
                        @click="updateStatus(s)"
                      >
                        {{ statusLabels[s] }}
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
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
                <div class="flex gap-3">
                  <span class="w-20 flex-shrink-0 text-muted-foreground">标签</span>
                  <div class="flex flex-wrap items-center gap-1.5">
                    <span
                      v-for="tag in detail.tags"
                      :key="tag"
                      class="inline-flex items-center gap-1 px-1.5 py-0.5 rounded border border-border/60 bg-secondary/20 text-xs text-muted-foreground"
                    >
                      {{ tag }}
                      <button class="hover:text-foreground transition-colors" @click="removeTag(tag)">
                        <X class="w-3 h-3" />
                      </button>
                    </span>
                    <input
                      v-model="tagInput"
                      type="text"
                      placeholder="+ 标签"
                      class="w-20 bg-transparent border-none px-1 py-0.5 text-xs focus:ring-0 focus:outline-none placeholder:text-muted-foreground/60"
                      @keydown="onTagKeydown"
                    />
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
            </div>
          </div>

          <!-- Tabs：v-show 保活各视图组件状态 -->
          <Tabs v-model="activeTab" class="flex-1 flex flex-col min-h-0 px-8 pb-6 mt-6">
            <TabsList class="self-start flex-shrink-0">
              <TabsTrigger value="content">原文</TabsTrigger>
              <TabsTrigger value="summary">总结</TabsTrigger>
              <TabsTrigger value="mindmap">脑图</TabsTrigger>
            </TabsList>

            <!-- 原文：滚动容器带 ref，滚动 2s 防抖记录阅读进度（T-010） -->
            <div
              v-show="activeTab === 'content'"
              ref="contentScrollRef"
              class="flex-1 min-h-0 mt-4 overflow-y-auto pr-2"
              @scroll="onContentScroll"
            >
              <MarkdownRenderer :content="detail.content" class="max-w-3xl" />
            </div>

            <!-- 总结 -->
            <div v-show="activeTab === 'summary'" class="flex-1 min-h-0 mt-4 overflow-y-auto pr-2">
              <KnowledgeArtifactView
                kind="SUMMARY"
                :status="detail.summaryStatus"
                :artifact="summaryArtifact"
                :timed-out="pollTimedOut"
                @regenerate="regenerateArtifact('SUMMARY')"
              >
                <template #default="{ artifact }">
                  <MarkdownRenderer :content="artifact.content" class="max-w-3xl" />
                </template>
              </KnowledgeArtifactView>
            </div>

            <!-- 脑图 -->
            <div v-show="activeTab === 'mindmap'" class="flex-1 min-h-0 mt-4">
              <KnowledgeArtifactView
                kind="MINDMAP"
                :status="detail.mindmapStatus"
                :artifact="mindmapArtifact"
                :timed-out="pollTimedOut"
                @regenerate="regenerateArtifact('MINDMAP')"
              >
                <template #default="{ artifact }">
                  <KnowledgeMindmap v-if="mindmapMounted" :content="artifact.content" :active="activeTab === 'mindmap'" />
                </template>
              </KnowledgeArtifactView>
            </div>
          </Tabs>

          <!-- AI 问答面板（T-011）：右栏底部开合，:key 按条目隔离会话状态 -->
          <KnowledgeQaPanel
            v-if="qaOpen"
            :key="detail.id"
            :item-id="detail.id"
            class="h-[45%] min-h-[260px] shrink-0 border-t border-border/40"
            @close="qaOpen = false"
          />
        </div>

        <!-- Load failed -->
        <div v-else class="flex-1 flex items-center justify-center text-muted-foreground">
          <p class="text-sm">详情加载失败，请稍后重试</p>
        </div>
      </div>
    </div>

    <KnowledgeAddDialog v-model:open="addDialogOpen" @added="handleAdded" />
  </div>
</template>
