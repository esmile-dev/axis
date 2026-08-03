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

export type KnowledgeType = 'ARTICLE' | 'BOOK' | 'PODCAST' | 'VIDEO' | 'TUTORIAL' | 'NOTE'
export type KnowledgeStatus = 'UNREAD' | 'READING' | 'DONE' | 'ARCHIVED'
export type ArtifactStatus = 'PENDING' | 'GENERATING' | 'DONE' | 'FAILED'

export interface KnowledgeItemSummary {
  id: string
  type: KnowledgeType
  title: string
  status: KnowledgeStatus
  progress: number
  summaryStatus: ArtifactStatus
  mindmapStatus: ArtifactStatus
  tags: string[]
  sourceUrl: string | null
  createdAt: string
  updatedAt: string
}

export interface KnowledgeArtifact {
  kind: 'SUMMARY' | 'MINDMAP'
  content: string
  model: string | null
  error: string | null
  updatedAt: string
}

export interface KnowledgeItemDetail extends KnowledgeItemSummary {
  content: string
  artifacts: KnowledgeArtifact[]
}
