import { isOrganicSource, organicSourceFromCampaignCode, organicSourceFromReferrer } from './organicSource'

export const CAMPAIGN_STORAGE_KEY = 'toadzip.campaign-source.v1'
export const CAMPAIGN_LIFETIME = 30 * 60 * 1000
const KEYS = ['utm_source', 'utm_campaign', 'utm_content'] as const
const CODE = /^[a-z][a-z0-9_-]{0,63}$/
// organic_source는 주소에서 읽지 않는다. 리퍼러나 알려진 출처 코드를 분류한 고정 값만 들어간다.
export type CampaignSource = Partial<Record<typeof KEYS[number] | 'organic_source', string>>
export type CampaignEntry = { source: CampaignSource; expiresAt: number }
let memory: CampaignEntry | undefined
let storageUnavailable = false
let expiry: ReturnType<typeof setTimeout> | undefined

export function safeCampaignSource(input: Record<string, unknown>): CampaignSource {
  const source: CampaignSource = Object.fromEntries(KEYS.flatMap(key => {
    const value = input[key]
    return typeof value === 'string' && CODE.test(value) ? [[key, value]] : []
  }))
  if (isOrganicSource(input.organic_source)) source.organic_source = input.organic_source
  return source
}

function store(source: CampaignSource): void {
  memory = { source, expiresAt: Date.now() + CAMPAIGN_LIFETIME }
  try { sessionStorage.setItem(CAMPAIGN_STORAGE_KEY, JSON.stringify(memory)) } catch { storageUnavailable = true }
  campaignEntry()
}

function clear(): void {
  memory = undefined
  clearTimeout(expiry)
  try { sessionStorage.removeItem(CAMPAIGN_STORAGE_KEY) } catch { /* 메모리에서도 폐기한다. */ }
}

export function campaignEntry(): CampaignEntry | undefined {
  let candidate: unknown = memory
  if (!storageUnavailable) {
    let value: string | null = null
    try { value = sessionStorage.getItem(CAMPAIGN_STORAGE_KEY) } catch { storageUnavailable = true }
    if (!storageUnavailable) {
      try { candidate = JSON.parse(value ?? 'null') } catch { clear(); return undefined }
    }
  }
  if (!candidate || typeof candidate !== 'object' || !('expiresAt' in candidate)
    || typeof candidate.expiresAt !== 'number' || !Number.isFinite(candidate.expiresAt)
    || candidate.expiresAt <= Date.now() || candidate.expiresAt > Date.now() + CAMPAIGN_LIFETIME
    || !('source' in candidate) || !candidate.source || typeof candidate.source !== 'object') {
    clear()
    return undefined
  }
  const source = safeCampaignSource(candidate.source as Record<string, unknown>)
  if (Object.keys(source).length === 0) { clear(); return undefined }
  memory = { source, expiresAt: candidate.expiresAt }
  clearTimeout(expiry)
  expiry = setTimeout(() => { campaignEntry() }, Math.max(0, memory.expiresAt - Date.now()))
  return { source: { ...source }, expiresAt: memory.expiresAt }
}

/** 라우터 상태와 다른 검색 조건을 보존하고 외부 링크의 세 코드만 주소에서 지운다. */
export function consumeCampaignEntry(): boolean {
  campaignEntry()
  const url = new URL(window.location.href)
  if (!KEYS.some(key => url.searchParams.has(key))) return false
  const codes = url.searchParams.getAll('utm_source')
  const organic = codes.length === 1 ? organicSourceFromCampaignCode(codes[0]) : undefined
  const source = organic ? { organic_source: organic } : safeCampaignSource(Object.fromEntries(KEYS.map(key =>
    [key, url.searchParams.getAll(key).length === 1 ? url.searchParams.get(key) : null])))
  for (const key of KEYS) url.searchParams.delete(key)
  try { history.replaceState(history.state, '', url.pathname + url.search + url.hash) } catch { return false }
  clear()
  if (Object.keys(source).length === 0) return false
  store(source)
  return true
}

/** 검색·AI 서비스에서 처음 열린 페이지의 리퍼러를 분류값으로 저장한다. 저장된 출처가 있으면 바꾸지 않는다. */
export function consumeOrganicEntry(): boolean {
  if (campaignEntry()) return false
  // 새로고침·뒤로가기에도 document.referrer는 남으므로 30분 뒤 같은 방문을 다시 세지 않게 한다.
  const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined
  if (navigation && navigation.type !== 'navigate') return false
  const source = organicSourceFromReferrer(document.referrer)
  if (!source) return false
  store({ organic_source: source })
  return true
}

export function hasCampaignParameters(search: string): boolean {
  const parameters = new URLSearchParams(search)
  return KEYS.some(key => parameters.has(key))
}
