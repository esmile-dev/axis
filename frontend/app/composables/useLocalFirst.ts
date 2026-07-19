import type { Ref } from 'vue'

const STORAGE_PREFIX = 'axis_'

export interface LocalFirstReturn<T extends { id: string }> {
  items: Ref<T[]>
  isSyncing: Ref<boolean>
  lastSyncAt: Ref<Date | null>
  init: () => void
  sync: () => Promise<void>
  addItem: (item: T) => void
  updateItem: (id: string, updates: Partial<T>) => void
  removeItem: (id: string) => void
}

export function useLocalFirst<T extends { id: string }>(
  key: string,
  fetchFn: () => Promise<T[]>
): LocalFirstReturn<T> {
  const storageKey = STORAGE_PREFIX + key
  const items: Ref<T[]> = ref([])
  const isSyncing = ref(false)
  const lastSyncAt = ref<Date | null>(null)

  function loadFromLocal(): T[] {
    if (import.meta.client) {
      const stored = localStorage.getItem(storageKey)
      if (stored) {
        try {
          return JSON.parse(stored)
        } catch {
          return []
        }
      }
    }
    return []
  }

  function saveToLocal(data: T[]) {
    if (import.meta.client) {
      localStorage.setItem(storageKey, JSON.stringify(data))
    }
  }

  async function sync() {
    isSyncing.value = true
    try {
      const serverData = await fetchFn()
      items.value = serverData
      saveToLocal(serverData)
      lastSyncAt.value = new Date()
    } catch (error) {
      console.error(`Sync failed for ${key}:`, error)
    } finally {
      isSyncing.value = false
    }
  }

  function init() {
    const localData = loadFromLocal()
    if (localData.length > 0) {
      items.value = localData
    }
    sync()
  }

  function addItem(item: T) {
    items.value.unshift(item)
    saveToLocal(items.value)
  }

  function updateItem(id: string, updates: Partial<T>) {
    const index = items.value.findIndex(i => i.id === id)
    if (index !== -1) {
      items.value[index] = { ...items.value[index], ...updates } as T
      saveToLocal(items.value)
    }
  }

  function removeItem(id: string) {
    items.value = items.value.filter(i => i.id !== id)
    saveToLocal(items.value)
  }

  return {
    items,
    isSyncing,
    lastSyncAt,
    init,
    sync,
    addItem,
    updateItem,
    removeItem
  }
}
