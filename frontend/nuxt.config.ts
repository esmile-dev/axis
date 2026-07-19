// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: true },
  modules: ['@nuxtjs/tailwindcss', '@pinia/nuxt', '@vueuse/nuxt'],
  devServer: {
    port: 7788
  },
  runtimeConfig: {
    public: {
      apiBase: process.env.AXIS_API_BASE || 'http://localhost:7789'
    }
  },
  components: {
    dirs: [
      {
        path: '~/components',
        extensions: ['.vue'],  // 只扫描 .vue 文件，排除 index.ts 命名冲突
      }
    ]
  }
})