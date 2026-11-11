import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EligibilityDraftPanel } from './EligibilityDraftPanel'
import { moveToDirectCheck, ruleText, type Content, type RuleSet } from './eligibilityDraftApi'

const api = vi.hoisted(() => ({ load: vi.fn(), create: vi.fn(), save: vi.fn(), publish: vi.fn() }))
vi.mock('./eligibilityDraftApi', async original => ({
  ...(await original<typeof import('./eligibilityDraftApi')>()),
  loadEligibilityDraft: api.load, createEligibilityDraft: api.create, saveEligibilityDraft: api.save, publishEligibilityDraft: api.publish,
}))

const income = { operator: 'INCOME_CEILING', field: 'HOUSEHOLD_MONTHLY_INCOME', expected: null, minimum: null, maximum: null, regions: [],
  incomeLimit: { standardTableId: 1, percent: 100, sizeExtraPercent: { 1: 20 } }, children: [] }
const age = { operator: 'AGE_RANGE', field: 'BIRTH_DATE', expected: null, minimum: 19, maximum: 39, regions: [], incomeLimit: null, children: [] }

function content(reviewStatus: 'REVIEWING' | 'REVIEWED' = 'REVIEWING'): Content {
  return {
    metadata: { referenceDate: '2026-09-30', reviewStatus, scopeNote: 'AI 추출 초안. 공급대상과 잇지 못한 경로: 고령자' },
    evidences: [{ id: 'ev-1', firstPage: 3, description: '만 19세 이상 39세 이하' }],
    supplyUnits: [{ id: 'unit-1', label: '인천석남 21A' }],
    paths: [{ id: 'path-1', label: '청년', qualificationIds: [], checks: [
      { id: 'path-1-1', label: '만 19세 이상 39세 이하', rule: age, appliesWhen: null, fallbackGuidanceId: null, evidenceIds: ['ev-1'] },
      { id: 'path-1-2', label: '월평균소득 100% 이하', rule: income, appliesWhen: null, fallbackGuidanceId: 'check-path-1', evidenceIds: ['ev-1'] }] }],
    options: [{ id: 'path-1-unit-1', supplyUnitId: 'unit-1', pathId: 'path-1', recruitmentStatus: 'RECRUITING' }],
    guidances: [{ id: 'check-path-1', kind: 'REQUIRED_CHECK', title: '자동 판정 한계 확인', body: '확인', items: [],
      targets: [{ scope: 'PATH', pathId: 'path-1', supplyUnitId: null }], evidenceIds: ['ev-1'] }],
    commonChecks: [], qualifications: [],
  }
}
const draft = (value: Content = content(), revision = 1): RuleSet => ({ id: 7, revision, status: 'DRAFT', content: value })

afterEach(() => vi.clearAllMocks())

describe('moveToDirectCheck', () => {
  it('다른 조건이 쓰는 대체 안내와 사람이 쓴 안내는 지우지 않는다', () => {
    const base = content()
    const manual = { id: 'manual', kind: 'REQUIRED_CHECK', title: '직접 쓴 안내', body: '확인', items: [], targets: [{ scope: 'ANNOUNCEMENT', pathId: null, supplyUnitId: null }], evidenceIds: ['ev-1'] }
    const moved = moveToDirectCheck({ ...base, guidances: [...base.guidances, manual] }, 'path-1', 'path-1-1')

    expect(moved.guidances.map(guidance => guidance.id)).toEqual(['check-path-1', 'manual', 'direct-path-1'])
  })

  it('조건을 지우지 않고 그 경로의 필수 확인 자격 항목으로 옮기고, 쓰지 않게 된 대체 안내는 뺀다', () => {
    const moved = moveToDirectCheck(content(), 'path-1', 'path-1-2')

    expect(moved.paths[0].checks.map(check => check.id)).toEqual(['path-1-1'])
    expect(moved.guidances).toEqual([{ id: 'direct-path-1', kind: 'REQUIRED_QUALIFICATION', title: '공고문에서 직접 확인할 신청자격',
      body: expect.any(String), items: ['월평균소득 100% 이하'], targets: [{ scope: 'PATH', pathId: 'path-1', supplyUnitId: null }], evidenceIds: ['ev-1'] }])
  })
})

describe('ruleText', () => {
  it('조건을 운영자가 읽는 문장으로 보인다', () => {
    expect(ruleText(age)).toBe('만 19~39세')
    expect(ruleText(income)).toBe('세대 월평균소득 기준표 #1 100% 이하(1인 +20%p)')
    expect(ruleText({ ...age, operator: 'MONEY_CEILING', field: 'FAMILY_TOTAL_ASSETS', minimum: null, maximum: 345_000_000 })).toBe('가족 총자산 34,500만원 이하')
    expect(ruleText({ ...age, operator: 'EQUALS', field: 'FAMILY_OWNS_HOUSE', expected: 'false', minimum: null, maximum: null })).toBe('본인 포함 가족 주택 없음')
  })
})

describe('EligibilityDraftPanel', () => {
  it('최신 PDF로 초안을 만들고, 조건을 직접 확인으로 바꿔 저장하며, 검토 완료 전에는 공개할 수 없다', async () => {
    api.load.mockResolvedValue(null)
    api.create.mockResolvedValue({ ruleSet: draft(), notices: ['신청자격 경로가 없는 공급대상: 고령자. 공고문을 확인해 주세요.'] })
    api.save.mockImplementation(async (_id: string, _current: RuleSet, value: Content) => draft(value, 2))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} onEvidence={vi.fn()} />)

    expect(await screen.findByText('아직 신청자격 초안이 없어요.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'AI 추출로 초안 만들기' }))
    expect(await screen.findByText('만 19~39세')).toBeInTheDocument()
    expect(api.create).toHaveBeenCalledWith('12', 'a'.repeat(64), null)
    expect(screen.getByText('신청 단위: 인천석남 21A (모집)')).toBeInTheDocument()
    expect(screen.getByText('신청자격 경로가 없는 공급대상: 고령자. 공고문을 확인해 주세요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '공개' })).toBeDisabled()

    fireEvent.click(screen.getAllByRole('button', { name: '직접 확인으로 바꾸기' })[1])
    await waitFor(() => expect(api.save).toHaveBeenCalledTimes(1))
    const saved: Content = api.save.mock.calls[0][2]
    expect(saved.paths[0].checks.map(check => check.label)).toEqual(['만 19세 이상 39세 이하'])
    expect(await screen.findByText('월평균소득 100% 이하')).toBeInTheDocument()
  })

  it('PDF 창이 없는 곳(적용 결과)에서는 근거 버튼을 두지 않는다', async () => {
    api.load.mockResolvedValue(draft())
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} />)

    expect(await screen.findByText('만 19~39세')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /근거 p\./ })).not.toBeInTheDocument()
  })

  it('공개가 성공하면 초안 다시 읽기가 실패해도 성공으로 보이고 공개를 다시 누를 수 없다', async () => {
    api.load.mockResolvedValueOnce(draft(content('REVIEWED'))).mockRejectedValueOnce(new Error('네트워크 오류'))
    api.publish.mockResolvedValue(draft(content('REVIEWED'), 2))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={null} onEvidence={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: '공개' }))

    expect(await screen.findByText(/공개했어요\./)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '공개' })).not.toBeInTheDocument()
  })

  it('보정 적용 뒤(autoCreate)에는 초안이 없으면 누르지 않아도 최신 PDF 추출로 초안을 만든다', async () => {
    api.load.mockResolvedValue(null)
    api.create.mockResolvedValue({ ruleSet: draft(), notices: ['공급대상 구분이 없는 공급행 1개에는 모든 신청자격 경로를 이었습니다.'] })
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} autoCreate />)

    expect(await screen.findByText('만 19~39세')).toBeInTheDocument()
    expect(api.create).toHaveBeenCalledTimes(1)
    expect(api.create).toHaveBeenCalledWith('12', 'a'.repeat(64), null)
    expect(screen.getByText('공급대상 구분이 없는 공급행 1개에는 모든 신청자격 경로를 이었습니다.')).toBeInTheDocument()
  })

  it('보정 적용 뒤에도 이미 있는 초안은 덮어쓰지 않고, 자동 만들기가 실패하면 이유를 보이고 다시 만들 수 있게 둔다', async () => {
    api.load.mockResolvedValueOnce(draft())
    const first = render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} autoCreate />)
    expect(await screen.findByText('만 19~39세')).toBeInTheDocument()
    expect(api.create).not.toHaveBeenCalled()
    first.unmount()

    api.load.mockResolvedValueOnce(null)
    api.create.mockRejectedValueOnce(new Error('공급대상과 이어지는 신청자격 경로가 없어 초안을 만들 수 없습니다.'))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} autoCreate />)

    expect(await screen.findByRole('alert')).toHaveTextContent('공급대상과 이어지는 신청자격 경로가 없어')
    expect(api.create).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'AI 추출로 초안 만들기' })).toBeEnabled()
  })

  it('열린 PDF가 없으면 보정 적용 뒤에도 초안을 자동으로 만들지 않는다', async () => {
    api.load.mockResolvedValue(null)
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={null} autoCreate />)

    expect(await screen.findByText('PDF를 열면 초안을 만들 수 있어요.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'AI 추출로 초안 만들기' })).toBeDisabled())
    expect(api.create).not.toHaveBeenCalled()
  })

  it('첫 조회가 끝나기 전에는 초안을 만들 수 없다', async () => {
    let finish: (value: RuleSet | null) => void = () => {}
    api.load.mockReturnValue(new Promise(resolve => { finish = resolve }))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} onEvidence={vi.fn()} />)

    expect(screen.getByRole('button', { name: 'AI 추출로 초안 만들기' })).toBeDisabled()
    finish(null)
    await waitFor(() => expect(screen.getByRole('button', { name: 'AI 추출로 초안 만들기' })).toBeEnabled())
  })

  it('검토 완료로 표시한 초안만 공개하고, 공개 뒤에는 초안을 다시 읽는다', async () => {
    api.load.mockResolvedValueOnce(draft()).mockResolvedValueOnce(null)
    api.save.mockImplementation(async (_id: string, _current: RuleSet, value: Content) => draft(value, 2))
    api.publish.mockResolvedValue(draft(content('REVIEWED'), 3))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={null} onEvidence={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: '검토 완료로 표시' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '공개' })).toBeEnabled())
    expect(api.save.mock.calls[0][2].metadata.reviewStatus).toBe('REVIEWED')
    expect(screen.getByRole('button', { name: 'AI 추출로 다시 만들기' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: '공개' }))
    expect(await screen.findByText('공개했어요. 사용자 판정이 곧 다시 계산돼요.')).toBeInTheDocument()
    expect(api.publish).toHaveBeenCalledWith('12', expect.objectContaining({ revision: 2 }))
    // 공개본을 초안처럼 들고 있지 않아 다음 작업이 이전 revision으로 409가 나지 않는다.
    expect(api.load).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('button', { name: '공개' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'AI 추출로 초안 만들기' })).toBeInTheDocument()
  })
})
