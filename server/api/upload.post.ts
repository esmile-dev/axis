import { writeFile } from 'fs/promises'
import { join } from 'path'
import { createHash } from 'crypto'

export default defineEventHandler(async (event) => {
  const formData = await readMultipartFormData(event)

  if (!formData || formData.length === 0) {
    throw createError({ statusCode: 400, statusMessage: 'No file uploaded' })
  }

  const file = formData[0]
  if (!file.filename || !file.data) {
    throw createError({ statusCode: 400, statusMessage: 'Invalid file data' })
  }

  // Validate file type (images only)
  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(file.type || '')) {
    throw createError({ statusCode: 400, statusMessage: 'Only image files allowed (jpeg, png, gif, webp)' })
  }

  // Validate file size (max 5MB)
  if (file.data.length > 5 * 1024 * 1024) {
    throw createError({ statusCode: 400, statusMessage: 'File too large (max 5MB)' })
  }

  // Generate unique filename
  const hash = createHash('md5').update(file.data).digest('hex').slice(0, 8)
  const timestamp = Date.now()
  const ext = file.filename.split('.').pop() || 'png'
  const filename = `${timestamp}-${hash}.${ext}`

  // Save to uploads directory
  const uploadsDir = join(process.cwd(), 'server', 'uploads')
  await writeFile(join(uploadsDir, filename), file.data)

  return {
    url: `/api/uploads/${filename}`,
    filename: file.filename,
    size: file.data.length
  }
})