export interface RecentComplex {
  readonly complexId: string
  readonly name: string
  readonly address: string | null
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
  try {
    localStorage.setItem(RECENT_COMPLEXES_KEY, JSON.stringify(next))
  } catch {
    // Browsing remains available when persistence is blocked or full.
  }
  return next
}

function isRecentComplex(value: unknown): value is RecentComplex {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  return 'complexId' in value && typeof value.complexId === 'string' && value.complexId.length > 0
    && 'name' in value && typeof value.name === 'string' && value.name.length > 0
    && 'address' in value && (value.address === null || typeof value.address === 'string')
}
