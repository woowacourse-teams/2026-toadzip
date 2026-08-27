const COMPLEX_ID_QUERY_KEY = 'complexId'
const MAXIMUM_JAVA_LONG = '9223372036854775807'
const CANONICAL_POSITIVE_INTEGER = /^[1-9][0-9]*$/

export type ComplexIdQueryResult =
  | { readonly kind: 'absent'; readonly complexId: null }
  | { readonly kind: 'invalid'; readonly complexId: null }
  | { readonly kind: 'valid'; readonly complexId: string }

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

export function setComplexIdQuery(
  searchParams: URLSearchParams,
  complexId: string,
): URLSearchParams {
  if (!isCanonicalPositiveJavaLong(complexId)) {
    throw new TypeError('정규 positive Java Long ID가 아닙니다.')
  }

  const nextSearchParams = new URLSearchParams(searchParams)
  nextSearchParams.set(COMPLEX_ID_QUERY_KEY, complexId)
  return nextSearchParams
}

export function clearComplexIdQuery(
  searchParams: URLSearchParams,
): URLSearchParams {
  const nextSearchParams = new URLSearchParams(searchParams)
  nextSearchParams.delete(COMPLEX_ID_QUERY_KEY)
  return nextSearchParams
}
