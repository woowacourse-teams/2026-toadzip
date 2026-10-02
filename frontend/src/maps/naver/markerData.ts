import type {
  NaverMapAggregateMarker,
  NaverMapComplexMarker,
  NaverMapMarker,
} from './naverMapTypes.ts'

interface RenderedComplexMarker {
  readonly kind: 'complex'
  readonly marker: NaverMapComplexMarker
}

interface RenderedAggregateMarker {
  readonly kind: 'aggregate'
  readonly marker: NaverMapAggregateMarker
}

export type RenderedMarker = RenderedAggregateMarker | RenderedComplexMarker

export function toRenderedMarkers(
  aggregateMarkers: readonly NaverMapAggregateMarker[],
  markers: readonly NaverMapMarker[],
  representation: 'AGGREGATE' | 'INDIVIDUAL',
): RenderedMarker[] {
  if (representation === 'AGGREGATE') {
    return uniqueSortedById(aggregateMarkers, (marker) => marker.groupKey).map((marker) => ({
      kind: 'aggregate',
      marker,
    }))
  }
  return uniqueSortedById(markers, (marker) => marker.id).map((marker) => ({
    kind: 'complex',
    marker,
  }))
}

function uniqueSortedById<T>(markers: readonly T[], getId: (marker: T) => string): T[] {
  const uniqueMarkers = new Map<string, T>()
  for (const marker of markers) {
    const id = getId(marker)
    if (!uniqueMarkers.has(id)) {
      uniqueMarkers.set(id, marker)
    }
  }
  return [...uniqueMarkers.entries()]
    .sort(([leftId], [rightId]) => leftId.localeCompare(rightId))
    .map(([, marker]) => marker)
}

export function renderedMarkerId(marker: RenderedMarker) {
  if (marker.kind === 'aggregate') {
    return marker.marker.groupKey
  }
  return marker.marker.id
}

export function renderedMarkerIdentity(marker: RenderedMarker) {
  return JSON.stringify([marker.kind, renderedMarkerId(marker)])
}

/** Selection and highlight are updated in place; all other visible fields replace the overlay. */
export function renderedMarkerContentKey(marker: RenderedMarker) {
  if (marker.kind === 'aggregate') {
    return JSON.stringify([
      marker.kind,
      marker.marker.groupKey,
      marker.marker.groupLabel,
      marker.marker.latitude,
      marker.marker.longitude,
      marker.marker.uniqueComplexCount,
      marker.marker.nextStage,
      marker.marker.expansionZoom,
    ])
  }
  return JSON.stringify([
    marker.kind,
    marker.marker.id,
    marker.marker.latitude,
    marker.marker.longitude,
    marker.marker.name,
    marker.marker.agencyLabel,
    marker.marker.agencyName,
    marker.marker.rentalTypeLabel,
    marker.marker.rentalTypeName,
    marker.marker.deposit?.digits,
    marker.marker.deposit?.unit,
    marker.marker.deposit?.exactLabel,
    marker.marker.monthlyRent?.digits,
    marker.marker.monthlyRent?.unit,
    marker.marker.monthlyRent?.exactLabel,
  ])
}

export function createMarkerContentKey(
  aggregateMarkers: readonly NaverMapAggregateMarker[],
  markers: readonly NaverMapMarker[],
  representation: 'AGGREGATE' | 'INDIVIDUAL',
): string {
  return JSON.stringify([
    representation,
    ...toRenderedMarkers(aggregateMarkers, markers, representation).map(renderedMarkerContentKey),
  ])
}
