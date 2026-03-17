import { prisma } from '../../../utils/prisma'

export default defineEventHandler(async (event) => {
    const id = getRouterParam(event, 'id')
    if (!id) {
        throw createError({ statusCode: 400, statusMessage: 'Missing ID' })
    }

    // 获取原 InboxItem
    const inboxItem = await prisma.inboxItem.findUnique({ where: { id } })
    if (!inboxItem) {
        throw createError({ statusCode: 404, statusMessage: 'InboxItem not found' })
    }

    const body = await readBody(event)

    // 创建 Project
    const project = await prisma.project.create({
        data: {
            name: body.name || inboxItem.content,
            description: body.description,
            status: body.status || 'PLANNING'
        }
    })

    // 如果指定删除原项
    if (body.deleteOriginal) {
        await prisma.inboxItem.delete({ where: { id } })
    }

    return { project, deleted: body.deleteOriginal }
})