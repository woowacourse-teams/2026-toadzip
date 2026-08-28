import { describe, expect, it } from 'vitest'

import {
  SCREEN_MARKER_CLUSTER_RADIUS,
  clusterScreenMarkers,
  type ProjectedScreenMarker,
} from './screenMarkerClustering.ts'

function marker(
  id: string,
  x: number,
  y: number,
  latitude: number,
  longitude: number,
): ProjectedScreenMarker {
  return { id, latitude, longitude, x, y }
}

function clusterMemberIds(markers: readonly ProjectedScreenMarker[]) {
  return clusterScreenMarkers(markers).map((item) => {
    if (item.kind === 'singleton') {
      return [item.marker.id]
    }

    return item.markers.map((member) => member.id)
  })
}

describe('clusterScreenMarkers', () => {
  it('64px 거리까지 묶고 그보다 멀면 별도 marker로 유지한다', () => {
    const markers = [
      marker('a', 0, 0, 37.5, 126.9),
      marker('b', SCREEN_MARKER_CLUSTER_RADIUS, 0, 37.6, 127),
      marker(
        'c',
        SCREEN_MARKER_CLUSTER_RADIUS * 2 + 0.01,
        0,
        37.7,
        127.1,
      ),
    ]

    expect(clusterMemberIds(markers)).toEqual([['a', 'b'], ['c']])
  })

  it('공간 hash 셀 경계를 사이에 둔 가까운 marker도 묶는다', () => {
    const markers = [
      marker('west', 63.5, 63.5, 37.5, 126.9),
      marker('east', 64.5, 64.5, 37.6, 127),
    ]

    expect(clusterMemberIds(markers)).toEqual([['east', 'west']])
  })

  it('연결된 모든 marker를 하나의 cluster로 합치고 중심을 평균낸다', () => {
    const markers = [
      marker('a', 0, 10, 37.5, 126.9),
      marker('b', 50, 20, 37.6, 127),
      marker('c', 100, 30, 37.7, 127.1),
    ]

    expect(clusterScreenMarkers(markers)).toEqual([
      {
        id: 'cluster:["a","b","c"]',
        kind: 'cluster',
        latitude: 37.6,
        longitude: 127,
        markers,
        x: 50,
        y: 20,
      },
    ])
  })

  it('입력 순서와 무관하게 같은 순서와 구성으로 반환한다', () => {
    const markers = [
      marker('b', 10, 0, 37.51, 126.91),
      marker('d', 210, 0, 37.71, 127.11),
      marker('a', 0, 0, 37.5, 126.9),
      marker('c', 200, 0, 37.7, 127.1),
    ]

    expect(clusterScreenMarkers(markers)).toEqual(
      clusterScreenMarkers(markers.toReversed()),
    )
    expect(clusterMemberIds(markers)).toEqual([
      ['a', 'b'],
      ['c', 'd'],
    ])
  })

  it('모든 화면 좌표를 같은 만큼 옮겨도 cluster 구성은 바뀌지 않는다', () => {
    const markers = [
      marker('a', -20, 10, 37.5, 126.9),
      marker('b', 20, 20, 37.6, 127),
      marker('c', 200, 30, 37.7, 127.1),
    ]
    const translated = markers.map((item) => ({
      ...item,
      x: item.x + 1_000,
      y: item.y - 500,
    }))

    const originalResult = clusterScreenMarkers(markers)
    const translatedResult = clusterScreenMarkers(translated)

    expect(clusterMemberIds(translated)).toEqual(clusterMemberIds(markers))
    expect(translatedResult[0]).toMatchObject({
      kind: 'cluster',
      latitude: 37.55,
      longitude: 126.95,
      x: 1_000,
      y: -485,
    })
    expect(originalResult[0]).toMatchObject({ x: 0, y: 15 })
  })
})
