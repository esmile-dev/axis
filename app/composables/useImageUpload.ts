import { ref } from 'vue'

export function useImageUpload() {
  const isUploading = ref(false)
  const uploadError = ref<string | null>(null)

  async function uploadImage(file: File): Promise<string | null> {
    isUploading.value = true
    uploadError.value = null

    try {
      const api = useApi()
      const { public: { apiBase } } = useRuntimeConfig()
      const formData = new FormData()
      formData.append('file', file)

      const result = await api<{ url: string }>('/api/upload', {
        method: 'POST',
        body: formData
      })

      // 后端返回相对路径 /api/uploads/<file>，跨域下需拼成绝对 URL 才能被 <img> 加载
      return result.url.startsWith('http') ? result.url : `${apiBase}${result.url}`
    } catch (err: any) {
      uploadError.value = err.data?.statusMessage || 'Upload failed'
      return null
    } finally {
      isUploading.value = false
    }
  }

  function insertImageMarkdown(text: string, imageUrl: string): string {
    // Insert image markdown at cursor position or at end
    const imageMarkdown = `\n![image](${imageUrl})\n`
    return text + imageMarkdown
  }

  async function handlePaste(event: ClipboardEvent, currentText: string): Promise<string> {
    const items = event.clipboardData?.items
    if (!items) return currentText

    for (const item of items) {
      if (item.type.startsWith('image/')) {
        const file = item.getAsFile()
        if (file) {
          // 阻止默认粘贴行为（避免粘贴图片文件名）
          event.preventDefault()

          const imageUrl = await uploadImage(file)
          if (imageUrl) {
            return insertImageMarkdown(currentText, imageUrl)
          }
        }
      }
    }

    return currentText
  }

  return {
    isUploading,
    uploadError,
    uploadImage,
    insertImageMarkdown,
    handlePaste
  }
}