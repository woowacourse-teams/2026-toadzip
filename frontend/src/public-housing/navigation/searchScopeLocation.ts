import type { MapBounds, SearchScope } from '../model/publicHousing.ts'

export type { SearchScope } from '../model/publicHousing.ts'

const REGION_KEYS = ['boundaryRegionCode', 'complexRegionCode', 'announcementRegionCode'] as const
const REGION_CODE = /^(?:\d{2}|\d{5})$/

export function parseSearchScope(query: URLSearchParams): SearchScope | null {
  const modes = query.getAll('searchMode')
  if (modes.length > 1) return null
  if (modes[0] === 'area') {
    const values = query.getAll('searchBounds')
    const bounds = values.length === 1 ? parseBounds(values[0]) : null
    return bounds === null ? null : { mode: 'area', bounds }
  }
  if (modes.length === 1 && modes[0] !== 'region') return null
  const keys = modes[0] === 'region'
    ? REGION_KEYS
    : ['complexRegionCode', 'announcementRegionCode', 'boundaryRegionCode']
  for (const key of keys) {
    const values = query.getAll(key)
    if (values.length === 1 && REGION_CODE.test(values[0])) {
      return { mode: 'region', regionCode: values[0] }
    }
  }
  return null
}

export function setSearchScopeQuery(query: URLSearchParams, scope: SearchScope): URLSearchParams {
  const next = new URLSearchParams(query)
  next.delete('searchMode')
  next.delete('searchBounds')
  REGION_KEYS.forEach((key) => next.delete(key))
  if (scope.mode === 'region') {
    if (!REGION_CODE.test(scope.regionCode)) throw new RangeError('지역 코드를 확인해 주세요.')
    next.set('searchMode', 'region')
    REGION_KEYS.forEach((key) => next.set(key, scope.regionCode))
  } else {
    const value = serializeBounds(scope.bounds)
    if (parseBounds(value) === null) throw new RangeError('지도 검색 범위를 확인해 주세요.')
    next.set('searchMode', 'area')
    next.set('searchBounds', value)
  }
  return next
}

export function searchScopeSignature(scope: SearchScope | null | undefined): string {
  if (scope == null) return ''
  return scope.mode === 'region'
    ? `region:${scope.regionCode}`
    : `area:${serializeBounds(scope.bounds)}`
}

function serializeBounds(bounds: MapBounds): string {
  return [bounds.southWestLat, bounds.southWestLng, bounds.northEastLat, bounds.northEastLng].join(',')
}

function parseBounds(value: string): MapBounds | null {
  const parts = value.split(',')
  if (parts.length !== 4 || parts.some((part) => !/^-?\d+(?:\.\d+)?$/.test(part))) return null
  const [southWestLat, southWestLng, northEastLat, northEastLng] = parts.map(Number)
  if (![southWestLat, southWestLng, northEastLat, northEastLng].every(Number.isFinite)
    || southWestLat < -90 || northEastLat > 90 || southWestLng < -180 || northEastLng > 180
    || southWestLat >= northEastLat || southWestLng >= northEastLng) return null
  return { southWestLat, southWestLng, northEastLat, northEastLng }
}
