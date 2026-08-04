<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ListTree, Network } from 'lucide-vue-next'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import type { IMarkmapOptions, Markmap } from 'markmap-view'
import type { Transformer } from 'markmap-lib'

const props = defineProps<{
  content: string
  active: boolean
}>()

const mode = ref<'map' | 'outline'>('map')
const mapError = ref(false)
const svgRef = ref<SVGSVGElement | null>(null)

let mm: Markmap | null = null
let transformer: Transformer | null = null
// 隐藏（display:none）状态下渲染/fit 出的缩放无效，置真后在变为可见时补一次 fit
let needsFit = false

// 暗色背景可读的分支配色（默认 d3 category10 的红/棕/灰在暗色下偏暗）
const DARK_PALETTE = ['#7aa2f7', '#9ece6a', '#e0af68', '#bb9af7', '#f7768e', '#7dcfff', '#73daca', '#ff9e64']

const markmapOptions: Partial<IMarkmapOptions> = {
  maxWidth: 320,
  spacingVertical: 8,
  paddingX: 12,
  color: (node) => {
    const path = node.state?.path ?? ''
    let hash = 0
    for (let i = 0; i < path.length; i++) hash = (hash * 31 + path.charCodeAt(i)) | 0
    return DARK_PALETTE[Math.abs(hash) % DARK_PALETTE.length] as string
  },
}

// markmap 依赖 DOM：动态导入保证只在客户端执行，SSR 不触碰
async function renderMap() {
  if (mode.value !== 'map') return
  await nextTick()
  const svg = svgRef.value
  if (!svg) return
  try {
    const [{ Markmap }, markmapLib] = await Promise.all([
      import('markmap-view'),
      import('markmap-lib'),
    ])
    transformer ??= new markmapLib.Transformer()
    const { root } = transformer.transform(props.content)
    mm ??= Markmap.create(svg, markmapOptions)
    await mm.setData(root)
    await mm.fit()
    needsFit = !props.active
    mapError.value = false
  } catch (err) {
    console.error('Failed to render mindmap:', err)
    mapError.value = true
    mode.value = 'outline' // 渲染失败降级到大纲视图
  }
}

watch(() => props.content, renderMap)
watch(mode, renderMap)
// 仅补偿隐藏期挂载/渲染（needsFit）：同一条目内 tab 正常往返不触发重 fit
watch(() => props.active, async (active) => {
  if (!active || !needsFit) return
  needsFit = false
  await nextTick()
  if (mode.value === 'map') await mm?.fit()
})
onMounted(renderMap)
onBeforeUnmount(() => {
  mm?.destroy()
  mm = null
})
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <!-- 工具条：脑图 / 大纲切换 -->
    <div class="mb-2 flex flex-shrink-0 items-center justify-end">
      <p v-if="mapError" class="mr-auto text-xs text-muted-foreground">脑图渲染失败，已切换为大纲视图</p>
      <div class="inline-flex items-center rounded-lg bg-muted p-0.5 text-muted-foreground">
        <button
          class="inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-xs transition-colors"
          :class="mode === 'map' ? 'bg-background text-foreground shadow' : 'hover:text-foreground'"
          @click="mode = 'map'"
        >
          <Network class="h-3 w-3" />
          脑图
        </button>
        <button
          class="inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-xs transition-colors"
          :class="mode === 'outline' ? 'bg-background text-foreground shadow' : 'hover:text-foreground'"
          @click="mode = 'outline'"
        >
          <ListTree class="h-3 w-3" />
          大纲
        </button>
      </div>
    </div>

    <!-- markmap-dark 启用 markmap 内置暗色变量（节点文字/连线协调暗色背景） -->
    <div v-show="mode === 'map'" class="markmap-dark min-h-0 flex-1">
      <svg ref="svgRef" class="h-full w-full" />
    </div>
    <div v-show="mode === 'outline'" class="min-h-0 flex-1 overflow-y-auto pr-2">
      <MarkdownRenderer :content="content" />
    </div>
  </div>
</template>
