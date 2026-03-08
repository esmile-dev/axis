import { prisma } from '../../utils/prisma'
export default defineEventHandler(async (event) => {
    const id = getRouterParam(event, 'id')
    if (!id) {
        throw createError({ statusCode: 400, statusMessage: 'Missing ID' })
    }
    return await prisma.kanbanTask.delete({
        where: { id }
    })
})
