import { getApiBaseUrl } from '../api/apiBaseUrl'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export async function submitFeedback(content: string): Promise<void> {
  try {
    await sendFeedback(content)
  } catch (cause) {
    if (cause instanceof TypeError) throw new Error('서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    throw cause
  }
}

async function sendFeedback(content: string): Promise<void> {
  const base = getApiBaseUrl()
  const csrfResponse = await fetch(`${base}/api/auth/csrf`, { credentials: 'include' })
  if (!csrfResponse.ok) throw new Error('의견 전송을 준비하지 못했습니다. 다시 시도해 주세요.')
  const csrf: unknown = await csrfResponse.json().catch(() => null)
  if (!isRecord(csrf) || typeof csrf.headerName !== 'string' || typeof csrf.token !== 'string') {
    throw new Error('의견 전송을 준비하지 못했습니다. 다시 시도해 주세요.')
  }
  const response = await fetch(`${base}/api/v1/feedback`, {
    method: 'POST', credentials: 'include',
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify({ content }),
  })
  const body: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    if (isRecord(body) && Array.isArray(body.errors)) {
      const field = body.errors.find(error => isRecord(error) && error.field === 'content')
      if (isRecord(field) && typeof field.reason === 'string') throw new Error(field.reason)
    }
    throw new Error('의견을 보내지 못했습니다. 작성한 내용을 확인하고 다시 시도해 주세요.')
  }
  if (!isRecord(body) || !isRecord(body.data) || typeof body.data.id !== 'number'
    || !Number.isSafeInteger(body.data.id) || body.data.id <= 0) {
    throw new Error('접수 결과를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.')
  }
}
