import { getApiBaseUrl } from '../../api/apiBaseUrl'
export type BookmarkType = 'COMPLEX' | 'ANNOUNCEMENT'
export interface BookmarkItem {
  readonly type: BookmarkType
  readonly targetId: string
  readonly name: string
  readonly createdAt: string
  readonly available: boolean
  readonly address: string | null
  readonly status: string | null
  readonly applicationStartDate: string | null
  readonly applicationEndDate: string | null
}
export interface BookmarkRepository {
  list(signal?: AbortSignal): Promise<readonly BookmarkItem[]>
  save(type: BookmarkType, id: string): Promise<void>
  remove(type: BookmarkType, id: string): Promise<void>
}
export class BookmarkSessionExpired extends Error {
  constructor() { super('로그인이 필요해요. 다시 로그인해 주세요.') }
}
function parseItem(value: unknown): BookmarkItem {
  if (typeof value !== 'object' || value === null) throw new Error('북마크 응답이 올바르지 않습니다.')
  const item = value as Record<string, unknown>
  if ((item.type !== 'COMPLEX' && item.type !== 'ANNOUNCEMENT') || typeof item.targetId !== 'string'
    || !/^[1-9]\d{0,18}$/.test(item.targetId) || typeof item.name !== 'string'
    || typeof item.createdAt !== 'string' || !Number.isFinite(Date.parse(item.createdAt)) || typeof item.available !== 'boolean'
    || !['address', 'status', 'applicationStartDate', 'applicationEndDate'].every((key) => item[key] === null || typeof item[key] === 'string')
    || !['applicationStartDate', 'applicationEndDate'].every((key) => item[key] === null || /^\d{4}-\d{2}-\d{2}$/.test(String(item[key])) && Number.isFinite(Date.parse(String(item[key]))))) {
    throw new Error('북마크 응답이 올바르지 않습니다.')
  }
  return item as unknown as BookmarkItem
}
export function createBookmarkRepository(fetcher: typeof fetch = (...args) => globalThis.fetch(...args)): BookmarkRepository {
  const endpoint = `${getApiBaseUrl()}/api/v1/bookmarks/me`
  async function ensureSuccess(response: Response) {
    if (response.status === 401) throw new BookmarkSessionExpired()
    if (!response.ok) throw new Error('북마크를 처리하지 못했습니다. 다시 시도해 주세요.')
  }
  async function write(method: 'PUT' | 'DELETE', type: BookmarkType, id: string) {
    const csrfResponse = await fetcher(`${getApiBaseUrl()}/api/auth/csrf`, { credentials: 'include' })
    await ensureSuccess(csrfResponse)
    const csrf: unknown = await csrfResponse.json()
    if (typeof csrf !== 'object' || csrf === null || !('token' in csrf) || typeof csrf.token !== 'string' || !csrf.token
      || !('headerName' in csrf) || typeof csrf.headerName !== 'string' || !['X-XSRF-TOKEN', 'X-CSRF-TOKEN'].includes(csrf.headerName)) throw new Error('북마크 저장을 준비하지 못했습니다.')
    const response = await fetcher(`${endpoint}/${type}/${encodeURIComponent(id)}`, { method, credentials: 'include', headers: { [csrf.headerName]: csrf.token } })
    await ensureSuccess(response)
  }
  return {
    async list(signal) {
      const response = await fetcher(endpoint, { credentials: 'include', signal })
      await ensureSuccess(response)
      const body: unknown = await response.json()
      if (typeof body !== 'object' || body === null || !('items' in body) || !Array.isArray(body.items)) throw new Error('북마크 응답이 올바르지 않습니다.')
      return body.items.map(parseItem)
    },
    save: (type, id) => write('PUT', type, id), remove: (type, id) => write('DELETE', type, id),
  }
}
export const bookmarkRepository = createBookmarkRepository()
