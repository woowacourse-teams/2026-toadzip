import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConditionsContext, type ConditionsContextValue } from '../../user/conditions/ConditionsContext'
import { EMPTY_PROFILE, type CheckView, type ComparisonView, type ConditionsRepository, type TypeView } from '../../user/conditions/conditionsModel'
import { EligibilityComparisonSection, type EligibilitySectionDetail } from './EligibilityComparisonSection'

afterEach(() => vi.useRealTimers())

const detail = (extra: Partial<EligibilitySectionDetail> = {}): EligibilitySectionDetail => ({
  announcementId: '1', title: '회현 행복주택', rentalTypeLabel: '행복주택', applicationStatus: 'APPLYING', targets: [], ...extra,
})

const check = (id: string, status: CheckView['status'], extra: Partial<CheckView> = {}): CheckView => ({
  id, label: id, status, mine: null, criterion: '기준값', basis: null, evidence: null, fields: ['APPLICANT_ASSETS'], missingReason: null, ...extra,
})
const type = (key: string, label: string, status: TypeView['status'], checks: CheckView[], extra: Partial<TypeView> = {}): TypeView => ({
  key, label, coverage: 'GENERAL', status, units: '', checks, guidances: [], ...extra,
})

const happyView = (types: TypeView[], extra: Partial<ComparisonView> = {}): ComparisonView => ({
  announcementId: '1', availability: 'AVAILABLE', referenceDate: '2026-11-06', requiredQualification: null, types, ...extra,
})

const youthNeed = type('youth', '청년', 'INPUT_NEEDED', [
  check('나이', 'SATISFIED', { mine: '만 30세', criterion: '만 19~39세', fields: ['BIRTH_DATE'] }),
  check('본인 소득', 'SATISFIED', { mine: '월 230만 원', criterion: '월 256만 4,238원 이하', basis: '2026 중위소득 100%·1인', fields: ['APPLICANT_MONTHLY_INCOME'] }),
  check('본인 자산', 'INPUT_NEEDED', { criterion: '2억 5,400만 원 이하', missingReason: 'LATER', fields: ['APPLICANT_ASSETS'] }),
])
const newlywedNot = type('newlywed', '신혼부부', 'NOT_MATCHED', [
  check('가족 형태', 'NOT_SATISFIED', { mine: '미혼', criterion: '부부·예비부부', fields: ['FAMILY_TYPE'] }),
  check('무주택 세대', 'INPUT_NEEDED', { missingReason: 'FAMILY_SKIPPED', fields: ['FAMILY_OWNS_HOUSE'] }),
])
const seniorMatched = type('senior', '고령자', 'MATCHED', [check('나이', 'SATISFIED', { mine: '만 70세', fields: ['BIRTH_DATE'] })])

function context(repository: Partial<ConditionsRepository>, extra: Partial<ConditionsContextValue> = {}): ConditionsContextValue {
  return {
    mode: 'member',
    repository: { loadProfile: vi.fn(), saveProfile: vi.fn(), loadListResults: vi.fn(), loadComparison: vi.fn(), ...repository } as ConditionsRepository,
    stored: { version: 1, savedAt: '2026-11-06T00:00:00.000Z', profile: { ...EMPTY_PROFILE, facts: { BIRTH_DATE: { state: 'PROVIDED', value: '1996-03-02' } } } },
    hasProfile: true, memberDraft: null, revision: 1, lastSave: null,
    openInput: vi.fn(), restartInput: vi.fn(), openNudge: vi.fn(), clearProfile: vi.fn(), notify: vi.fn(),
    ...extra,
  }
}

function renderSection(value: ConditionsContextValue, item = detail(), onOpenEvidence = vi.fn()) {
  const view = render(<ConditionsContext value={value}><EligibilityComparisonSection detail={item} onOpenEvidence={onOpenEvidence} /></ConditionsContext>)
  return { ...view, onOpenEvidence, rerenderWith: (next: ConditionsContextValue, nextItem = item) => view.rerender(<ConditionsContext value={next}><EligibilityComparisonSection detail={nextItem} onOpenEvidence={onOpenEvidence} /></ConditionsContext>) }
}

describe('EligibilityComparisonSection', () => {
  it('invites guests with the type chips of the announcement', async () => {
    const value = context({ loadComparison: vi.fn(async () => happyView([youthNeed, newlywedNot, seniorMatched])) }, { mode: 'guest', hasProfile: false, stored: null })
    renderSection(value)
    expect(await screen.findByText('이 공고, 나도 신청할 수 있을까?')).toBeInTheDocument()
    expect(screen.getByText('신청 유형별 기본 조건을 비교해 드려요')).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: '이 공고의 신청 유형' })).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['청년?', '신혼부부?', '고령자?'])
    fireEvent.click(screen.getByRole('button', { name: '지금 확인해 보기' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'full', step: 1, origin: 'detail' }, { announcementId: '1', resume: true })
  })

  it('offers to resume a member draft', async () => {
    const value = context({ loadComparison: vi.fn(async () => happyView([youthNeed])) }, { hasProfile: false, memberDraft: { step: 2 } })
    renderSection(value)
    expect(await screen.findByText('작성하던 내 조건이 있어요')).toBeInTheDocument()
    expect(screen.getByText('2단계까지 입력했어요')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '처음부터 다시 입력' }))
    expect(value.restartInput).toHaveBeenCalledWith('detail', '1')
  })

  it('hides the invite card for a closed announcement', async () => {
    const load = vi.fn(async () => happyView([youthNeed]))
    renderSection(context({ loadComparison: load }, { mode: 'guest', hasProfile: false, stored: null }), detail({ applicationStatus: 'CLOSED' }))
    await act(async () => {})
    expect(screen.queryByText('이 공고, 나도 신청할 수 있을까?')).not.toBeInTheDocument()
  })

  it('orders type tabs with matched types first and keeps status text for screen readers', async () => {
    renderSection(context({ loadComparison: vi.fn(async () => happyView([youthNeed, newlywedNot, seniorMatched])) }))
    const tabs = await screen.findAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual(['고령자, 충족', '청년, 입력 필요', '신혼부부, 미충족'])
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(tabs[0], { key: 'ArrowRight' })
    expect(screen.getByRole('tab', { name: '청년, 입력 필요' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: '청년, 입력 필요' })).toHaveFocus()
  })

  it('shows the verdict band and rows with my value and the criterion', async () => {
    renderSection(context({ loadComparison: vi.fn(async () => happyView([youthNeed])) }))
    expect(await screen.findByText('입력하면 확인할 수 있어요')).toBeInTheDocument()
    expect(screen.getByText('거의 다 왔어요. 1개만 채우면 바로 알려드릴게요.')).toBeInTheDocument()
    const rows = within(screen.getByRole('list', { name: '청년 항목별 비교' })).getAllByRole('listitem')
    expect(within(rows[0]).getByRole('img', { name: '판정 불가' })).toBeInTheDocument()
    expect(rows[0]).toHaveTextContent('기준 2억 5,400만 원 이하')
    expect(rows[0]).toHaveTextContent('나중에 입력하기로 한 항목 · 입력하면 바로 확인돼요')
    expect(rows[2]).toHaveTextContent('기준 월 256만 4,238원 이하 (2026 중위소득 100%·1인)')
    expect(rows[2]).toHaveTextContent('월 230만 원')
    expect(screen.getByText(/비교 기준일 2026.11.06 · 11월 6일 저장한 내 조건 기준/)).toBeInTheDocument()
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
  })

  it('opens a partial input from a missing item and highlights rows that changed after saving', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const load = vi.fn(async () => happyView([youthNeed]))
    const value = context({ loadComparison: load })
    const { rerenderWith } = renderSection(value)
    fireEvent.click(await screen.findByRole('button', { name: '본인 자산 입력하기' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'partial', fields: ['APPLICANT_ASSETS'], origin: 'detail' }, { announcementId: '1' })
    const matched = type('youth', '청년', 'MATCHED', youthNeed.checks.map((item) => item.id === '본인 자산' ? { ...item, status: 'SATISFIED' as const, mine: '3,000만 원' } : item))
    load.mockImplementation(async () => happyView([matched]))
    rerenderWith({ ...value, revision: 2, lastSave: { revision: 2, origin: 'detail', kind: 'partial', fields: ['APPLICANT_ASSETS'], announcementId: '1' } })
    expect(await screen.findByText('방금 확인했어요')).toBeInTheDocument()
    expect(value.notify).toHaveBeenCalledWith('본인 자산을 저장했어요 · 청년: 충족')
    await act(async () => { vi.advanceTimersByTime(6100) })
    expect(screen.queryByText('방금 확인했어요')).not.toBeInTheDocument()
  })

  it('does not highlight rows after a full save', async () => {
    const load = vi.fn(async () => happyView([youthNeed]))
    const value = context({ loadComparison: load })
    const { rerenderWith } = renderSection(value)
    await screen.findByText('입력하면 확인할 수 있어요')
    const matched = type('youth', '청년', 'MATCHED', youthNeed.checks.map((item) => ({ ...item, status: 'SATISFIED' as const, mine: '값' })))
    load.mockImplementation(async () => happyView([matched]))
    rerenderWith({ ...value, revision: 2, lastSave: { revision: 2, origin: 'detail', kind: 'full', fields: [], announcementId: '1' } })
    expect(await screen.findByText('기본 조건을 충족해요')).toBeInTheDocument()
    expect(screen.queryByText('방금 확인했어요')).not.toBeInTheDocument()
  })

  it('opens the full family step to fix a family type mismatch', async () => {
    const value = context({ loadComparison: vi.fn(async () => happyView([newlywedNot])) })
    renderSection(value)
    fireEvent.click(await screen.findByRole('button', { name: '가족 형태 수정, 지금 값 미혼' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'full', step: 3, origin: 'detail' }, { announcementId: '1' })
    fireEvent.click(screen.getByRole('button', { name: '내 정보 수정하기' }))
    expect(value.openInput).toHaveBeenCalledTimes(2)
    expect(screen.getByText('다른 조건이 맞지 않아 입력해도 결과는 같아요')).toBeInTheDocument()
  })

  it('shows the required qualification box before a single common-only card', async () => {
    const common = type('permanentCommon', '공통 조건', 'MATCHED', [check('나이', 'SATISFIED', { mine: '만 30세' })], { coverage: 'COMMON_ONLY' })
    renderSection(context({ loadComparison: vi.fn(async () => happyView([common], { requiredQualification: { title: '별도 신청자격이 필요해요', body: '본문', items: ['생계·의료급여 수급자'], evidence: null } })) }))
    expect(await screen.findByText('별도 신청자격이 필요해요')).toBeInTheDocument()
    expect(screen.getByText('공통 조건은 맞아요')).toBeInTheDocument()
    expect(screen.queryByText(/지원 가능/)).not.toBeInTheDocument()
  })

  it('opens evidence pages from rows and guidances', async () => {
    const withEvidence = type('youth', '청년', 'MATCHED', [check('나이', 'SATISFIED', { mine: '만 30세', evidence: { page: 5, location: null, fileName: null } })],
      { guidances: [{ kind: 'PROCEDURE', title: '중복 신청 불가', body: '한 유형만', evidence: { page: 3, location: null, fileName: null } }] })
    const { onOpenEvidence } = renderSection(context({ loadComparison: vi.fn(async () => happyView([withEvidence])) }))
    fireEvent.click(await screen.findByRole('button', { name: '근거 공고문 5쪽 열기' }))
    expect(onOpenEvidence).toHaveBeenCalledWith({ evidence: { page: 5, location: null, fileName: null }, label: '나이' })
    expect(screen.getByText('신청 절차')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '근거 공고문 3쪽 열기' }))
    expect(onOpenEvidence).toHaveBeenLastCalledWith({ evidence: { page: 3, location: null, fileName: null }, label: '중복 신청 불가' })
  })

  it('shows the preparing notice for NOT_STRUCTURED', async () => {
    renderSection(context({ loadComparison: vi.fn(async () => happyView([], { availability: 'NOT_STRUCTURED' })) }, { mode: 'guest', hasProfile: false }))
    expect(await screen.findByText('자격 비교 준비 중')).toBeInTheDocument()
    expect(screen.getByText('신청 자격은 공고문에서 확인해 주세요.')).toBeInTheDocument()
  })

  it('ignores a late comparison for a previous announcement', async () => {
    let resolveFirst: (view: ComparisonView) => void = () => {}
    const load = vi.fn((ref: { announcementId: string }) => ref.announcementId === '1'
      ? new Promise<ComparisonView>((resolve) => { resolveFirst = resolve })
      : Promise.resolve(happyView([seniorMatched], { announcementId: '2' })))
    const value = context({ loadComparison: load as ConditionsRepository['loadComparison'] })
    const { rerenderWith } = renderSection(value)
    rerenderWith(value, detail({ announcementId: '2' }))
    expect(await screen.findByText('기본 조건을 충족해요')).toBeInTheDocument()
    await act(async () => { resolveFirst(happyView([newlywedNot])) })
    expect(screen.queryByText('맞지 않는 조건이 있어요')).not.toBeInTheDocument()
    expect(screen.getByText('기본 조건을 충족해요')).toBeInTheDocument()
  })

  it('shows a retry for a failed comparison', async () => {
    const load = vi.fn().mockRejectedValueOnce(new Error('x')).mockResolvedValue(happyView([seniorMatched]))
    renderSection(context({ loadComparison: load }))
    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('기본 조건을 충족해요')).toBeInTheDocument()
  })

  it('asks a member without saved conditions once per visit on a comparable announcement', async () => {
    sessionStorage.clear(); localStorage.clear()
    const value = context({ loadComparison: vi.fn(async () => happyView([youthNeed])) }, { hasProfile: false })
    const { unmount } = renderSection(value)
    await screen.findByText('이 공고, 나도 신청할 수 있을까?')
    expect(value.openNudge).toHaveBeenCalledWith('detail', { announcementId: '1' })
    unmount()
    const again = context({ loadComparison: vi.fn(async () => happyView([youthNeed])) }, { hasProfile: false })
    renderSection(again)
    await screen.findByText('이 공고, 나도 신청할 수 있을까?')
    expect(again.openNudge).not.toHaveBeenCalled()
  })

  it('does not ask while a guest draft from the login is still being saved', async () => {
    sessionStorage.clear(); localStorage.clear()
    sessionStorage.setItem('toadzip:condition-handoff', JSON.stringify({ draft: { answers: {}, familyDetails: null, provinceCode: null }, origin: 'detail', announcementId: '1', expiresAt: Date.now() + 60_000 }))
    const value = context({ loadComparison: vi.fn(async () => happyView([youthNeed])) }, { hasProfile: false })
    renderSection(value)
    await screen.findByText('이 공고, 나도 신청할 수 있을까?')
    expect(value.openNudge).not.toHaveBeenCalled()
    sessionStorage.clear()
  })

  it('does not ask during the snooze or for guests', async () => {
    sessionStorage.clear()
    localStorage.setItem('toadzip:condition-nudge-snooze-until', String(Date.now() + 60_000))
    const member = context({ loadComparison: vi.fn(async () => happyView([youthNeed])) }, { hasProfile: false })
    const { unmount } = renderSection(member)
    await screen.findByText('이 공고, 나도 신청할 수 있을까?')
    expect(member.openNudge).not.toHaveBeenCalled()
    unmount()
    localStorage.clear()
    const guest = context({ loadComparison: vi.fn(async () => happyView([youthNeed])) }, { mode: 'guest', hasProfile: false, stored: null })
    renderSection(guest)
    await screen.findByText('이 공고, 나도 신청할 수 있을까?')
    expect(guest.openNudge).not.toHaveBeenCalled()
  })

  it('renders nothing without the conditions feature', () => {
    const { container } = render(<EligibilityComparisonSection detail={detail()} onOpenEvidence={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })
})
