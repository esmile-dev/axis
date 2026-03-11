import { prisma } from '../../utils/prisma'
export default defineEventHandler(async (event) => {
    const body = await readBody(event)
    return await prisma.issue.create({
        data: {
            title: body.title,
            description: body.description,
            status: body.status || 'TODO',
            priority: body.priority || 'MEDIUM',
            type: body.type || 'FEATURE',
            order: body.order || 0,
            projectId: body.projectId || null,
            attachment: body.attachment || null
        },
        include: { project: true }
    })
})
