import type { MapBounds } from '../../public-housing/model/publicHousing.ts'
import { markerWidth } from './complexMarkerButton.ts'
import type { NaverMapComplexMarker } from './naverMapTypes.ts'

export interface ComplexMarkerCluster {
  readonly id: string
  readonly latitude: number
  readonly longitude: number
  readonly bounds: MapBounds
  readonly members: readonly NaverMapComplexMarker[]
}

export type ClusteredComplexMarker =
  | { readonly kind: 'complex'; readonly marker: NaverMapComplexMarker }
  | { readonly kind: 'cluster'; readonly marker: ComplexMarkerCluster }

/** Group overlapping marker cards in screen pixels, independently of administrative regions. */
export function clusterComplexMarkers(
  maps: typeof naver.maps,
  map: naver.maps.Map,
  markers: readonly NaverMapComplexMarker[],
): ClusteredComplexMarker[] {
  if (markers.length === 0) return []
  const projection = map.getProjection()
  const center = projection.fromCoordToOffset(map.getCenter())
  const { width, height } = map.getSize()
  const selected: ClusteredComplexMarker[] = markers.filter((marker) => marker.selected)
    .map((marker) => ({ kind: 'complex', marker }))
  if (width <= 0 || height <= 0 || !Number.isFinite(center.x) || !Number.isFinite(center.y)) {
    return selected
  }
  const viewport = {
    left: center.x - width / 2, right: center.x + width / 2,
    top: center.y - height / 2, bottom: center.y + height / 2,
  }
  // Culling only affects SDK overlays, never the region's full result list or count.
  // Include the marker card's footprint and a small edge buffer for smooth panning.
  const projected = markers.filter((marker) => !marker.selected).flatMap((marker) => {
    const point = projection.fromCoordToOffset(new maps.LatLng(marker.latitude, marker.longitude))
    const halfWidth = markerWidth(marker) / 2
    if (!Number.isFinite(point.x) || !Number.isFinite(point.y)
      || point.x + halfWidth < viewport.left - 16 || point.x - halfWidth > viewport.right + 16
      || point.y < viewport.top - 16 || point.y - 66 > viewport.bottom + 16) return []
    return [{ marker, x: point.x, y: point.y, halfWidth }]
  })
  const candidates = projected.map(({ marker }) => marker)
  const points = projected
  const pointsById = new Map(projected.map((point) => [point.marker.id, point]))
  const parents = candidates.map((_, index) => index)
  const findRoot = (index: number): number => {
    let root = index
    while (parents[root] !== root) root = parents[root]
    while (parents[index] !== root) {
      const next = parents[index]
      parents[index] = root
      index = next
    }
    return root
  }
  const buckets = new Map<string, number[]>()
  points.forEach((point, index) => {
    const minX = Math.floor((point.x - point.halfWidth - 2) / 128)
    const maxX = Math.floor((point.x + point.halfWidth + 2) / 128)
    const minY = Math.floor((point.y - 68) / 128)
    const maxY = Math.floor((point.y + 2) / 128)
    const compared = new Set<number>()
    for (let x = minX; x <= maxX; x += 1) {
      for (let y = minY; y <= maxY; y += 1) {
        const key = `${x}:${y}`
        const bucket = buckets.get(key) ?? []
        for (const previous of bucket) {
          if (compared.has(previous)) continue
          compared.add(previous)
          const other = points[previous]
          if (Math.abs(point.x - other.x) < point.halfWidth + other.halfWidth + 4
            && Math.abs(point.y - other.y) < 70) {
            parents[findRoot(index)] = findRoot(previous)
          }
        }
        bucket.push(index)
        buckets.set(key, bucket)
      }
    }
  })
  const groups = new Map<number, NaverMapComplexMarker[]>()
  candidates.forEach((marker, index) => {
    const root = findRoot(index)
    const members = groups.get(root) ?? []
    members.push(marker)
    groups.set(root, members)
  })
  const result = [...selected]
  for (const members of groups.values()) {
    if (members.length === 1) {
      result.push({ kind: 'complex', marker: members[0] })
      continue
    }
    const bounds = members.reduce((range, marker) => ({
      southWestLat: Math.min(range.southWestLat, marker.latitude),
      southWestLng: Math.min(range.southWestLng, marker.longitude),
      northEastLat: Math.max(range.northEastLat, marker.latitude),
      northEastLng: Math.max(range.northEastLng, marker.longitude),
    }), {
      southWestLat: members[0].latitude, southWestLng: members[0].longitude,
      northEastLat: members[0].latitude, northEastLng: members[0].longitude,
    })
    let latitude = (bounds.southWestLat + bounds.northEastLat) / 2
    let longitude = (bounds.southWestLng + bounds.northEastLng) / 2
    const clusterPoint = projection.fromCoordToOffset(new maps.LatLng(latitude, longitude))
    if (clusterPoint.x < viewport.left || clusterPoint.x > viewport.right
      || clusterPoint.y < viewport.top || clusterPoint.y > viewport.bottom) {
      // An edge group must not hide its visible member behind an off-screen center.
      const visibleMember = members.find((member) => {
        const point = pointsById.get(member.id)
        return point && point.x >= viewport.left && point.x <= viewport.right
          && point.y >= viewport.top && point.y <= viewport.bottom
      })
      if (visibleMember) {
        latitude = visibleMember.latitude
        longitude = visibleMember.longitude
      }
    }
    result.push({
      kind: 'cluster',
      marker: {
        id: members.map((marker) => marker.id).join(','),
        latitude,
        longitude,
        bounds,
        members,
      },
    })
  }
  return result
}
