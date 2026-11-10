import { matchesRegionPath, parseRegionPath, regionPath, REGION_CODE_QUERY_KEY, RENTAL_TYPES_QUERY_KEY } from './regionPath.ts'

const COMPLEX_ID_QUERY_KEY = 'complexId'
const ANNOUNCEMENT_ID_QUERY_KEY = 'announcementId'
const MAXIMUM_JAVA_LONG = '9223372036854775807'
const CANONICAL_POSITIVE_INTEGER = /^[1-9][0-9]*$/

export type ComplexIdQueryResult =
  | { readonly kind: 'absent'; readonly complexId: null }
  | { readonly kind: 'invalid'; readonly complexId: null }
  | { readonly kind: 'valid'; readonly complexId: string }

export type AnnouncementIdQueryResult =
  | { readonly kind: 'absent'; readonly announcementId: null }
  | { readonly kind: 'invalid'; readonly announcementId: null }
  | { readonly kind: 'valid'; readonly announcementId: string }

export type DetailLocationResult =
  | { readonly kind: 'none' }
  | { readonly kind: 'invalid' }
  | { readonly kind: 'complex'; readonly complexId: string }
  | { readonly kind: 'announcement'; readonly announcementId: string }

function isCanonicalPositiveJavaLong(value: string): boolean {
  if (!CANONICAL_POSITIVE_INTEGER.test(value)) {
    return false
  }

  if (value.length !== MAXIMUM_JAVA_LONG.length) {
    return value.length < MAXIMUM_JAVA_LONG.length
  }

  return value <= MAXIMUM_JAVA_LONG
}

export function parseComplexIdQuery(
  searchParams: URLSearchParams,
): ComplexIdQueryResult {
  const values = searchParams.getAll(COMPLEX_ID_QUERY_KEY)
  if (values.length === 0) {
    return { kind: 'absent', complexId: null }
  }

  const complexId = values[0]
  if (
    values.length !== 1 ||
    complexId === undefined ||
    !isCanonicalPositiveJavaLong(complexId)
  ) {
    return { kind: 'invalid', complexId: null }
  }

  return { kind: 'valid', complexId }
}

export function parseAnnouncementIdQuery(
  searchParams: URLSearchParams,
): AnnouncementIdQueryResult {
  const values = searchParams.getAll(ANNOUNCEMENT_ID_QUERY_KEY)
  if (values.length === 0) {
    return { kind: 'absent', announcementId: null }
  }

  const announcementId = values[0]
  if (
    values.length !== 1 ||
    announcementId === undefined ||
    !isCanonicalPositiveJavaLong(announcementId)
  ) {
    return { kind: 'invalid', announcementId: null }
  }

  return { kind: 'valid', announcementId }
}

export function parseDetailLocation(
  searchParams: URLSearchParams,
): DetailLocationResult {
  const complex = parseComplexIdQuery(searchParams)
  const announcement = parseAnnouncementIdQuery(searchParams)
  if (complex.kind === 'invalid' || announcement.kind === 'invalid') {
    return { kind: 'invalid' }
  }
  if (complex.kind === 'valid' && announcement.kind === 'valid') {
    return { kind: 'invalid' }
  }
  if (complex.kind === 'valid') {
    return { kind: 'complex', complexId: complex.complexId }
  }
  if (announcement.kind === 'valid') {
    return { kind: 'announcement', announcementId: announcement.announcementId }
  }
  return { kind: 'none' }
}

export function setComplexIdQuery(
  searchParams: URLSearchParams,
  complexId: string,
): URLSearchParams {
  if (!isCanonicalPositiveJavaLong(complexId)) {
    throw new TypeError('정규 positive Java Long ID가 아닙니다.')
  }

  const nextSearchParams = new URLSearchParams(searchParams)
  nextSearchParams.delete(ANNOUNCEMENT_ID_QUERY_KEY)
  nextSearchParams.set(COMPLEX_ID_QUERY_KEY, complexId)
  return nextSearchParams
}

export function setAnnouncementIdQuery(
  searchParams: URLSearchParams,
  announcementId: string,
): URLSearchParams {
  if (!isCanonicalPositiveJavaLong(announcementId)) {
    throw new TypeError('정규 positive Java Long ID가 아닙니다.')
  }

  const nextSearchParams = new URLSearchParams(searchParams)
  nextSearchParams.delete(COMPLEX_ID_QUERY_KEY)
  nextSearchParams.set(ANNOUNCEMENT_ID_QUERY_KEY, announcementId)
  return nextSearchParams
}

export function clearComplexIdQuery(
  searchParams: URLSearchParams,
): URLSearchParams {
  const nextSearchParams = new URLSearchParams(searchParams)
  nextSearchParams.delete(COMPLEX_ID_QUERY_KEY)
  return nextSearchParams
}

export function clearAnnouncementIdQuery(
  searchParams: URLSearchParams,
): URLSearchParams {
  const nextSearchParams = new URLSearchParams(searchParams)
  nextSearchParams.delete(ANNOUNCEMENT_ID_QUERY_KEY)
  return nextSearchParams
}

export function clearDetailQuery(
  searchParams: URLSearchParams,
): URLSearchParams {
  const nextSearchParams = clearComplexIdQuery(searchParams)
  nextSearchParams.delete(ANNOUNCEMENT_ID_QUERY_KEY)
  return nextSearchParams
}

// 단지·공고 상세와 지역 목록의 고유 주소. 앱 안의 지도 화면은 쿼리(complexId/announcementId,
// boundaryRegionCode/complexRentalTypes)로 다루고, 주소창과 공유 링크에만 경로 주소를 쓴다.
export const EXPLORER_PATH = '/explore'
const DETAIL_PATH = /^\/(complexes|announcements)\/([^/]+)$/

export function complexPath(complexId: string): string {
  return `/complexes/${complexId}`
}

export function announcementPath(announcementId: string): string {
  return `/announcements/${announcementId}`
}

export function parseDetailPath(pathname: string): DetailLocationResult {
  const match = DETAIL_PATH.exec(pathname)
  if (!match) return { kind: 'none' }
  const [, segment, id] = match
  if (id === undefined || !isCanonicalPositiveJavaLong(id)) return { kind: 'invalid' }
  return segment === 'complexes'
    ? { kind: 'complex', complexId: id }
    : { kind: 'announcement', announcementId: id }
}

/** 상세·지역 주소의 형식이 틀렸는지. 틀린 주소는 없는 페이지다. */
export function isInvalidExplorerPath(pathname: string): boolean {
  return parseDetailPath(pathname).kind === 'invalid' || parseRegionPath(pathname).kind === 'invalid'
}

/** 상세·지역 주소도 지도 화면이다. 동의 배너·분석·로그인 복귀처럼 화면 단위로 판단할 때 쓰는 경로. */
export function explorerPagePath(pathname: string): string {
  const { kind } = parseDetailPath(pathname)
  if (kind === 'complex' || kind === 'announcement' || parseRegionPath(pathname).kind === 'region') return EXPLORER_PATH
  return pathname
}

interface PathLocation {
  readonly pathname: string
  readonly search: string
}

/** 상세·지역 주소를 지도 화면이 읽는 `/explore?…` 쿼리 형태로 바꾼다. 경로의 값이 쿼리보다 우선한다. */
export function toExplorerQueryLocation<T extends PathLocation>(location: T): T {
  const detail = parseDetailPath(location.pathname)
  const region = parseRegionPath(location.pathname)
  const search = new URLSearchParams(location.search)
  if (detail.kind === 'complex' || detail.kind === 'announcement') {
    const next = clearDetailQuery(search)
    if (detail.kind === 'complex') next.set('complexId', detail.complexId)
    else next.set('announcementId', detail.announcementId)
    return withSearch(location, next)
  }
  if (region.kind !== 'region') return location
  search.set(REGION_CODE_QUERY_KEY, region.regionCode)
  search.delete(RENTAL_TYPES_QUERY_KEY)
  if (region.rentalType !== null) search.set(RENTAL_TYPES_QUERY_KEY, region.rentalType)
  return withSearch(location, search)
}

/** 지도 화면의 `/explore?complexId=…` 주소를 주소창에 보일 상세 주소로 바꾼다. */
export function toDetailPathLocation<T extends PathLocation>(location: T): T {
  if (location.pathname !== EXPLORER_PATH) return location
  const searchParams = new URLSearchParams(location.search)
  const detail = parseDetailLocation(searchParams)
  if (detail.kind !== 'complex' && detail.kind !== 'announcement') return location
  const query = clearDetailQuery(searchParams).toString()
  return {
    ...location,
    pathname: detail.kind === 'complex' ? complexPath(detail.complexId) : announcementPath(detail.announcementId),
    search: query ? `?${query}` : '',
  }
}

/**
 * 지도 화면이 만든 주소를 주소창 주소로 바꾼다. 상세는 상세 주소로, 지역 주소에서 같은 지역 조건을 유지하는
 * 이동은 지역 주소로 남긴다. 조건을 바꾸면 `/explore`로 넘어간다.
 */
export function toPublicLocation<T extends PathLocation>(target: T, current: PathLocation): T {
  const detail = toDetailPathLocation(target)
  if (detail !== target) return detail
  const region = parseRegionPath(current.pathname)
  const search = new URLSearchParams(target.search)
  if (target.pathname !== EXPLORER_PATH || region.kind !== 'region' || !matchesRegionPath(search, region)) return target
  search.delete(REGION_CODE_QUERY_KEY)
  search.delete(RENTAL_TYPES_QUERY_KEY)
  return { ...withSearch(target, search), pathname: regionPath(region.regionCode, region.rentalType) }
}

function withSearch<T extends PathLocation>(location: T, search: URLSearchParams): T {
  const query = search.toString()
  return { ...location, pathname: EXPLORER_PATH, search: query ? `?${query}` : '' }
}
