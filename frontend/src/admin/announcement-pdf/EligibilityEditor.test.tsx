import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EligibilityDraftPanel } from './EligibilityDraftPanel'
import type { Content, Rule, RuleSet } from './eligibilityDraftApi'
import { addDirectItem, editProblems, removeGuidanceItem, updateRule } from './eligibilityEdit'

const api = vi.hoisted(() => ({ load: vi.fn(), apply: vi.fn() }))
vi.mock('./eligibilityDraftApi', async original => ({
  ...(await original<typeof import('./eligibilityDraftApi')>()),
  loadEligibility: api.load, applyEligibility: api.apply,
}))

const rule = (values: Partial<Rule>): Rule => ({ operator: 'AGE_RANGE', field: 'BIRTH_DATE', expected: null, minimum: null, maximum: null, regions: [], incomeLimit: null, children: [], ...values })
const age = rule({ minimum: 19, maximum: 39 })
const assets = rule({ operator: 'MONEY_CEILING', field: 'FAMILY_TOTAL_ASSETS', maximum: 345_000_000 })

function content(): Content {
  return {
    metadata: { referenceDate: '2026-09-30', reviewStatus: 'REVIEWED', scopeNote: '범위', classificationEvidenceId: 'ev-1' },
    evidences: [{ id: 'ev-1', firstPage: 3, description: null }],
    supplyUnits: [{ id: 'unit-1', label: '충주연수2 45.2600' }],
    paths: [
      { id: 'path-1', label: '청년', qualificationIds: [], checks: [{ id: 'path-1-1', label: '만 19세 이상 39세 이하', rule: age, appliesWhen: null, fallbackGuidanceId: null, evidenceIds: ['ev-1'] }] },
      { id: 'path-2', label: '마. 북한이탈주민 · 북한이탈주민', qualificationIds: ['qual-1'], checks: [] },
    ],
    options: [
      { id: 'path-1-unit-1', supplyUnitId: 'unit-1', pathId: 'path-1', recruitmentStatus: 'RECRUITING' },
      { id: 'path-2-unit-1', supplyUnitId: 'unit-1', pathId: 'path-2', recruitmentStatus: 'RECRUITING' },
    ],
    guidances: [{ id: 'direct-common', kind: 'REQUIRED_QUALIFICATION', title: '확인', body: '확인', items: ['주택청약종합저축 가입'],
      targets: [{ scope: 'ANNOUNCEMENT', pathId: null, supplyUnitId: null }], evidenceIds: ['ev-1'] }],
    commonChecks: [{ id: 'common-1', label: '총자산 34,500만원 이하', rule: assets, appliesWhen: null, fallbackGuidanceId: null, evidenceIds: ['ev-1'] }],
    qualifications: [{ id: 'qual-1', exactName: '북한이탈주민', conditionsAsWritten: '북한이탈주민', evidenceIds: ['ev-1'] }],
  }
}
const renamed = (value: Content, label: string): Content => ({ ...value, paths: value.paths.map((path, index) => index === 0 ? { ...path, label } : path) })
const live = (value: Content = content(), revision = 1): RuleSet => ({ id: 7, revision, status: 'PUBLISHED', content: value })

afterEach(() => vi.clearAllMocks())

describe('신청자격 편집 내용', () => {
  it('공고문에서 확인할 자격의 마지막 항목을 지우면 안내도 지워 판정을 막지 않게 한다', () => {
    expect(removeGuidanceItem(content(), 'direct-common', 0).guidances).toEqual([])
    const two = addDirectItem(content(), 'common', '세대주')
    expect(removeGuidanceItem(two, 'direct-common', 0).guidances[0].items).toEqual(['세대주'])
  })

  it('대체 안내로 쓰이는 안내는 항목을 다 지워도 남긴다', () => {
    const base = content()
    const withFallback: Content = { ...base,
      guidances: [...base.guidances, { id: 'check-common', kind: 'REQUIRED_CHECK', title: '한계', body: '확인', items: ['소득 가산'], targets: [{ scope: 'ANNOUNCEMENT', pathId: null, supplyUnitId: null }], evidenceIds: ['ev-1'] }],
      commonChecks: [{ ...base.commonChecks[0], fallbackGuidanceId: 'check-common' }] }

    expect(removeGuidanceItem(withFallback, 'check-common', 0).guidances.map(item => item.id)).toEqual(['direct-common', 'check-common'])
  })

  it('최소가 최대보다 크거나 이름을 비우면 저장 전에 알린다', () => {
    const wrong = updateRule(content(), 'path-1', 'path-1-1', [], { minimum: 40 })
    expect(editProblems(wrong)).toEqual(['청년: 만 40~39세 — 최소가 최대보다 커요.'])
    expect(editProblems({ ...content(), paths: content().paths.map(path => ({ ...path, label: ' ' })) })).toContain('신청 유형 이름을 비울 수 없어요.')
  })
})

describe('신청자격 직접 수정 화면', () => {
  async function openEditor(props: Partial<Parameters<typeof EligibilityDraftPanel>[0]> = {}) {
    api.load.mockResolvedValue(live())
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={null} pdfActions={false} {...props} />)
    fireEvent.click(await screen.findByRole('button', { name: '직접 수정' }))
    return screen.getByRole('region', { name: '신청자격 직접 수정' })
  }

  it('관리자 공고 상세에서는 PDF 반영 버튼 없이 직접 수정만 두고, 탭 이름·조건 숫자·자격 문구를 고쳐 곧바로 반영한다', async () => {
    api.apply.mockImplementation(async (_id: string, _current: RuleSet, value: Content) => ({ ruleSet: live(value, 2), published: true }))
    api.load.mockResolvedValue(live())
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={null} pdfActions={false} />)
    await screen.findByRole('button', { name: '직접 수정' })
    expect(screen.queryByRole('button', { name: 'PDF 추출로 다시 반영' })).toBeNull()
    expect(screen.queryByText('PDF를 열면 신청자격을 반영할 수 있어요.')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '직접 수정' }))
    const editor = screen.getByRole('region', { name: '신청자격 직접 수정' })
    const save = within(editor).getByRole('button', { name: '저장하고 반영' })
    expect(save).toBeDisabled()
    fireEvent.change(within(editor).getByDisplayValue('마. 북한이탈주민 · 북한이탈주민'), { target: { value: '북한이탈주민' } })
    fireEvent.change(within(editor).getByLabelText('최대 만 나이'), { target: { value: '34' } })
    fireEvent.change(within(editor).getByLabelText('가족 총자산 상한'), { target: { value: '36100' } })
    fireEvent.change(within(editor).getByLabelText('공고문에서 확인할 자격 1'), { target: { value: '주택청약종합저축 가입자' } })
    fireEvent.change(within(editor).getByLabelText('자격 이름'), { target: { value: ' 북한이탈주민(보호대상자) ' } })
    fireEvent.click(save)

    await screen.findByText('고친 신청자격을 사용자 화면에 반영했어요.')
    expect(api.apply).toHaveBeenCalledWith('12', expect.objectContaining({ revision: 1 }), expect.anything())
    const saved: Content = api.apply.mock.calls[0][2]
    expect(saved.paths.map(path => path.label)).toEqual(['청년', '북한이탈주민'])
    expect(saved.paths[0].checks[0].rule).toEqual(expect.objectContaining({ minimum: 19, maximum: 34 }))
    expect(saved.commonChecks[0].rule.maximum).toBe(361_000_000)
    expect(saved.guidances[0].items).toEqual(['주택청약종합저축 가입자'])
    expect(saved.qualifications.map(item => item.exactName)).toEqual(['북한이탈주민(보호대상자)'])
    // 저장 뒤에는 고친 내용으로 다시 보인다.
    expect(screen.queryByRole('region', { name: '신청자격 직접 수정' })).toBeNull()
    expect(screen.getByRole('region', { name: '북한이탈주민' })).toBeInTheDocument()
  })

  it('최대 나이를 비우면 상한 없음으로 저장하고, 조건을 더한 뒤 금액을 비워 두면 저장하지 않는다', async () => {
    api.apply.mockImplementation(async (_id: string, _current: RuleSet, value: Content) => ({ ruleSet: live(value, 2), published: true }))
    const editor = await openEditor()
    fireEvent.change(within(editor).getByLabelText('최대 만 나이'), { target: { value: '' } })
    const scope = within(editor).getByDisplayValue('청년').closest('fieldset')!
    fireEvent.change(within(scope as HTMLElement).getByLabelText('더할 조건'), { target: { value: 'applicant-assets' } })
    fireEvent.click(within(scope as HTMLElement).getByRole('button', { name: '조건 더하기' }))

    expect(within(editor).getByText(/숫자를 채워 주세요/)).toBeInTheDocument()
    expect(within(editor).getByRole('button', { name: '저장하고 반영' })).toBeDisabled()
    fireEvent.change(within(editor).getByLabelText('본인 자산 상한'), { target: { value: '25400' } })
    fireEvent.click(within(editor).getByRole('button', { name: '저장하고 반영' }))

    await waitFor(() => expect(api.apply).toHaveBeenCalled())
    const saved: Content = api.apply.mock.calls[0][2]
    expect(saved.paths[0].checks[0].rule.maximum).toBe(150)
    // 더한 조건은 서버가 요구하는 근거를 공고 분류 근거로 잇고, 이름은 조건 문장으로 채운다. 원래 조건의 원문 이름은 그대로다.
    expect(saved.paths[0].checks[1]).toEqual(expect.objectContaining({ id: 'manual-path-1-1', label: '본인 자산 25,400만원 이하', evidenceIds: ['ev-1'],
      fallbackGuidanceId: null, rule: expect.objectContaining({ maximum: 254_000_000 }) }))
    expect(saved.paths[0].checks[0].label).toBe('만 19세 이상 39세 이하')
  })

  it('묶음(하나만 충족) 안의 비교는 그 자리만 고쳐 저장한다', async () => {
    api.apply.mockImplementation(async (_id: string, _current: RuleSet, value: Content) => ({ ruleSet: live(value, 2), published: true }))
    const base = content()
    api.load.mockResolvedValue(live({ ...base, commonChecks: [{ ...base.commonChecks[0], rule: rule({ operator: 'ANY', field: null, children: [assets, age] }) }] }))
    render(<EligibilityDraftPanel announcementId="12" pdfSha256={null} pdfActions={false} />)
    fireEvent.click(await screen.findByRole('button', { name: '직접 수정' }))
    const common = screen.getByRole('group', { name: '모든 신청자에게 적용' })
    fireEvent.change(within(common).getByLabelText('최대 만 나이'), { target: { value: '34' } })
    fireEvent.click(screen.getByRole('button', { name: '저장하고 반영' }))

    await waitFor(() => expect(api.apply).toHaveBeenCalled())
    const saved: Content = api.apply.mock.calls[0][2]
    expect(saved.commonChecks[0].rule.children.map(child => child.maximum)).toEqual([345_000_000, 34])
    expect(saved.paths[0].checks[0].rule.maximum).toBe(39)
  })

  it('안내가 없던 신청 유형에 공고문에서 확인할 자격을 더하면 그 유형에만 걸고 근거를 잇는다', async () => {
    api.apply.mockImplementation(async (_id: string, _current: RuleSet, value: Content) => ({ ruleSet: live(value, 2), published: true }))
    const editor = await openEditor()
    const scope = within(editor).getByDisplayValue('마. 북한이탈주민 · 북한이탈주민').closest('fieldset') as HTMLElement
    fireEvent.change(within(scope).getByLabelText('더할 자격 문구'), { target: { value: '북한이탈주민 보호결정을 받은 사람' } })
    fireEvent.click(within(scope).getByRole('button', { name: '자격 더하기' }))
    fireEvent.click(within(editor).getByRole('button', { name: '저장하고 반영' }))

    await waitFor(() => expect(api.apply).toHaveBeenCalled())
    const saved: Content = api.apply.mock.calls[0][2]
    expect(saved.guidances.find(item => item.id === 'direct-path-2')).toEqual(expect.objectContaining({
      kind: 'REQUIRED_QUALIFICATION', items: ['북한이탈주민 보호결정을 받은 사람'],
      targets: [{ scope: 'PATH', pathId: 'path-2', supplyUnitId: null }], evidenceIds: ['ev-1'] }))
    // 다른 범위의 안내는 그대로다.
    expect(saved.guidances.find(item => item.id === 'direct-common')?.items).toEqual(['주택청약종합저축 가입'])
  })

  it('판정에서 빼기는 저장 전까지 화면에서만 옮기고, 공개본을 복사한 뒤 서버가 저장을 거절하면 고치던 내용을 남겨 복사한 초안으로 다시 저장한다', async () => {
    const reason = '아무 조건도 구조화하지 않은 경로를 신청 가능으로 판정할 수 없습니다.'
    api.apply.mockResolvedValueOnce({ ruleSet: { id: 8, revision: 0, status: 'DRAFT', content: content() }, published: false, saved: false, reason })
      .mockImplementationOnce(async (_id: string, _current: RuleSet, value: Content) => ({ ruleSet: live(value, 2), published: true }))
    const editor = await openEditor()
    const scope = within(editor).getByDisplayValue('청년').closest('fieldset') as HTMLElement
    fireEvent.click(within(scope).getByRole('button', { name: '판정에서 빼기' }))
    expect(api.apply).not.toHaveBeenCalled()
    expect(within(scope).getByLabelText('공고문에서 확인할 자격 1')).toHaveValue('나이: 만 19세 이상 39세 이하')

    fireEvent.click(within(editor).getByRole('button', { name: '저장하고 반영' }))

    expect(await within(editor).findByRole('alert')).toHaveTextContent(reason)
    expect(screen.queryByText(/저장했지만/)).toBeNull()
    expect(within(scope).getByLabelText('공고문에서 확인할 자격 1')).toHaveValue('나이: 만 19세 이상 39세 이하')
    fireEvent.click(within(editor).getByRole('button', { name: '저장하고 반영' }))
    await screen.findByText('고친 신청자격을 사용자 화면에 반영했어요.')
    expect(api.apply.mock.calls[1][1]).toEqual(expect.objectContaining({ id: 8, revision: 0, status: 'DRAFT' }))
    expect(api.apply.mock.calls[1][2].paths[0].checks).toEqual([])
  })

  it('복사해 보니 다른 곳에서 바뀐 신청자격이면 고친 내용을 덮어쓰지 않고 새 내용으로 다시 보인다', async () => {
    const changed = renamed(content(), '다른 관리자가 고친 청년')
    api.apply.mockResolvedValueOnce({ ruleSet: { id: 8, revision: 0, status: 'DRAFT', content: changed }, published: false, saved: false, reason: '다른 곳에서 바뀐 신청자격을 불러왔어요. 다시 확인한 뒤 고쳐 주세요.' })
    const editor = await openEditor()
    fireEvent.change(within(editor).getByDisplayValue('청년'), { target: { value: '청년 계층' } })
    fireEvent.click(within(editor).getByRole('button', { name: '저장하고 반영' }))

    expect(await within(editor).findByRole('alert')).toHaveTextContent('다른 곳에서 바뀐 신청자격을 불러왔어요.')
    expect(within(editor).getByDisplayValue('다른 관리자가 고친 청년')).toBeInTheDocument()
    expect(within(editor).getByRole('button', { name: '저장하고 반영' })).toBeDisabled()
  })

  it('저장은 됐지만 공개하지 못하면 그 이유를 알리고 저장한 초안으로 이어 고칠 수 있게 한다', async () => {
    api.apply.mockImplementation(async (_id: string, _current: RuleSet, value: Content) => ({ ruleSet: { id: 8, revision: 1, status: 'DRAFT', content: value }, published: false, saved: true, reason: '공고가 공개 전입니다.' }))
    const editor = await openEditor()
    fireEvent.change(within(editor).getByLabelText('최소 만 나이'), { target: { value: '20' } })
    fireEvent.click(within(editor).getByRole('button', { name: '저장하고 반영' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('고친 내용을 저장했지만 사용자 화면에는 아직 반영하지 못했어요. 공고가 공개 전입니다.')
    fireEvent.click(screen.getByRole('button', { name: '직접 수정' }))
    fireEvent.change(screen.getByLabelText('최소 만 나이'), { target: { value: '21' } })
    fireEvent.click(screen.getByRole('button', { name: '저장하고 반영' }))
    await waitFor(() => expect(api.apply).toHaveBeenCalledTimes(2))
    expect(api.apply.mock.calls[1][1]).toEqual(expect.objectContaining({ id: 8, status: 'DRAFT' }))
  })
})
