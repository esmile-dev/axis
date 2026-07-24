<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Save, Key, Eye, EyeOff, CheckCircle, RefreshCw, Plug,
  Plus, Pencil, Trash2, Power, Loader2
} from 'lucide-vue-next'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'

interface AiProfile {
  id: string
  name: string
  apiKey: string
  endpoint: string
  model: string
  isActive: boolean
}

interface ProfileForm {
  id?: string
  name: string
  apiKey: string
  endpoint: string
  model: string
}

const api = useApi()

const profiles = ref<AiProfile[]>([])
const activeProfile = computed(() => profiles.value.find(p => p.isActive))
const sourceLabel = computed(() => activeProfile.value ? '数据库' : '环境变量')

const isModalOpen = ref(false)
const isSubmitting = ref(false)
const showApiKey = ref(false)
const testResult = ref<{ id: string; success: boolean; message: string } | null>(null)
const savingId = ref<string | null>(null)
const activatingId = ref<string | null>(null)
const deletingId = ref<string | null>(null)

const form = reactive<ProfileForm>({
  name: '',
  apiKey: '',
  endpoint: 'https://api.openai.com/v1',
  model: 'gpt-4o-mini'
})
const isEditing = computed(() => !!form.id)

onMounted(() => {
  loadProfiles()
})

async function loadProfiles() {
  try {
    profiles.value = await api<AiProfile[]>('/api/v1/config/ai/profiles')
  } catch (error) {
    console.error('Failed to load AI profiles:', error)
  }
}

function openCreate() {
  form.id = undefined
  form.name = ''
  form.apiKey = ''
  form.endpoint = 'https://api.openai.com/v1'
  form.model = 'gpt-4o-mini'
  showApiKey.value = false
  isModalOpen.value = true
}

function openEdit(profile: AiProfile) {
  form.id = profile.id
  form.name = profile.name
  form.apiKey = '' // blank means keep existing key
  form.endpoint = profile.endpoint
  form.model = profile.model
  showApiKey.value = false
  isModalOpen.value = true
}

async function saveProfile() {
  isSubmitting.value = true
  try {
    if (isEditing.value) {
      await api(`/api/v1/config/ai/profiles/${form.id}`, {
        method: 'PUT',
        body: {
          name: form.name,
          apiKey: form.apiKey,
          endpoint: form.endpoint,
          model: form.model
        }
      })
    } else {
      await api('/api/v1/config/ai/profiles', {
        method: 'POST',
        body: {
          name: form.name,
          apiKey: form.apiKey,
          endpoint: form.endpoint,
          model: form.model
        }
      })
    }
    isModalOpen.value = false
    await loadProfiles()
  } catch (error) {
    console.error('Failed to save profile:', error)
  } finally {
    isSubmitting.value = false
  }
}

async function activateProfile(id: string) {
  activatingId.value = id
  try {
    await api(`/api/v1/config/ai/profiles/${id}/activate`, { method: 'POST' })
    await loadProfiles()
  } catch (error) {
    console.error('Failed to activate profile:', error)
  } finally {
    activatingId.value = null
  }
}

async function deleteProfile(id: string) {
  if (!confirm('确定删除这条 AI 配置？')) return
  deletingId.value = id
  try {
    await api(`/api/v1/config/ai/profiles/${id}`, { method: 'DELETE' })
    await loadProfiles()
  } catch (error: any) {
    alert(error?.data?.message || '删除失败')
  } finally {
    deletingId.value = null
  }
}

async function testProfile(id: string) {
  savingId.value = id
  testResult.value = null
  try {
    const res = await api<{ success: boolean; message: string; model?: string }>(
      `/api/v1/config/ai/profiles/${id}/test`,
      { method: 'POST' }
    )
    testResult.value = {
      id,
      success: res.success,
      message: res.success ? `${res.message} (${res.model})` : res.message
    }
  } catch (error) {
    testResult.value = { id, success: false, message: '请求失败' }
  } finally {
    savingId.value = null
  }
}

async function reloadConfig() {
  try {
    await api('/api/v1/config/ai/reload', { method: 'POST' })
    await loadProfiles()
  } catch (error) {
    console.error('Failed to reload config:', error)
  }
}

function maskKey(key: string) {
  if (!key || key.length < 8) return '***'
  return key.slice(0, 4) + '***' + key.slice(-4)
}
</script>

<template>
  <div class="p-8 max-w-3xl mx-auto">
    <header class="mb-10">
      <h1 class="text-3xl font-bold tracking-tight mb-2">Settings</h1>
      <p class="text-muted-foreground">Configure your AI Station preferences.</p>
    </header>

    <div class="space-y-6">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-3">
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
        <div class="flex items-center gap-2">
          <Button variant="outline" size="sm" @click="reloadConfig">
            <RefreshCw class="w-4 h-4 mr-1.5" />
            Reload
          </Button>
          <Button size="sm" @click="openCreate">
            <Plus class="w-4 h-4 mr-1.5" />
            Add Config
          </Button>
        </div>
      </div>

      <div class="grid gap-4">
        <div
          v-for="profile in profiles"
          :key="profile.id"
          class="bg-card border border-border rounded-2xl p-5 transition-all"
          :class="profile.isActive ? 'ring-1 ring-primary/50' : ''"
        >
          <div class="flex items-start justify-between gap-4">
            <div class="min-w-0">
              <div class="flex items-center gap-2 mb-1">
                <h3 class="font-semibold truncate">{{ profile.name }}</h3>
                <Badge v-if="profile.isActive" variant="default" class="text-xs">Active</Badge>
              </div>
              <p class="text-sm text-muted-foreground truncate">
                {{ profile.model }} · {{ profile.endpoint }}
              </p>
              <p class="text-xs text-muted-foreground font-mono mt-1">
                {{ maskKey(profile.apiKey) }}
              </p>
            </div>
            <div class="flex items-center gap-1 shrink-0">
              <Button
                v-if="!profile.isActive"
                variant="ghost"
                size="icon"
                class="h-8 w-8"
                :disabled="activatingId === profile.id"
                @click="activateProfile(profile.id)"
              >
                <Power class="w-4 h-4" :class="{ 'animate-pulse': activatingId === profile.id }" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                class="h-8 w-8"
                :disabled="savingId === profile.id"
                @click="testProfile(profile.id)"
              >
                <Plug class="w-4 h-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                class="h-8 w-8"
                @click="openEdit(profile)"
              >
                <Pencil class="w-4 h-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                class="h-8 w-8 text-destructive hover:text-destructive"
                :disabled="deletingId === profile.id"
                @click="deleteProfile(profile.id)"
              >
                <Trash2 class="w-4 h-4" />
              </Button>
            </div>
          </div>

          <div
            v-if="testResult?.id === profile.id"
            class="mt-3 text-sm flex items-center gap-1.5"
            :class="testResult.success ? 'text-green-500' : 'text-red-500'"
          >
            <CheckCircle v-if="testResult.success" class="w-4 h-4" />
            <Plug v-else class="w-4 h-4" />
            {{ testResult.message }}
          </div>
        </div>

        <div v-if="profiles.length === 0" class="text-center py-12 text-muted-foreground border border-dashed border-border rounded-2xl">
          暂无 AI 配置，点击右上角 Add Config 添加。
        </div>
      </div>
    </div>

    <!-- Create/Edit Modal -->
    <Dialog v-model:open="isModalOpen">
      <DialogContent class="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{{ isEditing ? 'Edit Configuration' : 'Add Configuration' }}</DialogTitle>
        </DialogHeader>

        <div class="space-y-4 py-2">
          <div>
            <label class="block text-sm font-medium mb-2">Name</label>
            <Input v-model="form.name" placeholder="DeepSeek Production" />
          </div>

          <div>
            <label class="block text-sm font-medium mb-2">API Key</label>
            <div class="relative">
              <Input
                v-model="form.apiKey"
                :type="showApiKey ? 'text' : 'password'"
                :placeholder="isEditing ? 'Leave blank to keep current key' : 'sk-...'"
                class="pr-10 font-mono"
              />
              <button
                type="button"
                @click="showApiKey = !showApiKey"
                class="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                <Eye v-if="!showApiKey" class="w-4 h-4" />
                <EyeOff v-else class="w-4 h-4" />
              </button>
            </div>
          </div>

          <div>
            <label class="block text-sm font-medium mb-2">API Endpoint</label>
            <Input v-model="form.endpoint" placeholder="https://api.openai.com/v1" />
          </div>

          <div>
            <label class="block text-sm font-medium mb-2">Model</label>
            <Input v-model="form.model" placeholder="gpt-4o-mini" />
          </div>
        </div>

        <div class="flex justify-end gap-2 mt-2">
          <Button variant="outline" @click="isModalOpen = false">Cancel</Button>
          <Button :disabled="isSubmitting" @click="saveProfile">
            <Loader2 v-if="isSubmitting" class="w-4 h-4 mr-1.5 animate-spin" />
            <Save v-else class="w-4 h-4 mr-1.5" />
            {{ isSubmitting ? 'Saving...' : 'Save' }}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  </div>
</template>
