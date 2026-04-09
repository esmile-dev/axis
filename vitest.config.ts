import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    // 测试文件匹配模式
    include: ['tests/**/*.test.ts'],
    // 并行运行
    pool: 'threads',
    // 测试超时时间
    testTimeout: 30000,
    // 设置环境变量
    env: {
      DATABASE_URL: 'postgresql://alan@localhost:5432/postgres?schema=axis',
      NUXT_PUBLIC_BASE_URL: 'http://localhost:3000'
    }
  }
})