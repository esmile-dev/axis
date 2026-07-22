<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Save, Key, Eye, EyeOff, CheckCircle, RefreshCw, Plug } from 'lucide-vue-next'

interface AiConfig {
  apiKey: string
  endpoint: string
  model: string
  source: 'db' | 'env'
}

const api = useApi()

const settings = reactive<AiConfig>({
  apiKey: '',
  endpoint: 'https://api.openai.com/v1',
  model: 'gpt-4o-mini',
  source: 'env'
})

const showApiKey = ref(false)
const isSaving = ref(false)
const isTesting = ref(false)
const isReloading = ref(false)
const saveSuccess = ref(false)
const testResult = ref<{ success: boolean; message: string } | null>(null)

onMounted(() => {
  loadConfig()
})

async function loadConfig() {
  try {
    const cfg = await api<AiConfig>('/api/v1/config/ai')
    settings.apiKey = cfg.apiKey === '***' ? '' : cfg.apiKey
    settings.endpoint = cfg.endpoint
    settings.model = cfg.model
    settings.source = cfg.source
  } catch (error) {
    console.error('Failed to load AI config:', error)
  }
}

async function saveSettings() {
  isSaving.value = true
  try {
    await api('/api/v1/config/ai', {
      method: 'PUT',
      body: {
        apiKey: settings.apiKey,
        endpoint: settings.endpoint,
        model: settings.model
      }
    })
    saveSuccess.value = true
    setTimeout(() => { saveSuccess.value = false }, 2000)
    await loadConfig()
  } catch (error) {
    console.error('Failed to save settings:', error)
  } finally {
    isSaving.value = false
  }
}

async function testConnection() {
  isTesting.value = true
  testResult.value = null
  try {
    const res = await api<{ success: boolean; message: string; model?: string }>('/api/v1/config/ai/test', {
      method: 'POST'
    })
    testResult.value = {
      success: res.success,
      message: res.success ? `${res.message} (${res.model})` : res.message
    }
  } catch (error) {
    testResult.value = { success: false, message: '请求失败' }
  } finally {
    isTesting.value = false
  }
}

async function reloadConfig() {
  isReloading.value = true
  try {
    await api('/api/v1/config/ai/reload', { method: 'POST' })
    await loadConfig()
    testResult.value = { success: true, message: '配置已重新加载' }
  } catch (error) {
    testResult.value = { success: false, message: '重新加载失败' }
  } finally {
    isReloading.value = false
  }
}

const sourceLabel = computed(() => {
  return settings.source === 'db' ? '数据库' : '环境变量'
})
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
            <p class="text-sm text-muted-foreground">
              当前生效配置来源：
              <span class="text-foreground font-medium">{{ sourceLabel }}</span>
            </p>
          </div>
        </div>

        <div class="space-y-6">
          <div>
            <label class="block text-sm font-medium mb-2">API Key</label>
            <div class="relative">
              <input
                v-model="settings.apiKey"
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
              API key 保存到服务端数据库并加密存储。
            </p>
          </div>

          <div>
            <label class="block text-sm font-medium mb-2">API Endpoint</label>
            <input
              v-model="settings.endpoint"
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
              v-model="settings.model"
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

      <div class="flex flex-wrap items-center justify-end gap-3">
        <div v-if="testResult" class="flex items-center gap-2 text-sm"
          :class="testResult.success ? 'text-green-500' : 'text-red-500'">
          <Plug class="w-4 h-4" />
          {{ testResult.message }}
        </div>
        <div v-if="saveSuccess" class="flex items-center gap-2 text-sm text-green-500">
          <CheckCircle class="w-4 h-4" />
          Settings saved
        </div>
        <button
          @click="testConnection"
          :disabled="isTesting"
          class="flex items-center gap-2 px-4 py-2.5 border border-border rounded-lg font-medium hover:bg-secondary transition-all disabled:opacity-50"
        >
          <Plug class="w-4 h-4" />
          {{ isTesting ? 'Testing...' : 'Test Connection' }}
        </button>
        <button
          @click="reloadConfig"
          :disabled="isReloading"
          class="flex items-center gap-2 px-4 py-2.5 border border-border rounded-lg font-medium hover:bg-secondary transition-all disabled:opacity-50"
        >
          <RefreshCw class="w-4 h-4" :class="{ 'animate-spin': isReloading }" />
          {{ isReloading ? 'Reloading...' : 'Apply Now' }}
        </button>
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
