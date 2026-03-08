import { prisma } from '../../utils/prisma'
export default defineEventHandler(async () => {
    return await prisma.kanbanTask.findMany({
        orderBy: { order: 'asc' }
    })
})
