import { prisma } from '../../utils/prisma'
export default defineEventHandler(async (event) => {
    const body = await readBody(event)
    return await prisma.todoItem.create({
        data: {
            title: body.title
        }
    })
})
