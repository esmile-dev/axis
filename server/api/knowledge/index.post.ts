import { prisma } from '../../utils/prisma'
export default defineEventHandler(async (event) => {
    const body = await readBody(event)
    return await prisma.knowledgeDocument.create({
        data: {
            title: body.title,
            content: body.content
        }
    })
})
