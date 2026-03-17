import { prisma } from '../../utils/prisma'
import { InboxItemStatus } from '../../../app/generated/prisma/client'

export default defineEventHandler(async (event) => {
    const id = getRouterParam(event, 'id')
    if (!id) {
        throw createError({ statusCode: 400, statusMessage: 'Missing ID' })
    }

    const body = await readBody(event)
    const data: { content?: string; status?: InboxItemStatus } = {}

    if (body.content !== undefined) {
        data.content = body.content
    }
    if (body.status !== undefined) {
        data.status = body.status as InboxItemStatus
    }

    return await prisma.inboxItem.update({
        where: { id },
        data
    })
})