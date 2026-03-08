import { prisma } from '../../utils/prisma'
export default defineEventHandler(async () => {
    return await prisma.knowledgeDocument.findMany({
        orderBy: { updatedAt: 'desc' }
    })
})
