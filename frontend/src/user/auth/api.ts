import { consentStore } from '../../privacy/consentStore'
import { getApiBaseUrl } from '../../api/apiBaseUrl'

export function socialLoginUrl(provider: 'kakao' | 'google', policyVersion?: string): string {
  return `${getApiBaseUrl()}/api/auth/oauth2/authorization/${provider}${policyVersion ? `?policyVersion=${encodeURIComponent(policyVersion)}` : ''}`
}

export async function getCurrentUser(): Promise<{ id: number; email: string | null } | null> {
  const response = await fetch(`${getApiBaseUrl()}/api/auth/me`, { credentials: 'include' })
  if (response.status === 401) return null
  if (!response.ok) throw new Error('로그인 상태를 확인하지 못했습니다.')
  const body: unknown = await response.json()
  if (typeof body !== 'object' || body === null || !('id' in body) || typeof body.id !== 'number') {
    throw new Error('로그인 응답이 올바르지 않습니다.')
  }
  if ('email' in body && body.email !== null && typeof body.email !== 'string') {
    throw new Error('로그인 응답이 올바르지 않습니다.')
  }
  return { id: body.id, email: 'email' in body ? body.email as string | null : null }
}

export async function logoutUser(): Promise<void> {
  const transition = consentStore.beginAuthTransition('logout')
  try {
    const csrfResponse = await fetch(`${getApiBaseUrl()}/api/auth/csrf`, { credentials: 'include', signal: AbortSignal.timeout(15_000) })
    if (!csrfResponse.ok) throw new Error('로그아웃을 준비하지 못했습니다.')
    const csrf: unknown = await csrfResponse.json()
    if (typeof csrf !== 'object' || csrf === null || !('token' in csrf) || typeof csrf.token !== 'string' || !('headerName' in csrf) || typeof csrf.headerName !== 'string') {
      throw new Error('로그아웃을 준비하지 못했습니다.')
    }
    const response = await fetch(`${getApiBaseUrl()}/api/auth/logout`, {
      method: 'POST',
      credentials: 'include',
      headers: { [csrf.headerName]: csrf.token },
      signal: AbortSignal.timeout(15_000),
    })
    if (!response.ok) throw new Error('로그아웃하지 못했습니다.')
  } finally {
    await consentStore.finishAuthTransition(transition)
  }
}
