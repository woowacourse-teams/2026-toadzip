import { describe, expect, it, vi } from 'vitest'
import { createEligibilityRepository } from './eligibilityRepository'

const ref = { announcementId: '5', title: 'D05', rentalType: null, applicationStatus: 'APPLYING', targets: [] }
const content = {
  metadata: { referenceDate: '2026-10-16' }, documents: [], evidences: [], qualifications: [], guidances: [],
  commonChecks: [], paths: [], supplyUnits: [],
}
const current = {
  requestedAnnouncementId: 5, announcementId: 5, availability: 'AVAILABLE', freshness: 'CURRENT',
  profileVersion: 2, evaluatedProfileVersion: 2, evaluatedAt: '2026-11-09T00:00:00Z', staleReasons: [],
  content, eligibleOptionIdsInRegion: ['ordinary'], selectedRegion: null,
  result: { status: 'ELIGIBLE', headline: '지원할 수 있어요', referenceDate: '2026-10-16', commonChecks: [],
    paths: [{ pathId: 'ordinary', checks: [] }], options: [{ optionId: 'ordinary', supplyUnitId: 'unit',
      unitLabel: '사천벌리1 26형', pathId: 'ordinary', pathLabel: '일반입주자 신청자격', regionCodes: ['48240'],
      recruitmentStatus: 'RECRUITING', status: 'ELIGIBLE', message: '지원할 수 있어요', missingInputMessage: null,
      missingInputs: [], requiredQualificationIds: [], directCheckGuidanceIds: [], exclusionGuidanceIds: [] }] },
}
const response = (data: unknown) => new Response(JSON.stringify({ data }), { status: 200 })

describe('실제 신청자격 API 연결', () => {
  it('최신 eligibility API의 실제 모집 선택지를 사용한다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(current))
    const view = await createEligibilityRepository(fetcher).loadComparison(ref)
    expect(String(fetcher.mock.calls[0][0])).toMatch(/\/announcements\/5\/eligibility$/)
    expect(view.types[0]).toMatchObject({ key: 'ordinary', status: 'ELIGIBLE', units: '사천벌리1 26형' })
  })
  it('처리 중 응답을 미구조화나 입력 부족으로 바꾸지 않는다', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response({ ...current, freshness: 'RECHECKING', result: null, eligibleOptionIdsInRegion: [] }))
    const view = await createEligibilityRepository(fetcher).loadComparison(ref)
    expect(view).toMatchObject({ availability: 'AVAILABLE', freshness: 'RECHECKING', types: [] })
  })
})
