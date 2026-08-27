import { describe, expect, it } from 'vitest'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from '../testing/minimalPublicHousingSnapshot.ts'
import {
  decodeLocalPublicHousingMapSnapshot,
  LocalPublicHousingMapSnapshotError,
} from './localPublicHousingMapSnapshot.ts'

const MAP_REGIONS = [
  {
    regionCode: '11140',
    name: '서울 중구',
    anchor: { latitude: 37.5636, longitude: 126.9976 },
    complexIds: [17],
  },
]

describe('decodeLocalPublicHousingMapSnapshot', () => {
  it('production 지도 DTO와 고정 행정구역 기준을 연결한다', () => {
    const snapshot = decodeLocalPublicHousingMapSnapshot({
      ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
      mapRegions: MAP_REGIONS,
    })

    expect(snapshot).toEqual({
      regions: [{
        regionCode: '11140',
        name: '서울 중구',
        anchor: { latitude: 37.5636, longitude: 126.9976 },
      }],
      complexes: [{
        complexId: '17',
        regionCode: '11140',
        name: '서울가람 행복주택',
        latitude: 37.5666,
        longitude: 126.9784,
      }],
    })
  })

  it('존재하지 않거나 다른 지역에 중복된 단지 연결을 거부한다', () => {
    const invalidRegions = [
      MAP_REGIONS[0],
      {
        ...MAP_REGIONS[0],
        regionCode: '11200',
        name: '서울 성동구',
      },
    ]

    expect(() => decodeLocalPublicHousingMapSnapshot({
      ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
      mapRegions: invalidRegions,
    })).toThrow(LocalPublicHousingMapSnapshotError)
    expect(() => decodeLocalPublicHousingMapSnapshot({
      ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
      mapRegions: [{ ...MAP_REGIONS[0], complexIds: [999] }],
    })).toThrow('$.mapRegions[0].complexIds[0]')
  })

  it('지도 DTO에 있는 모든 단지가 행정구역에 포함되어야 한다', () => {
    expect(() => decodeLocalPublicHousingMapSnapshot({
      ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
      mapRegions: [],
    })).toThrow('$.mapRegions')
  })

  it('production mapper가 제외하는 잘못된 좌표를 mock에서 허용하지 않는다', () => {
    expect(() => decodeLocalPublicHousingMapSnapshot({
      ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
      mapComplexItems: [{
        ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT.mapComplexItems[0],
        latitude: 91,
      }],
      mapRegions: MAP_REGIONS,
    })).toThrow('$.mapComplexItems')
  })
})
