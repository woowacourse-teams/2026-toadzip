import { RENTAL_TYPE_OPTIONS } from '../filters/searchFilterOptions.ts'

// 지역·공급 유형 목록 페이지(`/regions/28200/national-rental`)의 주소. 사람에게는 같은 조건의 지도 화면을 연다.
const REGION_CODE = /^(?:\d{2}|\d{5})$/

export function rentalTypeSlug(rentalType: string): string {
  return rentalType.toLowerCase().replaceAll('_', '-')
}

/** 지역 페이지 주소를 지도 화면 필터 쿼리로 바꾼다. 알 수 없는 지역·유형이면 null. */
export function regionExplorerSearch(regionCode: string | undefined, slug: string | undefined): string | null {
  if (regionCode === undefined || !REGION_CODE.test(regionCode)) return null
  const query = new URLSearchParams({ complexRegionCode: regionCode })
  if (slug !== undefined) {
    const rentalType = RENTAL_TYPE_OPTIONS.find(([value]) => rentalTypeSlug(value) === slug)?.[0]
    if (rentalType === undefined) return null
    query.set('complexRentalTypes', rentalType)
  }
  return `?${query}`
}
