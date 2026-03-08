import { prisma } from '../../utils/prisma'
export default defineEventHandler(async (event) => {
    const body = await readBody(event)
    return await prisma.inboxItem.create({
        data: {
            content: body.content
        }
    })
})
