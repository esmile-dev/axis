<script setup lang="ts">
import { computed } from 'vue'
import { AlertCircle, RotateCcw } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import type { ArtifactStatus, KnowledgeArtifact } from '@/types'

const props = defineProps<{
  kind: 'SUMMARY' | 'MINDMAP'
  status: ArtifactStatus
  artifact: KnowledgeArtifact | null
  timedOut: boolean
}>()

const emit = defineEmits<{
  regenerate: []
}>()

const kindLabels: Record<string, string> = { SUMMARY: '总结', MINDMAP: '脑图' }

const isGenerating = computed(() => props.status === 'PENDING' || props.status === 'GENERATING')
</script>

<template>
  <!-- PENDING / GENERATING：骨架屏（轮询超时后提示手动刷新） -->
  <div v-if="isGenerating" class="space-y-3">
    <div class="h-4 w-2/3 rounded bg-secondary/40 animate-pulse" />
    <div class="h-4 w-full rounded bg-secondary/30 animate-pulse" />
    <div class="h-4 w-5/6 rounded bg-secondary/30 animate-pulse" />
    <div class="h-4 w-1/2 rounded bg-secondary/30 animate-pulse" />
    <p class="pt-2 text-xs text-muted-foreground">
      {{ timedOut ? '生成时间较长，请稍后手动刷新查看' : `AI 正在生成${kindLabels[kind]}…` }}
    </p>
  </div>

  <!-- FAILED：错误展示 + 重试 -->
  <div v-else-if="status === 'FAILED'" class="rounded-lg border border-destructive/30 bg-destructive/5 p-4">
    <div class="flex items-start gap-3">
      <AlertCircle class="mt-0.5 h-4 w-4 flex-shrink-0 text-destructive" />
      <div class="min-w-0 flex-1">
        <p class="text-sm text-destructive">{{ kindLabels[kind] }}生成失败</p>
        <p v-if="artifact?.error" class="mt-1 break-all text-xs text-muted-foreground">{{ artifact.error }}</p>
        <Button size="sm" variant="outline" class="mt-3 h-7 px-3 text-xs" @click="emit('regenerate')">
          <RotateCcw class="mr-1.5 h-3 w-3" />
          重试
        </Button>
      </div>
    </div>
  </div>

  <!-- DONE：渲染产物；产物缺失兜底空态 -->
  <slot v-else-if="artifact?.content" :artifact="artifact" />
  <div v-else class="py-12 text-center text-muted-foreground">
    <p class="text-sm">暂无{{ kindLabels[kind] }}内容</p>
  </div>
</template>
