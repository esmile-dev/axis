import { prisma } from '../../utils/prisma'
export default defineEventHandler(async (event) => {
    const id = getRouterParam(event, 'id')
    if (!id) {
        throw createError({ statusCode: 400, statusMessage: 'Missing ID' })
    }
    const body = await readBody(event)
    return await prisma.todoItem.update({
        where: { id },
        data: {
            title: body.title,
            completed: body.completed
        }
    })
})
