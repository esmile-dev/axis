<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'

const props = defineProps<{
  content: string
}>()

// Configure marked for safe rendering
marked.setOptions({
  breaks: true,
  gfm: true
})

const renderedHtml = computed(() => {
  if (!props.content) return ''
  return marked.parse(props.content) as string
})
</script>

<template>
  <div
    class="markdown-content prose prose-invert prose-sm max-w-none"
    v-html="renderedHtml"
  />
</template>

<style scoped>
.markdown-content img {
  max-width: 100%;
  border-radius: 8px;
  margin: 8px 0;
}
.markdown-content p {
  margin: 0.5rem 0;
}
.markdown-content pre {
  background: rgba(0, 0, 0, 0.3);
  border-radius: 6px;
  padding: 8px 12px;
  overflow-x: auto;
}
.markdown-content code {
  font-size: 0.85em;
}
.markdown-content :deep(a) {
  color: #5e6ad2;
}
</style>