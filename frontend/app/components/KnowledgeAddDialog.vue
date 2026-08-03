<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { X, ChevronDown, Plus, Upload, ClipboardPaste, Link } from 'lucide-vue-next'
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Button } from '@/components/ui/button'
import type { KnowledgeItemDetail, KnowledgeType } from '@/types'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  added: [item: KnowledgeItemDetail]
}>()

const isOpen = computed({
  get: () => props.open,
  set: (val) => emit('update:open', val)
})

const api = useApi()

const activeTab = ref<'paste' | 'url' | 'upload'>('paste')
const submitting = ref(false)
const errorMessage = ref('')

// 粘贴
const pasteTitle = ref('')
const pasteContent = ref('')
const pasteType = ref<KnowledgeType>('ARTICLE')
const pasteSourceUrl = ref('')

// URL
const fetchUrl = ref('')

// 上传
const uploadFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const typeOptions: { value: KnowledgeType; label: string }[] = [
  { value: 'ARTICLE', label: '文章' },
  { value: 'BOOK', label: '书籍' },
  { value: 'NOTE', label: '笔记' },
]

const currentTypeLabel = computed(() => typeOptions.find(o => o.value === pasteType.value)?.label)

function extractError(err: any, fallback: string): string {
  const status = err?.response?.status
  if (status === 413) return '文件超过大小限制（最大 20MB）'
  return err?.data?.message || fallback
}

async function submitPaste() {
  if (!pasteTitle.value.trim() || !pasteContent.value.trim() || submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    const item = await api<KnowledgeItemDetail>('/api/knowledge', {
      method: 'POST',
      body: {
        type: pasteType.value,
        title: pasteTitle.value.trim(),
        content: pasteContent.value,
        sourceUrl: pasteSourceUrl.value.trim() || undefined,
      }
    })
    finish(item)
  } catch (err: any) {
    errorMessage.value = extractError(err, '创建失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

async function submitFetch() {
  if (!fetchUrl.value.trim() || submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    const item = await api<KnowledgeItemDetail>('/api/knowledge/fetch', {
      method: 'POST',
      body: { url: fetchUrl.value.trim() }
    })
    finish(item)
  } catch (err: any) {
    const msg = extractError(err, '抓取失败，请检查链接后重试')
    errorMessage.value = err?.response?.status === 422 ? `${msg}（可改用粘贴创建）` : msg
  } finally {
    submitting.value = false
  }
}

async function submitUpload() {
  if (!uploadFile.value || submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    const formData = new FormData()
    formData.append('file', uploadFile.value)
    const item = await api<KnowledgeItemDetail>('/api/knowledge/import', {
      method: 'POST',
      body: formData,
    })
    finish(item)
  } catch (err: any) {
    errorMessage.value = extractError(err, '导入失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

function finish(item: KnowledgeItemDetail) {
  reset()
  emit('added', item)
}

function triggerFileInput() {
  fileInput.value?.click()
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  uploadFile.value = input.files?.[0] ?? null
  errorMessage.value = ''
}

function reset() {
  pasteTitle.value = ''
  pasteContent.value = ''
  pasteType.value = 'ARTICLE'
  pasteSourceUrl.value = ''
  fetchUrl.value = ''
  uploadFile.value = null
  if (fileInput.value) fileInput.value.value = ''
  errorMessage.value = ''
  activeTab.value = 'paste'
}

watch(isOpen, (open) => {
  if (open) reset()
})
</script>

<template>
  <Dialog v-model:open="isOpen">
    <DialogContent class="sm:max-w-[520px] p-0 gap-0 bg-[#1c1c1e] border-border text-foreground overflow-hidden shadow-2xl">
      <DialogHeader class="px-5 py-4 flex flex-row items-center justify-between border-b border-border/40">
        <DialogTitle class="text-sm font-medium flex items-center gap-2">
          <Plus class="w-4 h-4 text-muted-foreground" />
          Add to Knowledge
        </DialogTitle>
        <DialogClose as-child>
          <Button variant="ghost" size="icon" class="h-6 w-6 text-muted-foreground hover:text-foreground">
            <X class="h-4 w-4" />
          </Button>
        </DialogClose>
      </DialogHeader>

      <div class="px-5 pt-4">
        <Tabs v-model="activeTab">
          <TabsList class="grid w-full grid-cols-3 bg-secondary/20">
            <TabsTrigger value="paste" class="text-xs">
              <ClipboardPaste class="w-3 h-3 mr-1.5" />
              粘贴
            </TabsTrigger>
            <TabsTrigger value="url" class="text-xs">
              <Link class="w-3 h-3 mr-1.5" />
              URL
            </TabsTrigger>
            <TabsTrigger value="upload" class="text-xs">
              <Upload class="w-3 h-3 mr-1.5" />
              上传
            </TabsTrigger>
          </TabsList>

          <!-- 粘贴 -->
          <TabsContent value="paste" class="space-y-3 pt-3">
            <input
              v-model="pasteTitle"
              type="text"
              placeholder="标题"
              class="w-full bg-secondary/20 border border-border/40 rounded-lg px-3 py-2 text-sm focus:ring-1 focus:ring-primary focus:border-primary transition-all placeholder:text-muted-foreground/60"
            />
            <textarea
              v-model="pasteContent"
              placeholder="内容（Markdown）"
              class="w-full bg-secondary/20 border border-border/40 rounded-lg px-3 py-2 text-sm resize-none min-h-[160px] focus:ring-1 focus:ring-primary focus:border-primary transition-all placeholder:text-muted-foreground/60"
            ></textarea>
            <div class="flex items-center gap-2">
              <DropdownMenu>
                <DropdownMenuTrigger as-child>
                  <Button variant="outline" size="sm" class="h-7 px-2.5 text-xs bg-secondary/20 border-border/40 hover:bg-secondary/40">
                    {{ currentTypeLabel }}
                    <ChevronDown class="w-3 h-3 ml-1 text-muted-foreground" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start" class="w-32 bg-[#1c1c1e] border-border">
                  <DropdownMenuItem
                    v-for="opt in typeOptions"
                    :key="opt.value"
                    :class="pasteType === opt.value ? 'bg-secondary/40' : ''"
                    class="text-xs cursor-pointer"
                    @click="pasteType = opt.value"
                  >
                    {{ opt.label }}
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
              <input
                v-model="pasteSourceUrl"
                type="url"
                placeholder="来源 URL（可选）"
                class="flex-1 bg-secondary/20 border border-border/40 rounded-lg px-3 py-1.5 text-xs focus:ring-1 focus:ring-primary focus:border-primary transition-all placeholder:text-muted-foreground/60"
              />
            </div>
          </TabsContent>

          <!-- URL -->
          <TabsContent value="url" class="space-y-3 pt-3">
            <input
              v-model="fetchUrl"
              type="url"
              placeholder="https://…"
              class="w-full bg-secondary/20 border border-border/40 rounded-lg px-3 py-2 text-sm focus:ring-1 focus:ring-primary focus:border-primary transition-all placeholder:text-muted-foreground/60"
            />
            <p class="text-xs text-muted-foreground">抓取网页正文并入库，类型为文章。</p>
          </TabsContent>

          <!-- 上传 -->
          <TabsContent value="upload" class="space-y-3 pt-3">
            <input
              ref="fileInput"
              type="file"
              accept=".md,.txt,.pdf"
              class="hidden"
              @change="handleFileChange"
            />
            <button
              type="button"
              class="w-full border border-dashed border-border/60 rounded-lg px-3 py-8 text-sm text-muted-foreground hover:border-primary/60 hover:text-foreground transition-all"
              @click="triggerFileInput"
            >
              <Upload class="w-5 h-5 mx-auto mb-2 opacity-60" />
              {{ uploadFile ? uploadFile.name : '点击选择文件' }}
            </button>
            <p class="text-xs text-muted-foreground">支持 .md / .txt / .pdf，单个文件不超过 20MB。</p>
          </TabsContent>
        </Tabs>
      </div>

      <!-- Footer -->
      <div class="px-5 py-3 mt-4 bg-[#18181a] border-t border-border/40 flex items-center justify-between gap-3">
        <p class="flex-1 text-xs text-red-400 truncate">{{ errorMessage }}</p>
        <Button
          v-if="activeTab === 'paste'"
          :disabled="!pasteTitle.trim() || !pasteContent.trim() || submitting"
          class="bg-[#5e6ad2] hover:bg-[#4b54a8] text-white h-7 px-3 text-xs font-medium"
          @click="submitPaste"
        >
          {{ submitting ? '提交中…' : '添加' }}
        </Button>
        <Button
          v-else-if="activeTab === 'url'"
          :disabled="!fetchUrl.trim() || submitting"
          class="bg-[#5e6ad2] hover:bg-[#4b54a8] text-white h-7 px-3 text-xs font-medium"
          @click="submitFetch"
        >
          {{ submitting ? '抓取中…' : '抓取' }}
        </Button>
        <Button
          v-else
          :disabled="!uploadFile || submitting"
          class="bg-[#5e6ad2] hover:bg-[#4b54a8] text-white h-7 px-3 text-xs font-medium"
          @click="submitUpload"
        >
          {{ submitting ? '导入中…' : '导入' }}
        </Button>
      </div>
    </DialogContent>
  </Dialog>
</template>
