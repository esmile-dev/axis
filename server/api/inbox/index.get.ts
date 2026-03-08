import { prisma } from '../../utils/prisma'
export default defineEventHandler(async () => {
    return await prisma.inboxItem.findMany({
        orderBy: { createdAt: 'desc' }
    })
})
