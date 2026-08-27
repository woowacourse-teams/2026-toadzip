import type { NaverMapMarker } from '../../maps/naver/NaverMap.tsx'

const UNKNOWN_COMPLEX_NAME = '단지명 정보 확인 중'

export interface LocalMapRegionSnapshot {
  readonly regionCode: string
  readonly name: string
  readonly anchor: {
    readonly latitude: number
    readonly longitude: number
  }
}

export interface LocalMapComplexSnapshot {
  readonly complexId: string
  readonly regionCode: string
  readonly name: string | null
  readonly latitude: number
  readonly longitude: number
}

export interface LocalMapSnapshot {
  readonly regions: readonly LocalMapRegionSnapshot[]
  readonly complexes: readonly LocalMapComplexSnapshot[]
}

export function resolveLocalMapMarkers(
  snapshot: LocalMapSnapshot,
  expandedRegionCode: string | null,
  selectedComplexId: string | null = null,
): NaverMapMarker[] {
  const expandedRegionExists = snapshot.regions.some(
    ({ regionCode }) => regionCode === expandedRegionCode,
  )

  return snapshot.regions.flatMap<NaverMapMarker>((region) => {
    const complexes = uniqueRegionComplexes(snapshot.complexes, region.regionCode)
    if (complexes.length === 0) {
      return []
    }
    if (expandedRegionExists && region.regionCode === expandedRegionCode) {
      return complexes.map((complex) => ({
        kind: 'complex',
        id: complex.complexId,
        latitude: complex.latitude,
        longitude: complex.longitude,
        name: complex.name ?? UNKNOWN_COMPLEX_NAME,
        regionCode: region.regionCode,
        regionName: region.name,
        selected: complex.complexId === selectedComplexId,
      }))
    }

    return [{
      kind: 'region-cluster',
      latitude: region.anchor.latitude,
      longitude: region.anchor.longitude,
      regionCode: region.regionCode,
      regionName: region.name,
      uniqueComplexCount: complexes.length,
    }]
  })
}

function uniqueRegionComplexes(
  complexes: readonly LocalMapComplexSnapshot[],
  regionCode: string,
): LocalMapComplexSnapshot[] {
  const uniqueComplexes = new Map<string, LocalMapComplexSnapshot>()
  complexes.forEach((complex) => {
    if (complex.regionCode !== regionCode || uniqueComplexes.has(complex.complexId)) {
      return
    }
    uniqueComplexes.set(complex.complexId, complex)
  })
  return [...uniqueComplexes.values()]
}
