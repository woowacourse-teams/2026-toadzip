export function getApiBaseUrl(): string {
  return import.meta.env.VITE_API_BASE_URL || (import.meta.env.DEV ? 'http://localhost:8080' : '')
}
