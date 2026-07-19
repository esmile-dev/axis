/**
 * 返回指向 Spring Boot 后端的 $fetch 实例（baseURL = runtimeConfig.public.apiBase）。
 * 所有 /api/** 调用统一走这里，替代原先同源的 Nitro $fetch。
 */
export function useApi() {
  const { public: { apiBase } } = useRuntimeConfig()
  return $fetch.create({ baseURL: apiBase })
}
