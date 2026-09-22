export interface RecentComplex {
  readonly complexId: string
  readonly name: string
  readonly address: string | null
  readonly agencyCode?: string | null
  readonly agencyName?: string | null
  readonly rentalType?: string | null
}

export const RECENT_COMPLEXES_KEY = 'toadzip.recent-complexes.v1'
const MAXIMUM_RECENT_COMPLEXES = 3

export function readRecentComplexes(): readonly RecentComplex[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(RECENT_COMPLEXES_KEY) ?? '[]')
    if (!Array.isArray(value)) {
      return []
    }
    const entries = value.filter(isRecentComplex)
    return entries.filter((entry, index) =>
      entries.findIndex(({ complexId }) => complexId === entry.complexId) === index,
    ).slice(0, MAXIMUM_RECENT_COMPLEXES)
  } catch {
    return []
  }
}

export function rememberComplex(
  current: readonly RecentComplex[],
  complex: RecentComplex,
): readonly RecentComplex[] {
  const next = [complex, ...current.filter(({ complexId }) => complexId !== complex.complexId)]
    .slice(0, MAXIMUM_RECENT_COMPLEXES)
  persistRecentComplexes(next)
  return next
}

export function enrichRecentComplexes(
  current: readonly RecentComplex[],
  available: readonly Pick<RecentComplex, 'complexId' | 'agencyCode' | 'agencyName' | 'rentalType'>[],
): readonly RecentComplex[] {
  const next = current.map((recent) => {
    const metadata = available.find(({ complexId }) => complexId === recent.complexId)
    if (!metadata) {
      return recent
    }
    const agencyCode = recent.agencyCode ?? metadata.agencyCode ?? recent.agencyCode
    const agencyName = recent.agencyName ?? metadata.agencyName ?? recent.agencyName
    const rentalType = recent.rentalType ?? metadata.rentalType ?? recent.rentalType
    if (agencyCode === recent.agencyCode && agencyName === recent.agencyName && rentalType === recent.rentalType) {
      return recent
    }
    return { ...recent, agencyCode, agencyName, rentalType }
  })
  if (next.every((recent, index) => recent === current[index])) {
    return current
  }
  persistRecentComplexes(next)
  return next
}

function persistRecentComplexes(complexes: readonly RecentComplex[]): void {
  try {
    localStorage.setItem(RECENT_COMPLEXES_KEY, JSON.stringify(complexes))
  } catch {
    // Browsing remains available when persistence is blocked or full.
  }
}

function isRecentComplex(value: unknown): value is RecentComplex {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  return 'complexId' in value && typeof value.complexId === 'string' && value.complexId.length > 0
    && 'name' in value && typeof value.name === 'string' && value.name.length > 0
    && 'address' in value && (value.address === null || typeof value.address === 'string')
    && (!('agencyCode' in value) || value.agencyCode === null || typeof value.agencyCode === 'string')
    && (!('agencyName' in value) || value.agencyName === null || typeof value.agencyName === 'string')
    && (!('rentalType' in value) || value.rentalType === null || typeof value.rentalType === 'string')
}
