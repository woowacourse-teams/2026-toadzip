export type SearchType = 'ANNOUNCEMENT' | 'COMPLEX' | 'REGION'

export interface SearchResultItem {
  readonly address: string | null
  readonly applicationStatus: string | null
  readonly cancelled: boolean
  readonly category: string | null
  readonly id: string
  readonly latitude: number | null
  readonly longitude: number | null
  readonly publishedAt: string | null
  readonly regionCode: string | null
  readonly subtitle: string | null
  readonly title: string
  readonly type: SearchType
}

export interface SearchFailure {
  readonly message: string
  readonly type: SearchType
}

export interface IntegratedSearchResponse {
  readonly failures: readonly SearchFailure[]
  readonly hasNext: boolean
  readonly housingInformation: readonly SearchResultItem[]
  readonly locations: readonly SearchResultItem[]
  readonly page: number
  readonly query: string
  readonly size: number
}

export interface IntegratedSearchRepository {
  search(
    query: string,
    preview: boolean,
    page: number,
    signal: AbortSignal,
  ): Promise<IntegratedSearchResponse>
}

export function createIntegratedSearchRepository(
  fetcher: typeof globalThis.fetch = globalThis.fetch,
): IntegratedSearchRepository {
  return {
    async search(query, preview, page, signal) {
      const params = new URLSearchParams({
        page: String(page),
        preview: String(preview),
        query,
        size: '20',
      })
      const response = await fetcher(`${apiBaseUrl()}/api/v1/search?${params}`, {
        headers: { Accept: 'application/json' },
        signal,
      })
      if (!response.ok) {
        throw new Error('통합 검색 결과를 불러오지 못했습니다.')
      }
      return decodeResponse((await response.json()) as unknown)
    },
  }
}

export const integratedSearchRepository = createIntegratedSearchRepository()

function decodeResponse(value: unknown): IntegratedSearchResponse {
  const envelope = record(value, '$')
  const data = record(envelope.data, '$.data')
  return {
    failures: array(data.failures, '$.data.failures').map(decodeFailure),
    hasNext: boolean(data.hasNext, '$.data.hasNext'),
    housingInformation: array(
      data.housingInformation,
      '$.data.housingInformation',
    ).map(decodeItem),
    locations: array(data.locations, '$.data.locations').map(decodeItem),
    page: number(data.page, '$.data.page'),
    query: string(data.query, '$.data.query'),
    size: number(data.size, '$.data.size'),
  }
}

function decodeItem(value: unknown, index: number): SearchResultItem {
  const item = record(value, `item[${index}]`)
  const type = string(item.type, 'item.type')
  if (!isSearchType(type)) {
    throw new Error('통합 검색 결과 유형이 올바르지 않습니다.')
  }
  return {
    address: nullableString(item.address),
    applicationStatus: nullableString(item.applicationStatus),
    cancelled: boolean(item.cancelled, 'item.cancelled'),
    category: nullableString(item.category),
    id: string(item.id, 'item.id'),
    latitude: nullableNumber(item.latitude),
    longitude: nullableNumber(item.longitude),
    publishedAt: nullableString(item.publishedAt),
    regionCode: nullableString(item.regionCode),
    subtitle: nullableString(item.subtitle),
    title: string(item.title, 'item.title'),
    type,
  }
}

function decodeFailure(value: unknown, index: number): SearchFailure {
  const failure = record(value, `failure[${index}]`)
  const type = string(failure.type, 'failure.type')
  if (!isSearchType(type)) {
    throw new Error('통합 검색 실패 유형이 올바르지 않습니다.')
  }
  return { message: string(failure.message, 'failure.message'), type }
}

function isSearchType(value: string): value is SearchType {
  return ['ANNOUNCEMENT', 'COMPLEX', 'REGION'].includes(value)
}

function record(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`${path} 응답 형식이 올바르지 않습니다.`)
  }
  return value as Record<string, unknown>
}

function array(value: unknown, path: string): readonly unknown[] {
  if (!Array.isArray(value)) {
    throw new Error(`${path} 응답 형식이 올바르지 않습니다.`)
  }
  return value
}

function string(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw new Error(`${path} 응답 형식이 올바르지 않습니다.`)
  }
  return value
}

function number(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`${path} 응답 형식이 올바르지 않습니다.`)
  }
  return value
}

function boolean(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') {
    throw new Error(`${path} 응답 형식이 올바르지 않습니다.`)
  }
  return value
}

function nullableString(value: unknown): string | null {
  return value === null ? null : string(value, 'nullable string')
}

function nullableNumber(value: unknown): number | null {
  return value === null ? null : number(value, 'nullable number')
}

function apiBaseUrl() {
  if (import.meta.env.VITE_API_BASE_URL) {
    return import.meta.env.VITE_API_BASE_URL
  }
  return import.meta.env.DEV ? 'http://localhost:8080' : ''
}
