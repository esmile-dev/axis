import { prisma } from '../../utils/prisma'
export default defineEventHandler(async (event) => {
    const body = await readBody(event)
    return await prisma.kanbanTask.create({
        data: {
            title: body.title,
            description: body.description,
            status: body.status || 'Todo',
            order: body.order || 0
        }
    })
})
