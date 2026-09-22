function apiBaseUrl(): string {
  return import.meta.env.VITE_API_BASE_URL || (import.meta.env.DEV ? 'http://localhost:8080' : '')
}

export function socialLoginUrl(provider: 'kakao' | 'google'): string {
  return `${apiBaseUrl()}/api/auth/oauth2/authorization/${provider}`
}

export async function getCurrentUser(): Promise<{ id: number } | null> {
  const response = await fetch(`${apiBaseUrl()}/api/auth/me`, { credentials: 'include' })
  if (response.status === 401 || response.status === 403) return null
  if (!response.ok) throw new Error('로그인 상태를 확인하지 못했습니다.')
  const body: unknown = await response.json()
  if (typeof body !== 'object' || body === null || !('id' in body) || typeof body.id !== 'number') {
    throw new Error('로그인 응답이 올바르지 않습니다.')
  }
  return { id: body.id }
}

export async function logoutUser(): Promise<void> {
  const csrfResponse = await fetch(`${apiBaseUrl()}/api/auth/csrf`, { credentials: 'include' })
  if (!csrfResponse.ok) throw new Error('로그아웃을 준비하지 못했습니다.')
  const csrf: unknown = await csrfResponse.json()
  if (typeof csrf !== 'object' || csrf === null || !('token' in csrf) || typeof csrf.token !== 'string' || !('headerName' in csrf) || typeof csrf.headerName !== 'string') {
    throw new Error('로그아웃을 준비하지 못했습니다.')
  }
  const response = await fetch(`${apiBaseUrl()}/api/auth/logout`, {
    method: 'POST',
    credentials: 'include',
    headers: { [csrf.headerName]: csrf.token },
  })
  if (!response.ok) throw new Error('로그아웃하지 못했습니다.')
}
