export type AdminSession = {
  loginIdentifier: string
  role: 'ADMIN'
}

type CsrfToken = {
  token: string
  headerName: string
}

type ApiErrorBody = {
  message?: string
}

const apiBaseUrl = resolveApiBaseUrl()

export class AdminApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
    this.name = 'AdminApiError'
  }
}

export async function getCurrentAdmin(): Promise<AdminSession> {
  return request<AdminSession>('/api/admin/auth/me')
}

export async function loginAdmin(
  loginIdentifier: string,
  password: string,
): Promise<AdminSession> {
  const csrfToken = await requestCsrfToken()
  return request<AdminSession>('/api/admin/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      [csrfToken.headerName]: csrfToken.token,
    },
    body: JSON.stringify({ loginIdentifier, password }),
  })
}

export async function logoutAdmin(): Promise<void> {
  const csrfToken = await requestCsrfToken()
  await request<void>('/api/admin/auth/logout', {
    method: 'POST',
    headers: {
      [csrfToken.headerName]: csrfToken.token,
    },
  })
}

async function requestCsrfToken(): Promise<CsrfToken> {
  return request<CsrfToken>('/api/admin/auth/csrf')
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...init,
    credentials: 'include',
  })

  if (!response.ok) {
    throw new AdminApiError(response.status, await errorMessage(response))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

async function errorMessage(response: Response): Promise<string> {
  const body = (await response.json().catch(() => null)) as ApiErrorBody | null
  if (body?.message) {
    return body.message
  }
  return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

function resolveApiBaseUrl(): string {
  const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL
  if (configuredApiBaseUrl) {
    return configuredApiBaseUrl
  }
  if (import.meta.env.DEV) {
    return 'http://localhost:8080'
  }
  throw new Error('VITE_API_BASE_URL must be configured outside development.')
}
