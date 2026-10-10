import { getApiBaseUrl } from '../../api/apiBaseUrl'
export type SearchType = 'ANNOUNCEMENT' | 'COMPLEX' | 'REGION' | 'SUBWAY_STATION'

export interface SearchResultItem {
  readonly applicationStatus: string | null
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
  readonly subwayStations?: readonly SearchResultItem[]
  readonly announcements: readonly SearchResultItem[]
  readonly complexes: readonly SearchResultItem[]
  readonly failures: readonly SearchFailure[]
  readonly hasNext: boolean
  readonly page: number
  readonly query: string
  readonly regions: readonly SearchResultItem[]
  readonly size: number
  readonly totalCount: number | null
}

export interface IntegratedSearchRepository {
  search(
    query: string,
    preview: boolean,
    page: number,
    signal: AbortSignal,
    type?: SearchType,
  ): Promise<IntegratedSearchResponse>
}

export function createIntegratedSearchRepository(
  fetcher: typeof globalThis.fetch = globalThis.fetch,
): IntegratedSearchRepository {
  return {
    async search(query, preview, page, signal, type) {
      if (type === 'SUBWAY_STATION') {
        return searchLocations(fetcher, query, page, signal, type)
      }
      const params = new URLSearchParams({
        page: String(page),
        preview: String(preview),
        query,
        size: type ? '5' : '20',
      })
      if (type) {
        params.set('type', type)
      }
      const response = await fetcher(`${getApiBaseUrl()}/api/v1/search?${params}`, {
        headers: { Accept: 'application/json' },
        signal,
      })
      if (!response.ok) {
        throw new Error('통합 검색 결과를 불러오지 못했습니다.')
      }
      const result = decodeResponse((await response.json()) as unknown)
      if (type === 'REGION' && result.totalCount === 0 && result.failures.length === 0) {
        signal.throwIfAborted()
        return searchLocations(fetcher, query, page, signal, type)
      }
      return result
    },
  }
}

export const integratedSearchRepository = createIntegratedSearchRepository()

async function searchLocations(
  fetcher: typeof globalThis.fetch,
  query: string,
  page: number,
  signal: AbortSignal,
  type: 'REGION' | 'SUBWAY_STATION',
): Promise<IntegratedSearchResponse> {
  const params = new URLSearchParams({ query, page: String(page), size: '5', type })
  const response = await fetcher(`${getApiBaseUrl()}/api/v1/locations/search?${params}`, {
    headers: { Accept: 'application/json' }, signal,
  })
  if (!response.ok) {
    throw new Error('위치 검색을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.')
  }
  const envelope = record((await response.json()) as unknown, '$')
  const data = record(envelope.data, '$.data')
  const items = array(data.items, '$.data.items').map((value, index): SearchResultItem => {
    const item = record(value, `item[${index}]`)
    if (item.type !== type) throw new Error('위치 검색 결과 유형이 올바르지 않습니다.')
    const latitude = number(item.latitude, 'item.latitude')
    const longitude = number(item.longitude, 'item.longitude')
    if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
      throw new Error('위치 검색 좌표가 올바르지 않습니다.')
    }
    return {
      type, id: string(item.id, 'item.id'), title: string(item.title, 'item.title'),
      subtitle: string(item.subtitle, 'item.subtitle'), latitude, longitude,
      regionCode: null, applicationStatus: null, publishedAt: null,
    }
  })
  return {
    query, announcements: [], complexes: [], failures: [],
    regions: type === 'REGION' ? items : [], subwayStations: type === 'SUBWAY_STATION' ? items : [],
    page: number(data.page, '$.data.page'), size: number(data.size, '$.data.size'),
    hasNext: boolean(data.hasNext, '$.data.hasNext'),
    totalCount: data.totalCount == null ? null : number(data.totalCount, '$.data.totalCount'),
  }
}

function decodeResponse(value: unknown): IntegratedSearchResponse {
  const envelope = record(value, '$')
  const data = record(envelope.data, '$.data')
  return {
    announcements: array(data.announcements, '$.data.announcements').map(decodeItem),
    complexes: array(data.complexes, '$.data.complexes').map(decodeItem),
    failures: array(data.failures, '$.data.failures').map(decodeFailure),
    hasNext: boolean(data.hasNext, '$.data.hasNext'),
    page: number(data.page, '$.data.page'),
    query: string(data.query, '$.data.query'),
    regions: array(data.regions, '$.data.regions').map(decodeItem),
    size: number(data.size, '$.data.size'),
    totalCount: data.totalCount == null ? null : number(data.totalCount, '$.data.totalCount'),
  }
}

function decodeItem(value: unknown, index: number): SearchResultItem {
  const item = record(value, `item[${index}]`)
  const type = string(item.type, 'item.type')
  if (!isSearchType(type)) {
    throw new Error('통합 검색 결과 유형이 올바르지 않습니다.')
  }
  return {
    applicationStatus: nullableString(item.applicationStatus),
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
  return ['ANNOUNCEMENT', 'COMPLEX', 'REGION', 'SUBWAY_STATION'].includes(value)
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
