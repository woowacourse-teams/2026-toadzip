import { RENTAL_TYPE_OPTIONS } from '../filters/searchFilterOptions.ts'

// 지역·공급 유형 목록 페이지(`/regions/28200/national-rental`)의 주소. 사람에게는 주소를 유지한 채
// 그 지역을 고르고(boundaryRegionCode) 공급 유형을 거른(complexRentalTypes) 지도 화면을 연다.
const REGION_PATH = /^\/regions\/([^/]+)(?:\/([^/]+))?$/
const REGION_CODE = /^(?:\d{2}|\d{5})$/
export const REGION_CODE_QUERY_KEY = 'boundaryRegionCode'
export const RENTAL_TYPES_QUERY_KEY = 'complexRentalTypes'

export type RegionPathResult =
  | { readonly kind: 'none' }
  | { readonly kind: 'invalid' }
  | { readonly kind: 'region'; readonly regionCode: string; readonly rentalType: string | null }

export function rentalTypeSlug(rentalType: string): string {
  return rentalType.toLowerCase().replaceAll('_', '-')
}

export function regionPath(regionCode: string, rentalType: string | null): string {
  return rentalType === null ? `/regions/${regionCode}` : `/regions/${regionCode}/${rentalTypeSlug(rentalType)}`
}

export function parseRegionPath(pathname: string): RegionPathResult {
  const match = REGION_PATH.exec(pathname)
  if (!match) return { kind: 'none' }
  const [, regionCode, slug] = match
  if (regionCode === undefined || !REGION_CODE.test(regionCode)) return { kind: 'invalid' }
  if (slug === undefined) return { kind: 'region', regionCode, rentalType: null }
  const rentalType = RENTAL_TYPE_OPTIONS.find(([value]) => rentalTypeSlug(value) === slug)?.[0]
  return rentalType === undefined ? { kind: 'invalid' } : { kind: 'region', regionCode, rentalType }
}

/** 지도 화면 쿼리가 이 지역 페이지와 같은 조건인지. 같을 때만 지역 주소를 유지한다. */
export function matchesRegionPath(search: URLSearchParams, region: { regionCode: string; rentalType: string | null }) {
  const codes = search.getAll(REGION_CODE_QUERY_KEY)
  const rentalTypes = search.getAll(RENTAL_TYPES_QUERY_KEY)
  const expectedTypes = region.rentalType === null ? [] : [region.rentalType]
  return codes.length === 1 && codes[0] === region.regionCode
    && rentalTypes.length === expectedTypes.length && rentalTypes.every((value, index) => value === expectedTypes[index])
}
