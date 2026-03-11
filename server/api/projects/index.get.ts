import { prisma } from '../../utils/prisma'
export default defineEventHandler(async () => {
    return await prisma.project.findMany({
        orderBy: { order: 'asc' },
        include: {
            _count: {
                select: { issues: true }
            }
        }
    })
})
