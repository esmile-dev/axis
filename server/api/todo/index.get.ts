import { prisma } from '../../utils/prisma'
export default defineEventHandler(async () => {
    return await prisma.todoItem.findMany({
        orderBy: { createdAt: 'desc' }
    })
})
