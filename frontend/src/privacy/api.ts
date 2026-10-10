import { getApiBaseUrl } from '../api/apiBaseUrl'

export type Decision = 'UNSET' | 'GRANTED' | 'DENIED' | 'WITHDRAWN'
export type EffectiveStatus = Decision | 'EXPIRED' | 'RECONSENT_REQUIRED'
export interface ConsentContext {
  subject: { kind: 'MEMBER' | 'GUEST'; userId?: string; contextId: string | null }
  consent: { decision: Decision; effectiveStatus: EffectiveStatus; revision: number; noticeVersion: string | null; scopeVersion: string | null; decidedAt: string | null; expiresAt: string | null }
  requiredNoticeVersion: string
  requiredScopeVersion: string
  collectionAllowed: boolean
  checkedAt: string
  maxAgeSeconds: number
}
export interface Choice {
  commandId: string
  expectedUserId?: string
  contextId?: string
  expectedRevision: number
  action: 'GRANT' | 'DENY' | 'WITHDRAW'
  noticeVersion?: string
  scopeVersion?: string
  source: 'FIRST_VISIT' | 'SETTINGS'
}
export interface Notice {
  key: string; version: string; scopeVersion: string | null; effectiveAt: string; contentHash: string; documentUrl: string
}
export interface NoticeDocument extends Notice { format: 'markdown'; content: string }
export class PrivacyError extends Error {
  readonly status: number
  readonly code: string
  constructor(status: number, code: string, message = '선택을 저장하지 못했어요. 다시 시도해 주세요.') { super(message); this.status = status; this.code = code }
}
export function record(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value) }
const nullableString = (value: unknown) => value === null || typeof value === 'string'
export function parseConsent(value: unknown): ConsentContext {
  if (!record(value) || !record(value.subject) || !record(value.consent)) throw new Error('분석 설정 응답이 올바르지 않습니다.')
  const { subject, consent } = value
  if (!['MEMBER', 'GUEST'].includes(String(subject.kind)) || !nullableString(subject.contextId)
    || (subject.kind === 'MEMBER' && (typeof subject.userId !== 'string' || !/^\d+$/.test(subject.userId)))
    || !['UNSET', 'GRANTED', 'DENIED', 'WITHDRAWN'].includes(String(consent.decision))
    || !['UNSET', 'GRANTED', 'DENIED', 'WITHDRAWN', 'EXPIRED', 'RECONSENT_REQUIRED'].includes(String(consent.effectiveStatus))
    || !Number.isSafeInteger(consent.revision) || Number(consent.revision) < 0
    || !['noticeVersion', 'scopeVersion', 'decidedAt', 'expiresAt'].every(key => nullableString(consent[key]))
    || typeof value.requiredNoticeVersion !== 'string' || typeof value.requiredScopeVersion !== 'string'
    || typeof value.collectionAllowed !== 'boolean' || typeof value.checkedAt !== 'string' || !Number.isFinite(Date.parse(value.checkedAt))
    || !Number.isSafeInteger(value.maxAgeSeconds) || Number(value.maxAgeSeconds) < 0 || Number(value.maxAgeSeconds) > 60) throw new Error('분석 설정 응답이 올바르지 않습니다.')
  return value as unknown as ConsentContext
}
function parseNotice(value: unknown): Notice {
  if (!record(value) || !['key', 'version', 'effectiveAt', 'contentHash', 'documentUrl'].every(key => typeof value[key] === 'string') || !nullableString(value.scopeVersion)) throw new Error('안내문을 확인하지 못했습니다.')
  return value as unknown as Notice
}
async function responseBody(response: Response): Promise<unknown> {
  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null)
    throw new PrivacyError(response.status, record(body) && typeof body.code === 'string' ? body.code : 'PRIVACY_REQUEST_FAILED')
  }
  return response.json()
}
export function createPrivacyApi(fetcher: typeof fetch = (...args) => fetch(...args)) {
  const request = (url: string, options: RequestInit = {}) => fetcher(url, { ...options, signal: AbortSignal.timeout(15_000) })
  const base = () => `${getApiBaseUrl()}/api/v1/privacy`
  async function post(path: string, body?: unknown) {
    for (let attempt = 0; attempt < 2; attempt++) {
      const csrf = await responseBody(await request(`${getApiBaseUrl()}/api/auth/csrf`, { credentials: 'include', cache: 'no-store' }))
      if (!record(csrf) || csrf.headerName !== 'X-XSRF-TOKEN' || typeof csrf.token !== 'string') throw new Error('저장을 준비하지 못했습니다.')
      const response = await request(`${base()}${path}`, { method: 'POST', credentials: 'include', cache: 'no-store', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, ...(body ? { body: JSON.stringify(body) } : {}) })
      if (response.status === 403 && attempt === 0) continue
      return responseBody(response)
    }
    throw new Error('저장을 준비하지 못했습니다.')
  }
  return {
    async context() { return parseConsent(await responseBody(await request(`${base()}/analytics-context`, { credentials: 'include', cache: 'no-store' }))) },
    async prepareGuest() { return parseConsent(await post('/analytics/guest-context')) },
    async choose(kind: 'MEMBER' | 'GUEST', choice: Choice) {
      const result = await post(`/analytics/${kind === 'MEMBER' ? 'me' : 'guest'}`, choice)
      if (!record(result) || !record(result.receipt) || result.receipt.commandId !== choice.commandId) throw new Error('저장 결과를 확인하지 못했습니다.')
      return parseConsent(result.current)
    },
    async notices() {
      const body = await responseBody(await request(`${base()}/notices/current`, { credentials: 'include', cache: 'no-store' }))
      if (!record(body) || !Array.isArray(body.documents)) throw new Error('안내문을 확인하지 못했습니다.')
      return body.documents.map(parseNotice)
    },
    async document(key: string, version: string): Promise<NoticeDocument> {
      const body = await responseBody(await request(`${base()}/notices/${encodeURIComponent(key)}/${encodeURIComponent(version)}`, { cache: 'no-store' }))
      parseNotice(body)
      if (!record(body) || body.format !== 'markdown' || typeof body.content !== 'string' || body.key !== key || body.version !== version) throw new Error('안내문을 확인하지 못했습니다.')
      return body as unknown as NoticeDocument
    },
  }
}
export const privacyApi = createPrivacyApi()
