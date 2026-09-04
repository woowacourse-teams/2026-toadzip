import { describe, expect, it } from 'vitest'
import type {
  AnnouncementListItem,
  RawAnnouncementListItem,
} from '../model/publicHousing.ts'
import { toHousingAnnouncementCardData } from './announcementPresentation.ts'

describe('toHousingAnnouncementCardData', () => {
  it('공고 목록의 확정 표시 필드만 카드 모델로 변환한다', () => {
    const result = toHousingAnnouncementCardData(announcement())

    expect(result).toEqual({
      agencyLabel: 'LH',
      announcementId: '201',
      applicationEndAt: '2026-08-30',
      applicationStartAt: '2026-08-28',
      applicationStatus: 'APPLYING',
      dDay: 2,
      recruitmentTypeLabel: '예비입주자',
      regionNames: ['서울특별시 중구'],
      rentalTypeLabel: '행복주택',
      supplyHouseholdCount: 0,
      title: '행복주택 모집 공고',
      viewCount: 0,
    })
    expect(result).not.toHaveProperty('supplyComplexCount')
    expect(result).not.toHaveProperty('predictedCompetitionRate')
  })

  it('알 수 없는 코드와 null은 raw code 대신 null로 전달한다', () => {
    const result = toHousingAnnouncementCardData(announcement({
      agency: null,
      recruitmentType: 'NEW_CODE',
      rentalType: null,
    }))

    expect(result).toMatchObject({
      agencyLabel: null,
      recruitmentTypeLabel: null,
      rentalTypeLabel: null,
    })
  })
})

function announcement(
  changes: Partial<AnnouncementListItem> = {},
): AnnouncementListItem {
  const raw = {} as RawAnnouncementListItem
  return {
    actualCompetitionRate: null,
    agency: { code: 'LH', name: '한국토지주택공사' },
    announcementId: '201',
    applicationEndAt: '2026-08-30',
    applicationStartAt: '2026-08-28',
    applicationStatus: 'APPLYING',
    dDay: 2,
    predictedCompetitionRate: null,
    publicationType: 'ORIGINAL',
    publishedAt: '2026-08-20',
    raw,
    recruitmentType: 'WAITLIST',
    regionNames: ['서울특별시 중구'],
    rentalType: 'HAPPY_HOUSING',
    supplyComplexCount: 1,
    supplyHouseholdCount: 0,
    thumbnailImageUrl: null,
    title: '행복주택 모집 공고',
    viewCount: 0,
    ...changes,
  }
}
