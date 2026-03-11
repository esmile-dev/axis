import { prisma } from '../../../utils/prisma'

export default defineEventHandler(async (event) => {
    const id = getRouterParam(event, 'id')
    
    if (!id) {
        throw createError({ statusCode: 400, statusMessage: 'Issue ID is required' })
    }

    const issue = await prisma.issue.findUnique({
        where: { id },
        include: {
            comments: {
                orderBy: { createdAt: 'desc' }
            }
        }
    })

    if (!issue) {
        throw createError({ statusCode: 404, statusMessage: 'Issue not found' })
    }

    return issue
})
