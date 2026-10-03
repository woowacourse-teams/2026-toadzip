import { clusterComplexMarkers, type ClusteredComplexMarker } from './markerClustering.ts'
import type {
  NaverMapAggregateMarker,
  NaverMapMarker,
} from './naverMapTypes.ts'

interface RenderedAggregateMarker {
  readonly kind: 'aggregate'
  readonly marker: NaverMapAggregateMarker
}

export type RenderedMarker = RenderedAggregateMarker | ClusteredComplexMarker

export function toRenderedMarkers(
  aggregateMarkers: readonly NaverMapAggregateMarker[],
  markers: readonly NaverMapMarker[],
  representation: 'AGGREGATE' | 'INDIVIDUAL',
  maps: typeof naver.maps,
  map: naver.maps.Map,
): RenderedMarker[] {
  if (representation === 'AGGREGATE') {
    return uniqueSortedById(aggregateMarkers, (marker) => marker.groupKey).map((marker) => ({
      kind: 'aggregate',
      marker,
    }))
  }
  return clusterComplexMarkers(maps, map, uniqueSortedById(markers, (marker) => marker.id))
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
  if (marker.kind === 'cluster') {
    return JSON.stringify([
      marker.kind, marker.marker.id, marker.marker.latitude, marker.marker.longitude, marker.marker.bounds,
      ...marker.marker.members.map((member) => [member.id, member.name, member.latitude, member.longitude]),
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
    ...(representation === 'AGGREGATE'
      ? uniqueSortedById(aggregateMarkers, (marker) => marker.groupKey)
        .map((marker) => renderedMarkerContentKey({ kind: 'aggregate', marker }))
      : uniqueSortedById(markers, (marker) => marker.id)
        .map((marker) => renderedMarkerContentKey({ kind: 'complex', marker }))),
  ])
}

/** Selection changes overlap groups but should not replay the entry animation. */
export function createMarkerGeometryKey(
  aggregateMarkers: readonly NaverMapAggregateMarker[],
  markers: readonly NaverMapMarker[],
  representation: 'AGGREGATE' | 'INDIVIDUAL',
): string {
  return JSON.stringify([
    createMarkerContentKey(aggregateMarkers, markers, representation),
    ...uniqueSortedById(markers, (marker) => marker.id)
      .filter((marker) => marker.selected).map((marker) => marker.id),
  ])
}
