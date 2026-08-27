import { describe, expect, it } from 'vitest'
import type { NaverMapMarker } from '../../maps/naver/NaverMap.tsx'
import {
  resolveLocalMapMarkers,
  type LocalMapSnapshot,
} from './localMapMarkerResolver.ts'

const SNAPSHOT = {
  regions: [
    {
      regionCode: '11140',
      name: '서울 중구',
      anchor: { latitude: 37.5636, longitude: 126.9976 },
    },
    {
      regionCode: '26110',
      name: '부산 중구',
      anchor: { latitude: 35.1064, longitude: 129.0323 },
    },
  ],
  complexes: [
    {
      complexId: '17',
      regionCode: '11140',
      name: '서울가람 행복주택',
      latitude: 37.5666,
      longitude: 126.9784,
    },
    {
      complexId: '17',
      regionCode: '11140',
      name: '중복 서울가람 행복주택',
      latitude: 37.9,
      longitude: 127.4,
    },
    {
      complexId: '18',
      regionCode: '11140',
      name: null,
      latitude: 37.564,
      longitude: 126.99,
    },
    {
      complexId: '31',
      regionCode: '26110',
      name: '부산 바다 임대주택',
      latitude: 35.104,
      longitude: 129.03,
    },
  ],
} satisfies LocalMapSnapshot

describe('resolveLocalMapMarkers', () => {
  it('전체 snapshot의 고유 단지 수와 고정 anchor로 1곳도 지역 cluster로 만든다', () => {
    const markers = resolveLocalMapMarkers(SNAPSHOT, null)

    expect(markers).toEqual([
      regionCluster('11140', '서울 중구', 2, 37.5636, 126.9976),
      regionCluster('26110', '부산 중구', 1, 35.1064, 129.0323),
    ])
  })

  it('선택한 지역만 중복 제거한 개별 단지로 바꾸고 다른 지역 cluster는 유지한다', () => {
    const markers = resolveLocalMapMarkers(SNAPSHOT, '11140', '18')

    expect(markers).toEqual([
      {
        kind: 'complex',
        id: '17',
        latitude: 37.5666,
        longitude: 126.9784,
        name: '서울가람 행복주택',
        regionCode: '11140',
        regionName: '서울 중구',
        selected: false,
      },
      {
        kind: 'complex',
        id: '18',
        latitude: 37.564,
        longitude: 126.99,
        name: '단지명 정보 확인 중',
        regionCode: '11140',
        regionName: '서울 중구',
        selected: true,
      },
      regionCluster('26110', '부산 중구', 1, 35.1064, 129.0323),
    ])
  })

  it('snapshot에 없는 regionCode를 선택하면 고정 cluster 상태를 유지한다', () => {
    expect(resolveLocalMapMarkers(SNAPSHOT, '99999')).toEqual(
      resolveLocalMapMarkers(SNAPSHOT, null),
    )
  })
})

function regionCluster(
  regionCode: string,
  regionName: string,
  uniqueComplexCount: number,
  latitude: number,
  longitude: number,
): NaverMapMarker {
  return {
    kind: 'region-cluster',
    latitude,
    longitude,
    regionCode,
    regionName,
    uniqueComplexCount,
  }
}
