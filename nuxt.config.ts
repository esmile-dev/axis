// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: true },
  modules: ['@nuxtjs/tailwindcss', '@pinia/nuxt', '@vueuse/nuxt'],
  devServer: {
    port: 7788
  },
  runtimeConfig: {
    databaseUrl: process.env.DATABASE_URL
  }
})