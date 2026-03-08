export default defineEventHandler(async (event) => {
    const query = getQuery(event)
    const title = query.title || 'Unknown Task'
    
    const body = await readBody(event).catch(() => ({}))
    const config = useRuntimeConfig()
    const apiKey = body.apiKey || config.public?.aiApiKey || ''
    const endpoint = body.endpoint || config.public?.aiEndpoint || 'https://api.openai.com/v1'
    const model = body.model || config.public?.aiModel || 'gpt-4o-mini'

    if (!apiKey) {
        const encoder = new TextEncoder()
        const stream = new ReadableStream({
            async start(controller) {
                const chunks = [
                    `# PRD: ${title}\n\n`,
                    "## 1. Objective\nAutomate the core workflow for this feature.\n\n",
                    "## 2. User Stories\n- As a user, I want to see results instantly.\n- As a system, I should handle errors gracefully.\n\n",
                    "## 3. Technical Specs\n- Frontend: Vue 3 / Nuxt 3\n- Backend: Prisma / PostgreSQL\n\n",
                    "--- \n*Demo mode - Configure your AI API key in Settings for real expansion*"
                ]
                for (const chunk of chunks) {
                    await new Promise(r => setTimeout(r, 200))
                    controller.enqueue(encoder.encode(chunk))
                }
                controller.close()
            }
        })
        return sendStream(event, stream)
    }

    const encoder = new TextEncoder()
    const stream = new ReadableStream({
        async start(controller) {
            try {
                const response = await fetch(`${endpoint}/chat/completions`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': `Bearer ${apiKey}`
                    },
                    body: JSON.stringify({
                        model,
                        messages: [
                            {
                                role: 'system',
                                content: 'You are a product manager assistant. Generate a concise PRD in Markdown format for the given feature title. Include: 1. Objective, 2. User Stories, 3. Technical Specs, 4. Acceptance Criteria. Keep it brief and actionable.'
                            },
                            {
                                role: 'user',
                                content: `Generate a PRD for: ${title}`
                            }
                        ],
                        stream: true,
                        max_tokens: 1000
                    })
                })

                if (!response.ok) {
                    throw new Error(`AI API error: ${response.status}`)
                }

                const reader = response.body?.getReader()
                if (!reader) throw new Error('No response body')

                while (true) {
                    const { done, value } = await reader.read()
                    if (done) break
                    
                    const chunk = new TextDecoder().decode(value)
                    const lines = chunk.split('\n').filter(line => line.trim().startsWith('data:'))
                    
                    for (const line of lines) {
                        const data = line.replace('data:', '').trim()
                        if (data === '[DONE]') continue
                        
                        try {
                            const parsed = JSON.parse(data)
                            const content = parsed.choices?.[0]?.delta?.content
                            if (content) {
                                controller.enqueue(encoder.encode(content))
                            }
                        } catch {
                            // Skip invalid JSON
                        }
                    }
                }
            } catch (error) {
                console.error('AI expansion error:', error)
                controller.enqueue(encoder.encode(`\n\n*Error: Failed to connect to AI API. Please check your settings.*`))
            } finally {
                controller.close()
            }
        }
    })

    return sendStream(event, stream)
})
