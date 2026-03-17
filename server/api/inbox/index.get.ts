import { prisma } from '../../utils/prisma'

export default defineEventHandler(async (event) => {
    const query = getQuery(event)
    const { status, timeRange, search } = query

    const where: any = {}

    // 状态筛选
    if (status && status !== 'all') {
        where.status = status
    }

    // 时间筛选
    if (timeRange && timeRange !== 'all') {
        const now = new Date()
        let startDate: Date

        switch (timeRange) {
            case 'today':
                startDate = new Date(now.getFullYear(), now.getMonth(), now.getDate())
                break
            case 'week':
                startDate = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)
                break
            case 'month':
                startDate = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000)
                break
            default:
                startDate = new Date(0)
        }
        where.createdAt = { gte: startDate }
    }

    // 搜索筛选
    if (search && typeof search === 'string' && search.trim()) {
        where.content = { contains: search.trim(), mode: 'insensitive' }
    }

    return await prisma.inboxItem.findMany({
        where,
        orderBy: { createdAt: 'desc' }
    })
})
