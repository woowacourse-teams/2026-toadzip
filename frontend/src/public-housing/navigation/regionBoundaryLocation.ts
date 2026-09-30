const BOUNDARY_REGION_KEY = 'boundaryRegionCode'
const REGION_CODE = /^(?:\d{2}|\d{5})$/

export function parseRegionBoundaryCode(query: URLSearchParams): string | null {
  const values = query.getAll(BOUNDARY_REGION_KEY)
  return values.length === 1 && REGION_CODE.test(values[0]) ? values[0] : null
}

export function setRegionBoundaryCode(query: URLSearchParams, code: string | null) {
  const next = new URLSearchParams(query)
  next.delete(BOUNDARY_REGION_KEY)
  if (code !== null && REGION_CODE.test(code)) {
    next.set(BOUNDARY_REGION_KEY, code)
  }
  return next
}
