import { describe, expect, it } from 'vitest'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from '../testing/minimalPublicHousingSnapshot'
import { toHousingAnnouncementDetailData } from '../presentation/announcementDetailPresentation'
import { decodeAnnouncementDetailEnvelope, PublicHousingContractError } from './publicHousingContract'
import { toAnnouncementDetail } from './publicHousingMapper'

const schedule = {
  scheduleId: 71, housingComplexId: 17, complexName: '서울가람', supplyRank: '2순위',
  state: 'CONDITIONAL', condition: '1순위 미달 시', startDate: '2026-09-29', endDate: '2026-09-30',
  startTime: '09:00:00', endTime: '18:00:00', sourceUrl: 'https://example.com/notice.pdf', sourcePage: 2,
}

function decode(applicationSchedules: unknown) {
  return decodeAnnouncementDetailEnvelope({ data: {
    ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT.announcementDetails[0], applicationSchedules,
  } })
}

describe('공고의 단지·순위별 접수 일정', () => {
  it('ID를 정규화하고 조건과 공식 시각·근거를 화면까지 전달한다', () => {
    const data = toHousingAnnouncementDetailData(toAnnouncementDetail(decode([schedule])))
    expect(data.applicationSchedules).toEqual([{ ...schedule, scheduleId: '71', housingComplexId: '17' }])
  })

  it('단지가 null인 공통 일정과 시각 미제공을 그대로 보존한다', () => {
    const data = toHousingAnnouncementDetailData(toAnnouncementDetail(decode([
      { ...schedule, housingComplexId: null, complexName: null, startTime: null, endTime: null },
    ])))
    expect(data.applicationSchedules?.[0]).toMatchObject({ housingComplexId: null, startTime: null, endTime: null })
  })

  it.each([
    { scheduleId: -1 }, { housingComplexId: 1.5 }, { startDate: '내일' },
    { startTime: '25:00:00' }, { sourcePage: 0 },
  ])('잘못된 일정 필드를 계약 경계에서 거부한다: %s', (overrides) => {
    expect(() => decode([{ ...schedule, ...overrides }])).toThrow(PublicHousingContractError)
  })

  it('이전 API가 새 필드를 생략하면 기존 일정 화면을 유지한다', () => {
    const data = toHousingAnnouncementDetailData(toAnnouncementDetail(decodeAnnouncementDetailEnvelope({
      data: MINIMAL_PUBLIC_HOUSING_SNAPSHOT.announcementDetails[0],
    })))
    expect(data.applicationSchedules).toEqual([])
    expect(data.schedules.length).toBeGreaterThan(0)
  })
})
