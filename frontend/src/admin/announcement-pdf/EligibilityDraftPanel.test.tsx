import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EligibilityDraftPanel } from './EligibilityDraftPanel'
import { checkTarget, itemTarget, moveToDirectCheck, requirementWarnings, ruleText, unjudgedReason, type Content, type RuleSet } from './eligibilityDraftApi'
import { readExtractedEligibility } from './extractedEligibility'

const api = vi.hoisted(() => ({ load: vi.fn(), create: vi.fn(), apply: vi.fn(), preview: vi.fn() }))
vi.mock('./eligibilityDraftApi', async original => ({
  ...(await original<typeof import('./eligibilityDraftApi')>()),
  loadEligibility: api.load, createEligibilityDraft: api.create, applyEligibility: api.apply, previewEligibilityDraft: api.preview,
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
const live = (value: Content = content('REVIEWED'), revision = 1): RuleSet => ({ id: 7, revision, status: 'PUBLISHED', content: value })
const applied = (value: Content) => ({ ruleSet: live(value, 2), published: true })

afterEach(() => vi.clearAllMocks())

describe('moveToDirectCheck', () => {
  it('다른 조건이 쓰는 대체 안내와 사람이 쓴 안내는 지우지 않는다', () => {
    const base = content()
    const manual = { id: 'manual', kind: 'REQUIRED_CHECK', title: '직접 쓴 안내', body: '확인', items: [], targets: [{ scope: 'ANNOUNCEMENT', pathId: null, supplyUnitId: null }], evidenceIds: ['ev-1'] }
    const moved = moveToDirectCheck({ ...base, guidances: [...base.guidances, manual] }, 'path-1', 'path-1-1')

    expect(moved.guidances.map(guidance => guidance.id)).toEqual(['check-path-1', 'manual', 'direct-path-1'])
  })

  it('조건을 지우지 않고 종류를 앞에 붙여 그 경로의 필수 확인 자격 항목으로 옮기고, 쓰지 않게 된 대체 안내는 뺀다', () => {
    const moved = moveToDirectCheck(content(), 'path-1', 'path-1-2')

    expect(moved.paths[0].checks.map(check => check.id)).toEqual(['path-1-1'])
    expect(moved.guidances).toEqual([{ id: 'direct-path-1', kind: 'REQUIRED_QUALIFICATION', title: '공고문에서 확인해야 하는 신청자격',
      body: expect.any(String), items: ['소득: 월평균소득 100% 이하'], targets: [{ scope: 'PATH', pathId: 'path-1', supplyUnitId: null }], evidenceIds: ['ev-1'] }])
  })
})

describe('ruleText', () => {
  it('조건을 운영자가 읽는 문장으로 보인다', () => {
    expect(ruleText(age)).toBe('만 19~39세')
    expect(ruleText(income)).toBe('세대 월평균소득 기준표 #1 100% 이하(1인 +20%p)')
    expect(ruleText({ ...age, operator: 'MONEY_CEILING', field: 'FAMILY_TOTAL_ASSETS', minimum: null, maximum: 345_000_000 })).toBe('가족 총자산 34,500만원 이하')
    expect(ruleText({ ...age, operator: 'EQUALS', field: 'FAMILY_OWNS_HOUSE', expected: 'false', minimum: null, maximum: null })).toBe('본인 포함 가족 주택 없음')
  })

  it('가산 대안처럼 묶음 안의 묶음은 괄호로 감싸고 필드 코드 대신 이름을 쓴다', () => {
    const equals = (field: string, expected: string) => ({ ...age, operator: 'EQUALS', field, expected, minimum: null, maximum: null })
    const couple = { ...age, operator: 'ALL', field: null, minimum: null, maximum: null,
      children: [equals('FAMILY_TYPE', 'COUPLE_OR_ENGAGED'), equals('HAS_CHILDREN_OR_PREGNANCY', 'true')] }
    const assets = { ...age, operator: 'MONEY_CEILING', field: 'FAMILY_TOTAL_ASSETS', minimum: null, maximum: 417_000_000 }
    expect(ruleText({ ...age, operator: 'ANY', field: null, minimum: null, maximum: null, children: [assets, equals('FAMILY_TYPE', 'SINGLE_PARENT'), couple] }))
      .toBe('가족 총자산 41,700만원 이하 또는 가족 형태 한부모 또는 (가족 형태 부부·예비부부 그리고 자녀가 있거나 임신 중)')
  })

  it('출산자녀 가산 단계는 자녀 수 코드·상한 없는 값(1800) 대신 몇 명일 때 어떤 기준인지로 읽는다', () => {
    const assets = (maximum: number) => ({ ...age, operator: 'MONEY_CEILING', field: 'FAMILY_TOTAL_ASSETS', minimum: null, maximum })
    const youngest = { ...age, field: 'YOUNGEST_CHILD', minimum: 0, maximum: 3 }
    const count = (minimum: number, maximum: number) => ({ ...age, operator: 'NUMBER_RANGE', field: 'CHILDREN_COUNT_INCLUDING_FETUS', minimum, maximum })
    const tier = (minimum: number, maximum: number, money: number) => ({ ...age, operator: 'ALL', field: null, minimum: null, maximum: null,
      children: [youngest, count(minimum, maximum), assets(money)] })
    const text = ruleText({ ...age, operator: 'ANY', field: null, minimum: null, maximum: null,
      children: [assets(345_000_000), tier(1, 1, 379_500_000), tier(2, 1800, 414_000_000)] })
    expect(text).toBe('가족 총자산 34,500만원 이하 또는 (막내 만 3세 이하 자녀(태아 포함) 1명이면 가족 총자산 37,950만원 이하)'
      + ' 또는 (막내 만 3세 이하 자녀(태아 포함) 2명 이상이면 가족 총자산 41,400만원 이하)')
    expect(text).not.toMatch(/CHILDREN_COUNT|1800/)
    expect(ruleText(count(2, 1800))).toBe('자녀 수(태아 포함) 2명 이상')
  })
})

describe('requirementWarnings', () => {
  const warning = (quoted: string, kind = '나이') => `신청자격 요건 '${quoted}'(${kind}): 숫자 확인 필요. 모델이 적은 문구와 원문 p.3 글에 대조했지만 이 숫자가 맞는지 확정하지 못했고, 그대로 판정에 씁니다(문구에 없는 숫자(19)).`

  it('경고를 같은 요건 문구에만 붙이고, 그 문구를 품은 다른 조건에는 붙이지 않는다', () => {
    expect(requirementWarnings({ label: '만 19세 이상' }, [warning('만 19세 이상')])).toHaveLength(1)
    expect(requirementWarnings({ label: '만 19세 이상 39세 이하' }, [warning('만 19세 이상')])).toEqual([])
  })

  it('자산과 자동차처럼 같은 문구를 쓰는 요건은 경고를 그 종류의 조건에만 붙인다', () => {
    const text = '총자산 34,500만원, 자동차 3,803만원 이하'
    const assets = { label: text, kind: '자산' }
    const car = { label: text, kind: '자동차가액' }
    const carWarning = warning(text, '자동차가액')
    expect(requirementWarnings(car, [carWarning])).toHaveLength(1)
    expect(requirementWarnings(assets, [carWarning])).toEqual([])
    // 종류를 모르는 곳(종류 없는 목록 문구)과 종류가 없는 이전 워커 경고는 문구만으로 맞춘다.
    expect(requirementWarnings({ label: text }, [carWarning])).toHaveLength(1)
    expect(requirementWarnings(assets, [`신청자격 요건 '${text}': 숫자 확인 필요. 이전 경고`])).toEqual(['숫자 확인 필요. 이전 경고'])
  })

  it('판정 조건과 목록 문구에서 경고를 맞출 요건 종류를 읽는다', () => {
    const assets = { ...age, operator: 'MONEY_CEILING', field: 'FAMILY_TOTAL_ASSETS', minimum: null, maximum: 345_000_000 }
    const withBonus = { ...age, operator: 'ANY', field: null, minimum: null, maximum: null, children: [assets, { ...assets, maximum: 459_000_000 }] }
    expect(checkTarget({ id: 'c', label: '총자산', rule: withBonus, appliesWhen: null, fallbackGuidanceId: null, evidenceIds: [] })).toEqual({ label: '총자산', kind: '자산' })
    expect(itemTarget('자산: 총자산 34,500만원 이하')).toEqual({ label: '총자산 34,500만원 이하', kind: '자산' })
    expect(itemTarget('그 밖의 경우: 4,542만원 이하')).toEqual({ label: '그 밖의 경우: 4,542만원 이하' })
  })

  it('40자에서 잘린 문구는 앞부분으로 맞추고, 가산 알림에는 경고를 붙이지 않는다(같은 요건의 조건에 이미 붙는다)', () => {
    const long = '‘23년 3월 28일 이후 출생한 자녀가 1명 45,900만원 이하 4,996만원 이하'
    expect(requirementWarnings({ label: long }, [warning(`${long.slice(0, 40)}…`)])).toHaveLength(1)
    const bonus = itemTarget(`출산자녀 가산 대상은 자산 기준이 더 높아요(기본 기준: 그 밖의 경우 - 41,700만원 이하). 가산 기준은 공고문에서 확인해 주세요.`)
    expect(requirementWarnings(bonus, [warning('그 밖의 경우 - 41,700만원 이하', '자산')])).toEqual([])
  })

  it('판정에 쓰지 않는 항목의 이유에는 판정에 쓴다고 말하지 않는다', () => {
    const [reason] = requirementWarnings({ label: '만 19세 이상' }, [warning('만 19세 이상')])
    expect(unjudgedReason(reason)).toBe('숫자 확인 필요. 모델이 적은 문구와 원문 p.3 글에 대조했지만 이 숫자가 맞는지 확정하지 못했어요. 이 항목은 판정에 쓰지 않아요(문구에 없는 숫자(19)).')
    expect(unjudgedReason('숫자 확인 필요. 원문과 맞는지 확인하지 못한 숫자를 그대로 판정에 씁니다(문구에 없는 숫자(40)).')).not.toContain('판정에 씁니다')
  })
})

describe('EligibilityDraftPanel', () => {
  it('보정 적용 뒤에는 누르지 않아도 최신 PDF 추출로 신청자격을 만들어 반영하고, 검토·공개 버튼 없이 판정에서 빼기도 곧바로 반영한다', async () => {
    api.load.mockResolvedValue(null)
    api.create.mockResolvedValue({ ruleSet: live(), published: true, notices: ['신청자격 경로가 없는 공급대상: 고령자. 공고문을 확인해 주세요.'] })
    api.apply.mockImplementation(async (_id: string, _current: RuleSet, value: Content) => applied(value))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} onEvidence={vi.fn()} autoCreate />)

    expect(await screen.findByText('만 19~39세')).toBeInTheDocument()
    expect(api.create).toHaveBeenCalledWith('12', 'a'.repeat(64), null)
    expect(screen.getByText('신청자격을 사용자 화면에 반영했어요.')).toBeInTheDocument()
    expect(screen.getByText('사용자 화면에 반영됨 · 기준일 2026-09-30')).toBeInTheDocument()
    expect(screen.getByText('신청 단위: 인천석남 21A (모집)')).toBeInTheDocument()
    expect(screen.getByText('신청자격 경로가 없는 공급대상: 고령자. 공고문을 확인해 주세요.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '공개' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '검토 완료로 표시' })).not.toBeInTheDocument()

    fireEvent.click(screen.getAllByRole('button', { name: '판정에서 빼기' })[1])
    expect(await screen.findByText('판정에서 뺐어요. 사용자 화면에 반영했어요.')).toBeInTheDocument()
    expect(api.apply).toHaveBeenCalledWith('12', expect.objectContaining({ status: 'PUBLISHED', revision: 1 }), expect.anything())
    const saved: Content = api.apply.mock.calls[0][2]
    expect(saved.paths[0].checks.map(check => check.label)).toEqual(['만 19세 이상 39세 이하'])
    expect(await screen.findByText('소득: 월평균소득 100% 이하')).toBeInTheDocument()
    // 다음 빼기는 방금 반영한 버전으로 한다(이전 revision으로 409가 나지 않게).
    fireEvent.click(screen.getByRole('button', { name: '판정에서 빼기' }))
    await waitFor(() => expect(api.apply).toHaveBeenCalledTimes(2))
    expect(api.apply.mock.calls[1][1]).toEqual(expect.objectContaining({ revision: 2 }))
  })

  it('PDF 창이 없는 곳(적용 결과)에서는 근거 버튼을 두지 않는다', async () => {
    api.load.mockResolvedValue(draft())
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} />)

    expect(await screen.findByText('만 19~39세')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /근거 p\./ })).not.toBeInTheDocument()
    // 추출 경고를 받지 않은 화면에서는 숫자를 대조했다고 말하지 않고, 원문에서 문구를 찾았다는 것만 보인다.
    expect(screen.getAllByText('확인: 원문 p.3에서 이 문구를 찾았어요.')).toHaveLength(2)
    expect(screen.queryByText(/숫자를 문구·쪽 글과 대조/)).not.toBeInTheDocument()
  })

  it('공고가 공개 전이라 저장만 됐으면 사용자 화면에 아직 반영되지 않았다고 알리고, 빼기를 공개하지 못한 이유도 보인다', async () => {
    api.load.mockResolvedValue(null)
    api.create.mockResolvedValue({ ruleSet: draft(content('REVIEWED')), published: false,
      notices: ['공고가 아직 공개 전이라 신청자격은 저장만 했습니다. 공고를 공개한 뒤 보정을 다시 적용하면 사용자에게 반영됩니다.'] })
    api.apply.mockImplementation(async (_id: string, _current: RuleSet, value: Content) => ({ ruleSet: draft(value, 2), published: false, reason: '요청이 충돌했습니다.' }))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} autoCreate />)

    expect(await screen.findByText('신청자격을 저장했어요.')).toBeInTheDocument()
    expect(screen.getByText(/아직 사용자 화면에 반영되지 않았어요/)).toBeInTheDocument()
    expect(screen.getByText(/공고가 아직 공개 전이라 신청자격은 저장만 했습니다/)).toBeInTheDocument()

    fireEvent.click(screen.getAllByRole('button', { name: '판정에서 빼기' })[0])
    expect(await screen.findByText(/사용자 화면에는 아직 반영하지 못했어요\. 요청이 충돌했습니다\./)).toBeInTheDocument()
  })

  it('보정 적용 뒤에는 이미 있는 신청자격도 적용한 공급정보로 다시 만들고, 초안이면 그 버전으로 덮어쓴다', async () => {
    api.load.mockResolvedValueOnce(live())
    api.create.mockResolvedValue({ ruleSet: live(content('REVIEWED'), 2), published: true, notices: [] })
    const first = render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} autoCreate />)
    expect(await screen.findByText('신청자격을 사용자 화면에 반영했어요.')).toBeInTheDocument()
    expect(api.create).toHaveBeenLastCalledWith('12', 'a'.repeat(64), expect.objectContaining({ status: 'PUBLISHED' }))
    first.unmount()

    api.load.mockResolvedValueOnce(draft(content(), 4))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} autoCreate />)
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(2))
    expect(api.create).toHaveBeenLastCalledWith('12', 'a'.repeat(64), expect.objectContaining({ status: 'DRAFT', revision: 4 }))
  })

  it('자동 반영이 실패하면 이유를 보이고 다시 반영할 수 있게 둔다', async () => {
    api.load.mockResolvedValueOnce(null)
    api.create.mockRejectedValueOnce(new Error('공급대상과 이어지는 신청자격 경로가 없어 초안을 만들 수 없습니다.'))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} autoCreate />)

    expect(await screen.findByRole('alert')).toHaveTextContent('공급대상과 이어지는 신청자격 경로가 없어')
    expect(api.create).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: '신청자격 반영하기' })).toBeEnabled()
  })

  it('열린 PDF가 없으면 보정 적용 뒤에도 자동으로 반영하지 않는다', async () => {
    api.load.mockResolvedValue(null)
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={null} autoCreate />)

    expect(await screen.findByText('PDF를 열면 신청자격을 반영할 수 있어요.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: '신청자격 반영하기' })).toBeDisabled())
    expect(api.create).not.toHaveBeenCalled()
  })

  it('첫 조회가 끝나기 전에는 반영할 수 없다', async () => {
    let finish: (value: RuleSet | null) => void = () => {}
    api.load.mockReturnValue(new Promise(resolve => { finish = resolve }))
    api.create.mockRejectedValue(new Error('공급대상과 이어지는 신청자격 경로가 없어 초안을 만들 수 없습니다.'))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} autoCreate />)

    expect(screen.getByRole('button', { name: '신청자격 반영하기' })).toBeDisabled()
    finish(null)
    await waitFor(() => expect(screen.getByRole('button', { name: '신청자격 반영하기' })).toBeEnabled())
  })

  it('보정 적용 전에는 서버가 바꾼 판정 조건과 판정에 넣지 않은 자격을 근거와 함께 읽기 전용으로 보인다', async () => {
    api.load.mockResolvedValue(null)
    const base = content()
    // 조건의 대체 안내에도 항목이 있어야 그것이 목록으로 새지 않는지 확인할 수 있다.
    const fallback = { ...base.guidances[0], items: ['소득 기준 직접 확인'] }
    api.preview.mockResolvedValue({ ...base, guidances: [fallback, { id: 'list-path-1', kind: 'REQUIRED_CHECK',
      title: '공고문에서 확인할 자격', body: '확인', items: ['입주 전까지 주택청약종합저축 가입'],
      targets: [{ scope: 'PATH', pathId: 'path-1', supplyUnitId: null }], evidenceIds: ['ev-1'] }] })
    const onEvidence = vi.fn()
    const extracted = readExtractedEligibility(JSON.stringify({ data: { eligibility: { common: { requirements: [], qualifications: [] }, targets: [], exclusions: [] } }, evidence: [] }))
    const numberWarning = "신청자격 요건 '만 19세 이상 39세 이하'(나이): 숫자 확인 필요. 모델이 적은 문구와 원문 p.3 글에 대조했지만 이 숫자가 맞는지 확정하지 못했고, 그대로 판정에 씁니다(문구에 없는 숫자(40))."
    const listWarning = "신청자격 요건 '입주 전까지 주택청약종합저축 가입': 근거 쪽에서 이 문구를 찾지 못해 판정에 쓰지 않고 목록으로만 남깁니다."
    const looseWarning = "신청자격 요건 '공고문에 없는 요건': 근거 쪽에서 이 문구를 찾지 못해 판정에 쓰지 않고 목록으로만 남깁니다."
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} onEvidence={onEvidence} extracted={extracted}
      warnings={[numberWarning, listWarning, looseWarning]} />)

    const youth = within(await screen.findByRole('region', { name: '청년' }))
    // 원문과 맞지 않는 숫자도 판정에 쓰므로, 그 경고를 해당 조건 곁에 어떻게 확인했는지와 함께 보인다.
    const [ageItem, incomeItem] = youth.getAllByRole('listitem')
    expect(ageItem).toHaveTextContent('확인 필요 원문 p.3에서 이 문구를 찾았어요. 숫자 확인 필요. 모델이 적은 문구와 원문 p.3 글에 대조했지만 이 숫자가 맞는지 확정하지 못했고, 그대로 판정에 씁니다(문구에 없는 숫자(40)).')
    expect(incomeItem).toHaveTextContent('확인: 원문 p.3에서 이 문구를 찾았어요. 이번 PDF 추출에서 숫자를 문구·쪽 글과 대조했고 맞지 않는 숫자는 없었어요.')
    expect(youth.getByText('입주 전까지 주택청약종합저축 가입').closest('li')).toHaveTextContent('어떻게 확인했나: 근거 쪽에서 이 문구를 찾지 못해')
    // 어느 조건에도 붙지 않은 경고만 위 목록에 남는다.
    const top = screen.getByText('PDF에서 읽으며 확인할 점').closest('div')!
    expect(top).toHaveTextContent(looseWarning)
    expect(top).not.toHaveTextContent('문구에 없는 숫자(40)')
    expect(api.preview).toHaveBeenCalledWith('12', 'a'.repeat(64), expect.anything())
    expect(youth.getByText('만 19~39세')).toBeInTheDocument()
    expect(youth.getByText('신청 전에 확인할 자격 (판정에 넣지 않음):', { exact: false })).toBeInTheDocument()
    expect(youth.getByText('입주 전까지 주택청약종합저축 가입')).toBeInTheDocument()
    // 조건의 대체 안내(check-path-1)는 판정을 막지 않는 목록으로 보이지 않는다.
    expect(youth.queryByText('소득 기준 직접 확인')).not.toBeInTheDocument()
    expect(screen.queryByText(/신청 단위/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '판정에서 빼기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /반영/ })).not.toBeInTheDocument()
  })

  it('어느 조건에도 붙이지 못한 숫자 경고가 있으면 숫자가 모두 맞았다고 말하지 않고 위 목록을 함께 보라고 한다', async () => {
    // 이전 추출로 만든 초안처럼 조건 문구와 이번 추출의 요건 문구가 다르면 경고를 조건 곁에 붙이지 못한다.
    api.load.mockResolvedValue(null)
    api.preview.mockResolvedValue(content())
    const extracted = readExtractedEligibility(JSON.stringify({ data: { eligibility: { common: { requirements: [], qualifications: [] }, targets: [], exclusions: [] } }, evidence: [] }))
    const other = "신청자격 요건 '그 밖의 경우 - 4,542만원 이하': 숫자 확인 필요. 모델이 적은 문구와 원문 p.3 글에 대조했지만 이 숫자가 맞는지 확정하지 못했고, 그대로 판정에 씁니다(문구의 여러 금액 중 이 요건의 금액인지 확인하지 못한 금액(4,542만원))."
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} extracted={extracted} warnings={[other]} />)

    const youth = within(await screen.findByRole('region', { name: '청년' }))
    const [, incomeItem] = youth.getAllByRole('listitem')
    expect(incomeItem).toHaveTextContent("확인: 원문 p.3에서 이 문구를 찾았어요. 이번 PDF 추출 경고 중 조건에 붙이지 못한 것이 있어요. 위 'PDF에서 읽으며 확인할 점'에 이 조건 숫자가 있는지 함께 보세요.")
    expect(incomeItem).not.toHaveTextContent('맞지 않는 숫자는 없었어요')
    expect(screen.getByText('PDF에서 읽으며 확인할 점').closest('div')).toHaveTextContent('4,542만원')
  })

  it('자산과 자동차가 같은 원문 문구를 써도 자동차가액 경고는 자동차 조건에만, 판정에서 빼면 옮긴 항목에만 붙는다', async () => {
    const text = '그 밖의 경우 - 41,700만원 이하 4,542만원 이하'
    const money = (field: string, maximum: number) => ({ ...age, operator: 'MONEY_CEILING', field, minimum: null, maximum })
    const base = content()
    const value: Content = { ...base,
      paths: [{ ...base.paths[0], checks: [base.paths[0].checks[0],
        { id: 'path-1-3', label: text, rule: money('FAMILY_CAR_VALUE', 45_420_000), appliesWhen: null, fallbackGuidanceId: null, evidenceIds: ['ev-1'] }] }],
      // 숫자를 읽지 못해 목록으로 간 자산 요건이다. 서버는 종류를 앞에 붙인다.
      guidances: [{ id: 'list-path-1', kind: 'REQUIRED_CHECK', title: '공고문에서 확인할 자격', body: '확인', items: [`자산: ${text}`],
        targets: [{ scope: 'PATH', pathId: 'path-1', supplyUnitId: null }], evidenceIds: ['ev-1'] }] }
    api.load.mockResolvedValue(draft(value))
    api.apply.mockImplementation(async (_id: string, _current: RuleSet, saved: Content) => applied(saved))
    const carWarning = `신청자격 요건 '${text}'(자동차가액): 숫자 확인 필요. 모델이 적은 문구와 원문 p.3 글에 대조했지만 이 숫자가 맞는지 확정하지 못했고, 그대로 판정에 씁니다(4,542만원).`
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} warnings={[carWarning]} />)

    const youth = within(await screen.findByRole('region', { name: '청년' }))
    const carItem = youth.getByText('가족 차량가액 4,542만원 이하', { exact: false }).closest('li')!
    expect(carItem).toHaveTextContent('확인 필요')
    expect(youth.getByText(`자산: ${text}`).closest('li')).not.toHaveTextContent('숫자 확인 필요')
    expect(screen.queryByText('PDF에서 읽으며 확인할 점')).not.toBeInTheDocument()

    fireEvent.click(within(carItem).getByRole('button', { name: '판정에서 빼기' }))
    const moved = await youth.findByText(`자동차가액: ${text}`)
    expect(moved.closest('li')).toHaveTextContent('어떻게 확인했나: 숫자 확인 필요')
    expect(youth.getByText(`자산: ${text}`).closest('li')).not.toHaveTextContent('숫자 확인 필요')
  })

  it('보정 적용 전 미리보기를 받지 못하면 PDF에서 이미 뽑은 신청자격을 그대로 보이고, 적용할 때 반영된다고 알린다', async () => {
    api.load.mockResolvedValue(null)
    api.preview.mockRejectedValue(new Error('화면의 PDF와 최신 추출 결과가 다릅니다.'))
    const onEvidence = vi.fn()
    const extracted = readExtractedEligibility(JSON.stringify({
      data: { eligibility: {
        common: { requirements: [{ kind: 'NO_HOUSE', asWritten: '무주택세대구성원' }], qualifications: [], summary: null },
        targets: [{ target: '청년', requirements: [{ kind: 'AGE', asWritten: '만 19세 이상 39세 이하' }], qualifications: [{ exactName: '주거급여 수급자' }] }],
        exclusions: [{ title: '재당첨 제한', body: '공공임대 당첨자' }],
      } },
      evidence: [{ path: 'eligibility.targets[0].requirements[0]', page: 5, quote: '만 19세 이상 39세 이하' }],
    }))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} onEvidence={onEvidence} extracted={extracted} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('화면의 PDF와 최신 추출 결과가 다릅니다.')
    expect(screen.getByText('보정 내용을 적용하면 아래 내용과 저장된 공급정보로 신청자격이 만들어져 사용자 화면에 곧바로 반영돼요.')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: '모든 신청자에게 적용' })).getByText('무주택세대구성원')).toBeInTheDocument()
    const youth = within(screen.getByRole('region', { name: '청년' }))
    expect(youth.getByText('만 19세 이상 39세 이하')).toBeInTheDocument()
    expect(youth.getByText('해당하는지 공고문에서 확인할 자격: 주거급여 수급자')).toBeInTheDocument()
    fireEvent.click(youth.getByRole('button', { name: '근거 p.5 열기' }))
    expect(onEvidence).toHaveBeenCalledWith({ path: 'eligibility.targets[0].requirements[0]', page: 5, quote: '만 19세 이상 39세 이하' })
    expect(screen.getByText('공공임대 당첨자')).toBeInTheDocument()
    // 추출은 이미 끝났다. 다시 추출하는 것처럼 보이는 버튼을 두지 않는다.
    expect(screen.queryByRole('button', { name: /반영/ })).not.toBeInTheDocument()
    expect(api.create).not.toHaveBeenCalled()
  })

  it('추출 결과에 신청자격이 없으면 없다고 알린다', async () => {
    api.load.mockResolvedValue(null)
    expect(readExtractedEligibility(JSON.stringify({ data: { eligibility: null }, evidence: [] }))).toBeNull()
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={'a'.repeat(64)} onEvidence={vi.fn()} extracted={null} />)

    expect(await screen.findByText('이 PDF 추출 결과에는 신청자격이 없어요. PDF를 다시 추출해 주세요.')).toBeInTheDocument()
  })
})
