import { resolveApiBaseUrl } from '../../api/apiBaseUrl'
import {
  isRecord,
  parseManagementDetail,
  parseManagementHistory,
  parseManagementPage,
  type ManagementChange,
  type ManagementDetailData,
  type ManagementPage,
  type ManagementResource,
} from './managementContract'

const apiBaseUrl = resolveApiBaseUrl(import.meta.env.VITE_API_BASE_URL, import.meta.env.DEV)

export function managementResourcePath(resource: ManagementResource): string {
  return `/api/admin/${resource === 'complexes' ? 'housing-complexes' : 'announcements'}`
}

export class ManagementError extends Error {
  readonly status: number
  readonly fields: Record<string, string>

  constructor(message: string, status: number, fields: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.fields = fields
  }
}

export async function requestManagementApi(
  path: string,
  method = 'GET',
  body?: unknown,
  signal?: AbortSignal,
): Promise<unknown> {
  const headers: Record<string, string> = {}
  if (method !== 'GET') {
    const response = await fetch(`${apiBaseUrl}/api/admin/auth/csrf`, { credentials: 'include', signal })
    if (!response.ok) {
      throw new ManagementError('로그인 상태를 확인해 주세요.', response.status)
    }
    const csrf: unknown = await response.json()
    if (!isRecord(csrf) || typeof csrf.headerName !== 'string' || typeof csrf.token !== 'string') {
      throw new Error('인증 응답이 올바르지 않습니다.')
    }
    headers[csrf.headerName] = csrf.token
    if (body !== undefined) headers['Content-Type'] = 'application/json'
  }

  const response = await fetch(`${apiBaseUrl}${path}`, {
    method, credentials: 'include', headers, signal,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (response.status === 204) return null

  const result: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    const fields: Record<string, string> = {}
    if (isRecord(result) && Array.isArray(result.errors)) {
      for (const error of result.errors) {
        if (isRecord(error) && typeof error.field === 'string' && typeof error.reason === 'string') {
          fields[error.field] = error.reason
        }
      }
    }
    const message = isRecord(result) && typeof result.message === 'string'
      ? result.message
      : '요청을 처리하지 못했습니다. 다시 시도해 주세요.'
    throw new ManagementError(message, response.status, fields)
  }
  return isRecord(result) && 'data' in result ? result.data : result
}

export async function getManagementPage(
  resource: ManagementResource,
  params: URLSearchParams,
  signal?: AbortSignal,
): Promise<ManagementPage> {
  const value = await requestManagementApi(`${managementResourcePath(resource)}?${params}`, 'GET', undefined, signal)
  return parseManagementPage(value)
}

export async function getManagementDetail(
  resource: ManagementResource,
  id: string,
  signal?: AbortSignal,
): Promise<ManagementDetailData> {
  const value = await requestManagementApi(`${managementResourcePath(resource)}/${encodeURIComponent(id)}`, 'GET', undefined, signal)
  return parseManagementDetail(value)
}

export async function getManagementHistory(
  resource: ManagementResource,
  id: string,
  page: number,
): Promise<ManagementChange[]> {
  const value = await requestManagementApi(`${managementResourcePath(resource)}/${encodeURIComponent(id)}/changes?page=${page}`)
  return parseManagementHistory(value)
}
