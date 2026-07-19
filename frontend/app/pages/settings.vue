<script setup lang="ts">
import { Save, Key, Eye, EyeOff, CheckCircle } from 'lucide-vue-next'

const config = useRuntimeConfig()

const settings = reactive({
  aiApiKey: '',
  aiApiEndpoint: 'https://api.openai.com/v1',
  aiModel: 'gpt-4o-mini'
})

const showApiKey = ref(false)
const isSaving = ref(false)
const saveSuccess = ref(false)

onMounted(() => {
  const savedApiKey = localStorage.getItem('axis_ai_api_key')
  const savedEndpoint = localStorage.getItem('axis_ai_endpoint')
  const savedModel = localStorage.getItem('axis_ai_model')
  
  if (savedApiKey) settings.aiApiKey = savedApiKey
  if (savedEndpoint) settings.aiApiEndpoint = savedEndpoint
  if (savedModel) settings.aiModel = savedModel
})

async function saveSettings() {
  isSaving.value = true
  
  try {
    localStorage.setItem('axis_ai_api_key', settings.aiApiKey)
    localStorage.setItem('axis_ai_endpoint', settings.aiApiEndpoint)
    localStorage.setItem('axis_ai_model', settings.aiModel)
    
    saveSuccess.value = true
    setTimeout(() => {
      saveSuccess.value = false
    }, 2000)
  } catch (error) {
    console.error('Failed to save settings:', error)
  } finally {
    isSaving.value = false
  }
}


</script>

<template>
  <div class="p-8 max-w-2xl mx-auto">
    <header class="mb-10">
      <h1 class="text-3xl font-bold tracking-tight mb-2">Settings</h1>
      <p class="text-muted-foreground">Configure your AI Station preferences.</p>
    </header>

    <div class="space-y-8">
      <section class="bg-card border border-border rounded-2xl p-6">
        <div class="flex items-center gap-3 mb-6">
          <div class="w-10 h-10 rounded-xl bg-primary/10 flex items-center justify-center">
            <Key class="w-5 h-5 text-primary" />
          </div>
          <div>
            <h2 class="font-semibold">AI Configuration</h2>
            <p class="text-sm text-muted-foreground">Set up your AI provider for PRD expansion</p>
          </div>
        </div>

        <div class="space-y-6">
          <div>
            <label class="block text-sm font-medium mb-2">API Key</label>
            <div class="relative">
              <input
                v-model="settings.aiApiKey"
                :type="showApiKey ? 'text' : 'password'"
                placeholder="sk-..."
                class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 pr-12 focus:ring-2 focus:ring-ring transition-all font-mono text-sm"
              />
              <button
                @click="showApiKey = !showApiKey"
                class="absolute right-3 top-1/2 -translate-y-1/2 p-1 text-muted-foreground hover:text-foreground transition-colors"
              >
                <Eye v-if="!showApiKey" class="w-4 h-4" />
                <EyeOff v-else class="w-4 h-4" />
              </button>
            </div>
            <p class="text-xs text-muted-foreground mt-2">
              Your API key is stored locally and never sent to our servers.
            </p>
          </div>

          <div>
            <label class="block text-sm font-medium mb-2">API Endpoint</label>
            <input
              v-model="settings.aiApiEndpoint"
              type="text"
              placeholder="https://api.openai.com/v1"
              class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all"
            />
            <p class="text-xs text-muted-foreground mt-2">
              Compatible with OpenAI, Azure OpenAI, or any OpenAI-compatible API.
            </p>
          </div>

          <div>
            <label class="block text-sm font-medium mb-2">Model</label>
            <input
              v-model="settings.aiModel"
              type="text"
              placeholder="gpt-4o-mini"
              class="w-full bg-secondary/50 border-none rounded-xl px-4 py-3 focus:ring-2 focus:ring-ring transition-all"
            />
            <p class="text-xs text-muted-foreground mt-2">
              Enter any model name, e.g. gpt-4o-mini, claude-3-sonnet, deepseek-chat, etc.
            </p>
          </div>
        </div>
      </section>

      <div class="flex items-center justify-end gap-4">
        <div v-if="saveSuccess" class="flex items-center gap-2 text-sm text-green-500">
          <CheckCircle class="w-4 h-4" />
          Settings saved
        </div>
        <button
          @click="saveSettings"
          :disabled="isSaving"
          class="flex items-center gap-2 px-6 py-2.5 bg-primary text-primary-foreground rounded-lg font-medium hover:opacity-90 transition-all disabled:opacity-50"
        >
          <Save class="w-4 h-4" />
          {{ isSaving ? 'Saving...' : 'Save Settings' }}
        </button>
      </div>
    </div>
  </div>
</template>
