import { getApiBaseUrl } from '../../api/apiBaseUrl'
const baseUrl = getApiBaseUrl()

async function csrf(path: string) {
  const response = await fetch(`${baseUrl}${path}`, { credentials: 'include' })
  if (!response.ok) throw new Error('요청을 준비하지 못했어요. 다시 시도해 주세요.')
  const token = await response.json() as { token: string; headerName: string }
  if (!token.token || !['X-CSRF-TOKEN', 'X-XSRF-TOKEN'].includes(token.headerName)) {
    throw new Error('요청을 준비하지 못했어요. 다시 시도해 주세요.')
  }
  return { [token.headerName]: token.token }
}

async function post(path: string, body?: object, admin = false) {
  const headers = await csrf(admin ? '/api/admin/auth/csrf' : '/api/auth/csrf')
  return fetch(`${baseUrl}${path}`, {
    method: 'POST', credentials: 'include',
    headers: { ...headers, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
}

export async function requestGuestCancellation(email: string) {
  const response = await post('/api/v1/notification-guest-cancellations', { email })
  if (!response.ok) throw new Error('취소 요청을 접수하지 못했어요. 다시 시도해 주세요.')
}

export async function verifyGuestCancellation(email: string, code: string) {
  const response = await post('/api/v1/notification-guest-cancellations/verify', { email, code })
  if (response.status === 400) throw new Error('이메일과 확인 코드를 다시 확인해 주세요.')
  if (!response.ok) throw new Error('취소를 완료하지 못했어요. 다시 시도해 주세요.')
}

export interface GuestCancellationRequest {
  readonly id: string
  readonly email: string
  readonly requestedAt: string
  readonly codeExpiresAt: string | null
  readonly failedAttempts: number
  readonly codeSentAt: string | null
  readonly codeSentBy: string | null
}

export async function loadGuestCancellationRequests(): Promise<GuestCancellationRequest[]> {
  const response = await fetch(`${baseUrl}/api/admin/notification-guest-cancellations`, { credentials: 'include' })
  if (!response.ok) throw new Error('취소 요청 목록을 불러오지 못했습니다.')
  return response.json() as Promise<GuestCancellationRequest[]>
}

export async function issueGuestCancellationCode(id: string) {
  const response = await post(`/api/admin/notification-guest-cancellations/${id}/code`, undefined, true)
  if (response.status === 409) throw new Error('이미 만든 코드가 있습니다. 목록을 새로고침한 뒤 필요하면 다시 만들어 주세요.')
  if (!response.ok) throw new Error('확인 코드를 만들지 못했습니다.')
  return response.json() as Promise<{ code: string; expiresAt: string }>
}

export async function markGuestCancellationCodeSent(id: string, code: string) {
  const response = await post(`/api/admin/notification-guest-cancellations/${id}/sent`, { code }, true)
  if (response.status === 409) throw new Error('코드가 만료됐거나 다시 발급됐습니다. 목록을 새로고침해 확인해 주세요.')
  if (!response.ok) throw new Error('발송 완료를 기록하지 못했습니다.')
}

export async function reissueGuestCancellationCode(id: string) {
  const response = await post(`/api/admin/notification-guest-cancellations/${id}/code/reissue`, undefined, true)
  if (!response.ok) throw new Error('코드를 다시 만들지 못했습니다. 목록을 새로고침해 주세요.')
  return response.json() as Promise<{ code: string; expiresAt: string }>
}
