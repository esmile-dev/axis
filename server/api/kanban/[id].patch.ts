import { prisma } from '../../utils/prisma'
export default defineEventHandler(async (event) => {
    const id = getRouterParam(event, 'id')
    if (!id) {
        throw createError({ statusCode: 400, statusMessage: 'Missing ID' })
    }
    const body = await readBody(event)
    return await prisma.kanbanTask.update({
        where: { id },
        data: {
            title: body.title,
            description: body.description,
            status: body.status,
            order: body.order
        }
    })
})
