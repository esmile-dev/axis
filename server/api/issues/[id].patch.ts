import { prisma } from '../../utils/prisma'
export default defineEventHandler(async (event) => {
    const id = getRouterParam(event, 'id')
    if (!id) {
        throw createError({ statusCode: 400, statusMessage: 'Missing ID' })
    }
    const body = await readBody(event)
    return await prisma.issue.update({
        where: { id },
        data: {
            title: body.title,
            description: body.description,
            status: body.status,
            priority: body.priority,
            type: body.type,
            order: body.order,
            attachment: body.attachment,
            projectId: body.projectId
        }
    })
})
