import { describe, expect, it } from 'vitest'
import { toComplexPage } from '../api/publicHousingMapper.ts'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from '../testing/minimalPublicHousingSnapshot.ts'
import { toHousingComplexCardData } from './complexPresentation.ts'

describe('단지 카드 표시 변환', () => {
  const complex = toComplexPage({ items: [MINIMAL_PUBLIC_HOUSING_SNAPSHOT.complexListItems[0]], hasNext: false, nextCursor: null }).items[0]!

  it('0과 누락된 금액을 구분하고 대표 공고를 그대로 전달한다', () => {
    expect(toHousingComplexCardData(complex)).toMatchObject({
      complexId: '17', depositMin: 0, depositMax: null, exclusiveAreaMin: 0,
      rentalTypeLabel: '행복주택',
      representativeAnnouncement: { announcementId: '201', applicationStatus: 'APPLYING', dDay: 0 },
    })
  })

  it('누락된 식별 정보는 공고문 확인으로 표시하고 없는 공고를 만들지 않는다', () => {
    expect(toHousingComplexCardData({ ...complex, name: null, regionName: null,
      agency: null, rentalType: 'UNRECOGNIZED', representativeAnnouncement: null })).toMatchObject({
      name: '공고문 확인', regionName: '공고문 확인', agencyCode: null,
      agencyName: '공고문 확인', rentalTypeLabel: '공고문 확인', representativeAnnouncement: null,
    })
  })

  it('공고의 상태가 없을 때만 UNKNOWN으로 표시한다', () => {
    expect(toHousingComplexCardData({ ...complex, representativeAnnouncement: {
      announcementId: '201', publicationType: null, applicationStatus: null,
      applicationEndAt: null, dDay: null,
    } }).representativeAnnouncement?.applicationStatus).toBe('UNKNOWN')
  })
})
