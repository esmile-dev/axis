import { readFile } from 'fs/promises'
import { join } from 'path'

export default defineEventHandler(async (event) => {
  const filename = getRouterParam(event, 'filename')

  if (!filename) {
    throw createError({ statusCode: 400, statusMessage: 'Filename required' })
  }

  // Security: prevent directory traversal
  if (filename.includes('/') || filename.includes('..')) {
    throw createError({ statusCode: 400, statusMessage: 'Invalid filename' })
  }

  const uploadsDir = join(process.cwd(), 'server', 'uploads')

  try {
    const fileData = await readFile(join(uploadsDir, filename))

    // Determine content type from extension
    const ext = filename.split('.').pop()?.toLowerCase()
    const contentTypes: Record<string, string> = {
      'jpg': 'image/jpeg',
      'jpeg': 'image/jpeg',
      'png': 'image/png',
      'gif': 'image/gif',
      'webp': 'image/webp'
    }

    return new Response(fileData, {
      headers: {
        'Content-Type': contentTypes[ext || ''] || 'application/octet-stream',
        'Cache-Control': 'public, max-age=31536000'
      }
    })
  } catch {
    throw createError({ statusCode: 404, statusMessage: 'File not found' })
  }
})