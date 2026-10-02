export function resolveApiBaseUrl(configuredBaseUrl: string | undefined, isDevelopment: boolean): string {
  return configuredBaseUrl || (isDevelopment ? 'http://localhost:8080' : '')
}
