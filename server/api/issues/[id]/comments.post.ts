import { prisma } from '../../../utils/prisma'

export default defineEventHandler(async (event) => {
    const issueId = getRouterParam(event, 'id')
    
    if (!issueId) {
        throw createError({ statusCode: 400, statusMessage: 'Issue ID is required' })
    }

    const body = await readBody(event)

    if (!body.content || typeof body.content !== 'string' || body.content.trim() === '') {
        throw createError({ statusCode: 400, statusMessage: 'Comment content is required' })
    }

    const comment = await prisma.comment.create({
        data: {
            content: body.content.trim(),
            issueId
        }
    })

    return comment
})
