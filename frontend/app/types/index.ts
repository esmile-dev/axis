export interface Issue {
  id: string
  title: string
  description?: string
  status: 'BACKLOG' | 'TODO' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'
  priority: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
  type: 'BUG' | 'FEATURE' | 'IMPROVEMENT'
  order: number
  attachment?: string | null
  projectId?: string | null
  createdAt: string
  updatedAt: string
}
