export const SCREEN_MARKER_CLUSTER_RADIUS = 64

export interface ProjectedScreenMarker {
  readonly id: string
  readonly latitude: number
  readonly longitude: number
  readonly x: number
  readonly y: number
}

export interface SingletonScreenMarker {
  readonly kind: 'singleton'
  readonly marker: ProjectedScreenMarker
}

export interface ClusteredScreenMarkers {
  readonly id: string
  readonly kind: 'cluster'
  readonly latitude: number
  readonly longitude: number
  readonly markers: readonly ProjectedScreenMarker[]
  readonly x: number
  readonly y: number
}

export type ScreenMarkerClusteringResult =
  | SingletonScreenMarker
  | ClusteredScreenMarkers

interface CellCoordinate {
  readonly x: number
  readonly y: number
}

function compareMarkerIds(
  left: ProjectedScreenMarker,
  right: ProjectedScreenMarker,
): number {
  if (left.id < right.id) {
    return -1
  }

  if (left.id > right.id) {
    return 1
  }

  return 0
}

function toCell(marker: ProjectedScreenMarker): CellCoordinate {
  return {
    x: Math.floor(marker.x / SCREEN_MARKER_CLUSTER_RADIUS),
    y: Math.floor(marker.y / SCREEN_MARKER_CLUSTER_RADIUS),
  }
}

function toCellKey(cell: CellCoordinate): string {
  return `${cell.x}:${cell.y}`
}

function isWithinClusterRadius(
  left: ProjectedScreenMarker,
  right: ProjectedScreenMarker,
): boolean {
  const xDistance = left.x - right.x
  const yDistance = left.y - right.y
  const squaredDistance = xDistance ** 2 + yDistance ** 2

  return squaredDistance <= SCREEN_MARKER_CLUSTER_RADIUS ** 2
}

function findRoot(parents: number[], index: number): number {
  let root = index
  while (parents[root] !== root) {
    root = parents[root]
  }

  let current = index
  while (parents[current] !== current) {
    const next = parents[current]
    parents[current] = root
    current = next
  }

  return root
}

function connect(parents: number[], left: number, right: number) {
  const leftRoot = findRoot(parents, left)
  const rightRoot = findRoot(parents, right)
  if (leftRoot === rightRoot) {
    return
  }

  if (leftRoot < rightRoot) {
    parents[rightRoot] = leftRoot
    return
  }

  parents[leftRoot] = rightRoot
}

function connectNearbyMarkers(
  markers: readonly ProjectedScreenMarker[],
  parents: number[],
) {
  const cells = new Map<string, number[]>()

  markers.forEach((marker, markerIndex) => {
    const cell = toCell(marker)
    for (let xOffset = -1; xOffset <= 1; xOffset += 1) {
      for (let yOffset = -1; yOffset <= 1; yOffset += 1) {
        const neighbors = cells.get(
          toCellKey({ x: cell.x + xOffset, y: cell.y + yOffset }),
        )
        neighbors?.forEach((neighborIndex) => {
          if (isWithinClusterRadius(marker, markers[neighborIndex])) {
            connect(parents, markerIndex, neighborIndex)
          }
        })
      }
    }

    const cellKey = toCellKey(cell)
    const members = cells.get(cellKey) ?? []
    members.push(markerIndex)
    cells.set(cellKey, members)
  })
}

function groupConnectedMarkers(
  markers: readonly ProjectedScreenMarker[],
  parents: number[],
): ProjectedScreenMarker[][] {
  const groups = new Map<number, ProjectedScreenMarker[]>()
  markers.forEach((marker, markerIndex) => {
    const root = findRoot(parents, markerIndex)
    const group = groups.get(root) ?? []
    group.push(marker)
    groups.set(root, group)
  })

  return [...groups.values()].sort((left, right) =>
    compareMarkerIds(left[0], right[0]),
  )
}

function average(
  markers: readonly ProjectedScreenMarker[],
  coordinate: (marker: ProjectedScreenMarker) => number,
): number {
  const total = markers.reduce(
    (sum, marker) => sum + coordinate(marker),
    0,
  )
  return total / markers.length
}

function toClusteringResult(
  markers: readonly ProjectedScreenMarker[],
): ScreenMarkerClusteringResult {
  if (markers.length === 1) {
    return { kind: 'singleton', marker: markers[0] }
  }

  const markerIds = markers.map((marker) => marker.id)
  return {
    id: `cluster:${JSON.stringify(markerIds)}`,
    kind: 'cluster',
    latitude: average(markers, (marker) => marker.latitude),
    longitude: average(markers, (marker) => marker.longitude),
    markers,
    x: average(markers, (marker) => marker.x),
    y: average(markers, (marker) => marker.y),
  }
}

export function clusterScreenMarkers(
  markers: readonly ProjectedScreenMarker[],
): ScreenMarkerClusteringResult[] {
  const sortedMarkers = [...markers].sort(compareMarkerIds)
  const parents = sortedMarkers.map((_, index) => index)

  connectNearbyMarkers(sortedMarkers, parents)

  return groupConnectedMarkers(sortedMarkers, parents).map(toClusteringResult)
}
