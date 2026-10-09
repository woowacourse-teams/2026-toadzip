import { describe, expect, it } from 'vitest'
import { districtRegionOptionsForProvince } from '../model/publicHousingRegion.ts'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from '../testing/minimalPublicHousingSnapshot.ts'
import { createSnapshotPublicHousingRegionRepository } from './snapshotPublicHousingRegionRepository.ts'

describe('snapshot public housing region repository', () => {
  it.each([
    ['1111010100', '11110', '서울특별시 종로구', '1111010100', '서울특별시 종로구 청운동'],
    ['4182025021', '41820', '경기도 가평군', '4182025000', '경기도 가평군 가평읍'],
  ])('10자리 코드 %s에서도 부모 시군구와 공식 읍면동 이름을 복원한다', async (code, parentCode, parentName, neighborhoodCode, displayName) => {
    const repository = createSnapshotPublicHousingRegionRepository({
      ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
      complexRegionCodes: { 17: code },
      announcementRegionCodes: {},
      complexListItems: [{
        ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT.complexListItems[0],
        regionName: parentName,
      }],
    })
    const regions = await repository.search(parentName, new AbortController().signal)
    expect(regions).toEqual(expect.arrayContaining([
      expect.objectContaining({ regionCode: parentCode, displayName: parentName }),
      expect.objectContaining({ regionCode: neighborhoodCode, displayName }),
    ]))
    expect(districtRegionOptionsForProvince(regions, code.slice(0, 2)).map(({ regionCode }) => regionCode)).toEqual([parentCode])
  })

  it('snapshot 표시명과 하위 코드로 도·상위 시 선택지를 복원한다', async () => {
    const snapshot = {
      ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT,
      complexRegionCodes: { 17: '41135' },
      regionCodeDescendants: {
        41130: ['41131', '41133', '41135', '41137', '41139'],
      },
      complexListItems: [{
        ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT.complexListItems[0],
        regionName: '경기도 성남시 분당구',
      }],
    }
    const repository = createSnapshotPublicHousingRegionRepository(snapshot)

    const regions = await repository.search(
      '경기도',
      new AbortController().signal,
    )

    expect(regions).toEqual(expect.arrayContaining([
      {
        regionCode: '41130',
        provinceName: '경기도',
        districtName: '성남시',
        displayName: '경기도 성남시',
      },
      {
        regionCode: '41135',
        provinceName: '경기도',
        districtName: '성남시 분당구',
        displayName: '경기도 성남시 분당구',
      },
    ]))
    expect(districtRegionOptionsForProvince(regions, '41').map(
      ({ regionCode }) => regionCode,
    )).toEqual(['41130'])
  })
})
