import { describe, expect, it } from 'vitest'
import type {
  AnnouncementDetail,
  RawAnnouncementDetail,
} from '../model/publicHousing.ts'
import {
  groupAnnouncementSupplyRows,
  toHousingAnnouncementDetailData,
} from './announcementDetailPresentation.ts'

describe('toHousingAnnouncementDetailData', () => {
  it('공고 상세 DTO의 코드와 중첩 데이터를 시안 B 표시 모델로 변환한다', () => {
    const result = toHousingAnnouncementDetailData(announcementDetail())

    expect(result).toMatchObject({
      agencyCode: 'LH',
      agencyName: '한국토지주택공사',
      applicationStatusLabel: '접수중',
      publicationTypeLabel: '정정공고',
      recruitmentTypeLabel: '예비입주자 모집',
      rentalTypeLabel: '행복주택',
    })
    expect(result.schedules[0]).toMatchObject({
      type: 'DOCUMENT_SUBMISSION',
      typeLabel: '서류 제출',
    })
    expect(result.receptionPlaces[0]?.methodLabel).toBe('온라인')
    expect(result.attachments[0]?.fileTypeLabel).toBe('공고문')
    expect(result.supplyRows[0]?.supplyTypeLabel).toBe('신규공급')
    expect(result).not.toHaveProperty('competition')
    expect(result).not.toHaveProperty('predictedCompetitionRate')
  })

  it('알 수 없는 코드와 null은 공고문 확인으로 바꾼다', () => {
    const source = announcementDetail()
    const result = toHousingAnnouncementDetailData(announcementDetail({
      applicationStatus: null,
      publicationType: 'NEW_CODE',
      recruitmentType: null,
      rentalType: 'NEW_CODE',
      schedules: [{ ...source.schedules[0]!, type: null }],
      attachments: [{ ...source.attachments[0]!, fileType: null }],
      receptionPlaces: [{ ...source.receptionPlaces[0]!, method: null }],
      supplyRows: [{ ...source.supplyRows[0]!, supplyType: null }],
    }))

    expect(result).toMatchObject({
      applicationStatusLabel: '공고문 확인',
      publicationTypeLabel: '공고문 확인',
      recruitmentTypeLabel: '공고문 확인',
      rentalTypeLabel: '공고문 확인',
    })
    expect(result.schedules[0]?.typeLabel).toBe('공고문 확인')
    expect(result.attachments[0]?.fileTypeLabel).toBe('공고문 확인')
    expect(result.receptionPlaces[0]?.methodLabel).toBe('공고문 확인')
    expect(result.supplyRows[0]?.supplyTypeLabel).toBe('공고문 확인')
  })
})

describe('groupAnnouncementSupplyRows', () => {
  it('같은 단지의 행을 묶고 0을 유지하며 공급 세대수를 합산한다', () => {
    const data = toHousingAnnouncementDetailData(announcementDetail())
    const secondRow = {
      ...data.supplyRows[0]!,
      supplyRowId: '402',
      totalSupplyHouseholdCount: 0,
    }

    const groups = groupAnnouncementSupplyRows([data.supplyRows[0]!, secondRow])

    expect(groups).toHaveLength(1)
    expect(groups[0]).toMatchObject({
      complexId: '101',
      name: '새솔마을',
      supplyHouseholdCount: 12,
    })
    expect(groups[0]?.rows).toHaveLength(2)
  })

  it('공급 세대수가 전부 null이면 합계도 null로 유지한다', () => {
    const data = toHousingAnnouncementDetailData(announcementDetail())
    const row = { ...data.supplyRows[0]!, totalSupplyHouseholdCount: null }

    expect(groupAnnouncementSupplyRows([row])[0]?.supplyHouseholdCount).toBeNull()
  })
})

function announcementDetail(
  changes: Partial<AnnouncementDetail> = {},
): AnnouncementDetail {
  return {
    agency: { code: 'LH', name: '한국토지주택공사' },
    announcementId: '201',
    applicationEndAt: '2026-08-30',
    applicationStartAt: '2026-08-28',
    applicationStatus: 'APPLYING',
    attachments: [{
      attachmentId: '601',
      fileName: '공고문.pdf',
      fileType: 'ANNOUNCEMENT',
      fileUrl: 'https://example.com/notice.pdf',
    }],
    competition: { actualRate: 2.4, predictedRate: 3.1 },
    correctionOrCancellationReason: '접수 일정 정정',
    dDay: 2,
    documentLinkUrl: 'https://example.com/notice',
    publicationType: 'CORRECTION',
    publishedAt: '2026-08-20',
    raw: {} as RawAnnouncementDetail,
    receptionPlaces: [{
      address: null,
      method: 'ONLINE',
      name: 'LH청약플러스',
      phoneNumber: null,
      url: 'https://example.com/apply',
    }],
    recruitmentType: 'WAITLIST',
    regionNames: ['경기 성남시'],
    rentalType: 'HAPPY_HOUSING',
    schedules: [{
      endAt: '2026-09-02T18:00:00',
      name: null,
      scheduleId: '501',
      startAt: '2026-09-01T09:00:00',
      type: 'DOCUMENT_SUBMISSION',
    }],
    supplyComplexCount: 1,
    supplyHouseholdCount: 12,
    supplyRows: [{
      complex: {
        address: '경기 성남시 수정구',
        complexId: '101',
        name: '새솔마을',
        overviewImageUrl: null,
        totalHouseholdCount: 100,
      },
      housingType: {
        exclusiveArea: 36.2,
        floorPlan3dImageUrl: null,
        floorPlanImageUrl: null,
        housingTypeId: '301',
        name: '36A',
        supplyArea: 50.1,
      },
      occupancyExpectedYearMonth: '202611',
      sourceComplexName: '새솔마을',
      sourceHousingTypeName: '36A',
      supplyRowId: '401',
      supplyType: 'NEW',
      targets: [{
        applicationCondition: '무주택 세대구성원',
        convertibleDeposit: null,
        deposit: 32000000,
        monthlyRent: 128000,
        priority: '1순위',
        supplyHouseholdCount: 12,
        supplyTargetId: '701',
        target: '청년',
        waitlistCount: 30,
      }],
      totalSupplyHouseholdCount: 12,
    }],
    targets: ['청년'],
    title: '성남 행복주택 예비입주자 모집',
    viewCount: 0,
    winnerAnnouncementAt: '2026-09-10',
    ...changes,
  }
}
