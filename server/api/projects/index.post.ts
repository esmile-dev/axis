import { prisma } from '../../utils/prisma'
export default defineEventHandler(async (event) => {
    const body = await readBody(event)
    return await prisma.project.create({
        data: {
            name: body.name,
            description: body.description,
            status: body.status || 'PLANNING',
            order: body.order || 0
        }
    })
})
