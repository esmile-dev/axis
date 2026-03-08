import type { LocalFirstReturn } from './useLocalFirst'

export function useOptimistic<T extends { id: string }>(
  localFirst: LocalFirstReturn<T>
) {
  const pendingOperations = ref(new Map<string, 'create' | 'update' | 'delete'>())

  async function optimisticAdd(
    createFn: (item: T) => Promise<T>,
    tempItem: T
  ): Promise<T> {
    pendingOperations.value.set(tempItem.id, 'create')
    localFirst.addItem(tempItem)
    
    try {
      const serverItem = await createFn(tempItem)
      localFirst.updateItem(tempItem.id, serverItem)
      return serverItem
    } catch (error) {
      localFirst.removeItem(tempItem.id)
      throw error
    } finally {
      pendingOperations.value.delete(tempItem.id)
    }
  }

  async function optimisticUpdate(
    updateFn: (id: string, updates: Partial<T>) => Promise<T>,
    id: string,
    updates: Partial<T>
  ): Promise<void> {
    const originalItem = localFirst.items.value.find((i) => i.id === id)
    if (!originalItem) return

    pendingOperations.value.set(id, 'update')
    localFirst.updateItem(id, updates)

    try {
      const serverItem = await updateFn(id, updates)
      localFirst.updateItem(id, serverItem)
    } catch (error) {
      if (originalItem) {
        localFirst.updateItem(id, originalItem)
      }
      throw error
    } finally {
      pendingOperations.value.delete(id)
    }
  }

  async function optimisticDelete(
    deleteFn: (id: string) => Promise<void>,
    id: string
  ): Promise<void> {
    const originalItem = localFirst.items.value.find((i) => i.id === id)
    if (!originalItem) return

    pendingOperations.value.set(id, 'delete')
    localFirst.removeItem(id)

    try {
      await deleteFn(id)
    } catch (error) {
      localFirst.addItem(originalItem)
      throw error
    } finally {
      pendingOperations.value.delete(id)
    }
  }

  function isPending(id: string): boolean {
    return pendingOperations.value.has(id)
  }

  return {
    optimisticAdd,
    optimisticUpdate,
    optimisticDelete,
    isPending
  }
}
