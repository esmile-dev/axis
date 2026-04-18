import { ref } from 'vue'

export function useImageUpload() {
  const isUploading = ref(false)
  const uploadError = ref<string | null>(null)

  async function uploadImage(file: File): Promise<string | null> {
    isUploading.value = true
    uploadError.value = null

    try {
      const formData = new FormData()
      formData.append('file', file)

      const result = await $fetch<{ url: string }>('/api/upload', {
        method: 'POST',
        body: formData
      })

      return result.url
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

  return {
    isUploading,
    uploadError,
    uploadImage,
    insertImageMarkdown
  }
}