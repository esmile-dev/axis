import { prisma } from '../../utils/prisma'
export default defineEventHandler(async (event) => {
    const query = getQuery(event)
    const projectId = query.projectId as string | undefined
    
    if (projectId === 'none') {
        return await prisma.issue.findMany({
            where: { projectId: null },
            orderBy: { order: 'asc' }
        })
    } else if (projectId) {
        return await prisma.issue.findMany({
            where: { projectId },
            orderBy: { order: 'asc' }
        })
    }
    
    return await prisma.issue.findMany({
        orderBy: { order: 'asc' },
        include: { project: true }
    })
})
