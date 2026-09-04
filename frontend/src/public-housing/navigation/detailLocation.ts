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
