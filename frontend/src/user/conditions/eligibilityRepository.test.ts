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

it('지역에 맞는 선택지가 없으면 공고 전체가 ELIGIBLE이어도 지역 필터에서 제외한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response({ ...current, eligibleOptionIdsInRegion: [] }))
  const result = await createEligibilityRepository(fetcher).loadListResults([{ ...ref, region: '41465' }])
  expect(String(fetcher.mock.calls[0][0])).toMatch(/eligibility\?region=41465$/)
  expect(result.get('5')).toMatchObject({ best: { status: 'ELIGIBLE' }, eligibleInRegion: false })
})

it('수치 미충족의 기준·방향·입력값을 보존하며 다른 정보 부족과 구분한다', async () => {
  const checks = [
    { id: 'assets', label: '총자산', evidenceIds: [], reading: { status: 'NOT_SATISFIED', missingInputs: [], comparedValues: { field: 'FAMILY_TOTAL_ASSETS', providedValue: 300000000, comparisonLimit: 245000000, unit: 'KRW' } } },
    { id: 'income', label: '세대 소득', evidenceIds: [], reading: { status: 'INPUT_NEEDED', missingInputs: ['HOUSEHOLD_MONTHLY_INCOME'], comparedValues: {} } },
  ]
  const data = { ...current, result: { ...current.result, status: 'INELIGIBLE', paths: [{ pathId: 'ordinary', checks }], options: [{ ...current.result.options[0], status: 'INELIGIBLE', missingInputs: ['HOUSEHOLD_MONTHLY_INCOME'] }] } }
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response(data))
  const view = await createEligibilityRepository(fetcher).loadComparison(ref)
  expect(view.types[0].status).toBe('INELIGIBLE')
  expect(view.types[0].checks[0]).toMatchObject({ mine: '3억 원', criterion: '2억 4,500만 원 이하', status: 'NOT_SATISFIED' })
  expect(view.types[0].checks[1]).toMatchObject({ status: 'INPUT_NEEDED', fields: ['HOUSEHOLD_MONTHLY_INCOME'] })
})

it('법정 필수 자격과 신청 제한 안내를 별도로 보존한다', async () => {
  const data = { ...current, content: { ...content,
    qualifications: [{ id: 'single-parent', exactName: '지원대상 한부모가족', conditionsAsWritten: '해당 법정 자격 확인', evidenceIds: [] }],
    guidances: [{ id: 'history', kind: 'APPLICATION_EXCLUSION', title: '불법전대 제한', body: '제한 이력 확인', items: [], evidenceIds: [] }] },
    result: { ...current.result, status: 'UNDETERMINED', options: [{ ...current.result.options[0], status: 'UNDETERMINED', requiredQualificationIds: ['single-parent'], exclusionGuidanceIds: ['history'] }] } }
  const view = await createEligibilityRepository(vi.fn<typeof fetch>().mockResolvedValue(response(data))).loadComparison(ref)
  expect(view.types[0].missingInputs).toEqual([])
  expect(view.types[0].qualifications?.[0].title).toBe('지원대상 한부모가족')
  expect(view.types[0].exclusions?.[0].title).toBe('불법전대 제한')
})

it('판정을 막지 않는 신청 전 확인 안내를 지원 가능 선택지에도 보인다', async () => {
  const data = { ...current, content: { ...content,
    guidances: [{ id: 'list-path-1', kind: 'REQUIRED_CHECK', title: '공고문에서 확인할 자격', body: '공고문 원문에서 확인',
      items: ['입주 전까지 주택청약종합저축 가입'], evidenceIds: [] }] },
    result: { ...current.result, options: [{ ...current.result.options[0], reminderGuidanceIds: ['list-path-1'] }] } }
  const view = await createEligibilityRepository(vi.fn<typeof fetch>().mockResolvedValue(response(data))).loadComparison(ref)
  expect(view.types[0].status).toBe('ELIGIBLE')
  expect(view.types[0].guidances).toEqual([expect.objectContaining({ kind: 'REQUIRED_CHECK', title: '공고문에서 확인할 자격',
    body: '공고문 원문에서 확인\n입주 전까지 주택청약종합저축 가입' })])
})

it('가산 대상처럼 기준이 그대로 적용되지 않은 조건은 기본 기준과 확인 안내를 함께 알린다', async () => {
  const checks = [{ id: 'income', label: '세대 소득', evidenceIds: [],
    reading: { status: 'DIRECT_CHECK_REQUIRED', missingInputs: [], comparedValues: { reason: 'NOT_APPLIED',
      field: 'HOUSEHOLD_MONTHLY_INCOME', providedValue: 4000000, comparisonLimit: 3519762, operator: 'LTE', unit: 'KRW_PER_MONTH' } } }]
  const data = { ...current, content: { ...content, paths: [{ id: 'ordinary', checks: [{ id: 'income',
    rule: { operator: 'INCOME_CEILING', field: 'HOUSEHOLD_MONTHLY_INCOME' } }] }] },
    result: { ...current.result, paths: [{ pathId: 'ordinary', checks }] } }
  const view = await createEligibilityRepository(vi.fn<typeof fetch>().mockResolvedValue(response(data))).loadComparison(ref)
  expect(view.types[0].status).toBe('ELIGIBLE')
  expect(view.types[0].checks.at(-1)).toMatchObject({ status: 'DIRECT_CHECK_REQUIRED',
    criterion: expect.stringMatching(/^기본 기준 월 .+ 이하 · 입력값 월 400만 원 · 가족 상황에 따라 다른 기준이 적용돼요/) })
})

it('필수 응답 상태가 잘못되면 지원 가능으로 추정하지 않고 실패한다', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response({ ...current, freshness: 'UNRECOGNIZED' }))
  await expect(createEligibilityRepository(fetcher).loadComparison(ref)).rejects.toThrow()
})
