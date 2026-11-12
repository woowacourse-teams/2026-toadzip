/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
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
  key, label, status, units: '', checks, guidances: [], ...extra,
})

const happyView = (types: TypeView[], extra: Partial<ComparisonView> = {}): ComparisonView => ({
  announcementId: '1', availability: 'AVAILABLE', referenceDate: '2026-11-06', freshness: 'CURRENT', status: 'ELIGIBLE', profileVersion: 1, evaluatedProfileVersion: 1, eligibleOptionIdsInRegion: ['youth'], scopeNote: null, types, ...extra,
})

const youthNeed = type('youth', '청년', 'UNDETERMINED', [
  check('나이', 'SATISFIED', { mine: '만 30세', criterion: '만 19~39세', fields: ['BIRTH_DATE'] }),
  check('본인 소득', 'SATISFIED', { mine: '월 230만 원', criterion: '월 256만 4,238원 이하', basis: '2026 중위소득 100%·1인', fields: ['APPLICANT_MONTHLY_INCOME'] }),
  check('본인 자산', 'INPUT_NEEDED', { criterion: '2억 5,400만 원 이하', missingReason: 'LATER', fields: ['APPLICANT_ASSETS'] }),
])
const newlywedNot = type('newlywed', '신혼부부', 'INELIGIBLE', [
  check('가족 형태', 'NOT_SATISFIED', { mine: '미혼', criterion: '부부·예비부부', fields: ['FAMILY_TYPE'] }),
  check('무주택 세대', 'INPUT_NEEDED', { missingReason: 'FAMILY_SKIPPED', fields: ['FAMILY_OWNS_HOUSE'] }),
])
const seniorMatched = type('senior', '고령자', 'ELIGIBLE', [check('나이', 'SATISFIED', { mine: '만 70세', fields: ['BIRTH_DATE'] })])

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
    expect(screen.getByText('모집 선택지별 신청자격을 확인해 드려요')).toBeInTheDocument()
    expect(value.repository.loadComparison).not.toHaveBeenCalled()
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
    expect(screen.getByRole('heading', { name: '내 조건 비교' })).toBeInTheDocument()
    expect(screen.getByText('거의 다 왔어요. 1개만 채우면 바로 알려드릴게요.')).toBeInTheDocument()
    const rows = within(screen.getByRole('list', { name: '청년 항목별 비교' })).getAllByRole('listitem')
    expect(within(rows[0]).getByRole('img', { name: '정보 부족' })).toBeInTheDocument()
    expect(rows[0]).toHaveTextContent('기준 2억 5,400만 원 이하')
    expect(within(rows[0]).getByRole('button', { name: '본인 자산 입력하기' })).toBeInTheDocument()
    expect(rows[0]).toHaveTextContent('나중에 입력하기로 한 항목 · 입력하면 바로 확인돼요')
    expect(rows[2]).toHaveTextContent('기준 월 256만 4,238원 이하 (2026 중위소득 100%·1인)')
    // 맞는 항목은 오른쪽에 내 값만 진하게 보인다.
    expect(rows[2].querySelector('b')).toHaveTextContent(/^월 230만 원$/)
    expect(rows[2]).not.toHaveTextContent('내 정보')
    expect(screen.getByText(/공고 기준일 2026.11.06 · 11월 6일 저장한 내 조건 기준/)).toBeInTheDocument()
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
  })

  it('keeps a group condition in one row with one input button that opens every missing item', async () => {
    const group = check('혼인기간 또는 자녀 나이', 'INPUT_NEEDED', { criterion: '혼인 7년 이내 또는 막내 자녀 만 6세 이하',
      fields: ['MARRIAGE_EVENT', 'YOUNGEST_CHILD'], missingReason: 'UNANSWERED' })
    const value = context({ loadComparison: vi.fn(async () => happyView([type('newlywed', '신혼부부', 'UNDETERMINED', [group])])) })
    renderSection(value)
    const list = await screen.findByRole('list', { name: '신혼부부 항목별 비교' })
    const rows = within(list).getAllByRole('listitem')
    expect(rows).toHaveLength(1)
    expect(rows[0]).toHaveTextContent('기준 혼인 7년 이내 또는 막내 자녀 만 6세 이하')
    expect(within(rows[0]).getAllByText(/입력하면 바로 확인돼요/)).toHaveLength(1)
    fireEvent.click(within(rows[0]).getByRole('button', { name: '혼인기간 또는 자녀 나이 입력하기' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'partial', fields: ['MARRIAGE_EVENT', 'YOUNGEST_CHILD'], origin: 'detail' }, { announcementId: '1' })
  })

  it('opens a partial input from a missing item and highlights rows that changed after saving', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const load = vi.fn(async () => happyView([youthNeed]))
    const value = context({ loadComparison: load })
    const { rerenderWith } = renderSection(value)
    fireEvent.click(await screen.findByRole('button', { name: '본인 자산 입력하기' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'partial', fields: ['APPLICANT_ASSETS'], origin: 'detail' }, { announcementId: '1' })
    const matched = type('youth', '청년', 'ELIGIBLE', youthNeed.checks.map((item) => item.id === '본인 자산' ? { ...item, status: 'SATISFIED' as const, mine: '3,000만 원' } : item))
    load.mockImplementation(async () => happyView([matched]))
    rerenderWith({ ...value, revision: 2, lastSave: { revision: 2, origin: 'detail', kind: 'partial', fields: ['APPLICANT_ASSETS'], announcementId: '1' } })
    expect((await screen.findByText('3,000만 원', { exact: false })).closest('li')).toHaveTextContent('3,000만 원')
    expect(screen.queryByRole('button', { name: '본인 자산 입력하기' })).not.toBeInTheDocument()
    await act(async () => { vi.advanceTimersByTime(6100) })
    expect(screen.queryByText('방금 확인했어요')).not.toBeInTheDocument()
  })

  it('does not highlight rows after a full save', async () => {
    const load = vi.fn(async () => happyView([youthNeed]))
    const value = context({ loadComparison: load })
    const { rerenderWith } = renderSection(value)
    await screen.findByText('입력하면 확인할 수 있어요')
    const matched = type('youth', '청년', 'ELIGIBLE', youthNeed.checks.map((item) => ({ ...item, status: 'SATISFIED' as const, mine: '값' })))
    load.mockImplementation(async () => happyView([matched]))
    rerenderWith({ ...value, revision: 2, lastSave: { revision: 2, origin: 'detail', kind: 'full', fields: [], announcementId: '1' } })
    expect((await screen.findAllByText('기본 조건을 충족해요'))[0]).toBeInTheDocument()
    expect(screen.queryByText('방금 확인했어요')).not.toBeInTheDocument()
  })

  it('offers one input for several missing items only when more than one is missing', async () => {
    const two = type('youth', '청년', 'UNDETERMINED', [
      check('본인 자산', 'INPUT_NEEDED', { fields: ['APPLICANT_ASSETS'] }),
      check('본인 소득', 'INPUT_NEEDED', { fields: ['APPLICANT_MONTHLY_INCOME'] }),
    ])
    const value = context({ loadComparison: vi.fn(async () => happyView([two])) })
    renderSection(value)
    fireEvent.click(await screen.findByRole('button', { name: '△ 항목 한 번에 입력하기' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'partial', fields: ['APPLICANT_ASSETS', 'APPLICANT_MONTHLY_INCOME'], origin: 'detail' }, { announcementId: '1' })
  })

  it('does not offer one input when only one item is missing', async () => {
    renderSection(context({ loadComparison: vi.fn(async () => happyView([youthNeed])) }))
    await screen.findByText('입력하면 확인할 수 있어요')
    expect(screen.queryByRole('button', { name: '△ 항목 한 번에 입력하기' })).not.toBeInTheDocument()
  })

  it('opens the full family step to fix a family type mismatch', async () => {
    const value = context({ loadComparison: vi.fn(async () => happyView([newlywedNot])) })
    renderSection(value)
    fireEvent.click(await screen.findByRole('button', { name: '가족 형태 수정, 지금 값 미혼' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'full', step: 3, origin: 'detail' }, { announcementId: '1' })
    fireEvent.click(screen.getByRole('button', { name: '내 정보 수정하기' }))
    expect(value.openInput).toHaveBeenCalledTimes(2)
    // 미충족 칩은 내 값만 보이고, 남은 입력은 결과가 같다고 흐리게 알린다.
    expect(screen.getByRole('button', { name: '가족 형태 수정, 지금 값 미혼' })).toHaveTextContent(/^미혼$/)
    const rows = within(screen.getByRole('list', { name: '신혼부부 항목별 비교' })).getAllByRole('listitem')
    expect(rows[1]).toHaveTextContent('다른 조건이 맞지 않아 입력해도 결과는 같아요')
    expect(screen.queryByRole('button', { name: '△ 항목 한 번에 입력하기' })).not.toBeInTheDocument()
  })

  it('shows uncollected mandatory qualification separately without treating a family type as qualified', async () => {
    const path = type('single-parent', '지원대상 한부모가족 신청자격', 'UNDETERMINED', [check('서울 거주', 'SATISFIED', { mine: '서울' })], {
      qualifications: [{ title: '지원대상 한부모가족', body: '법정 증명 요건', items: [], evidence: null }],
    })
    renderSection(context({ loadComparison: vi.fn(async () => happyView([path], { status: 'UNDETERMINED' })) }))
    expect(await screen.findByText('법정 증명 요건')).toBeInTheDocument()
    // 판정 띠가 '위의 별도 신청자격'을 가리키므로 상자는 유형 패널보다 앞에 있다.
    const note = screen.getByRole('note')
    expect(note).toHaveTextContent('지원대상 한부모가족')
    expect(screen.getByText('공통 조건은 맞아요')).toBeInTheDocument()
    expect(screen.getByText('위의 별도 신청자격도 꼭 확인해 주세요.')).toBeInTheDocument()
    expect(note.compareDocumentPosition(screen.getByText('공통 조건은 맞아요')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.queryByText('기본 조건을 충족해요')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /입력하기/ })).not.toBeInTheDocument()
  })

  it('opens evidence pages from rows and guidances', async () => {
    const withEvidence = type('youth', '청년', 'ELIGIBLE', [check('나이', 'SATISFIED', { mine: '만 30세', evidence: { page: 5, location: null, fileName: null } })],
      { guidances: [{ kind: 'PROCEDURE', title: '중복 신청 불가', body: '한 유형만', evidence: { page: 3, location: null, fileName: null } }] })
    const { onOpenEvidence } = renderSection(context({ loadComparison: vi.fn(async () => happyView([withEvidence])) }))
    fireEvent.click(await screen.findByRole('button', { name: '근거 공고문 5쪽 열기' }))
    expect(onOpenEvidence).toHaveBeenCalledWith({ evidence: { page: 5, location: null, fileName: null }, label: '나이' })
    expect(screen.getByText('신청 절차')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '근거 공고문 3쪽 열기' }))
    expect(onOpenEvidence).toHaveBeenLastCalledWith({ evidence: { page: 3, location: null, fileName: null }, label: '중복 신청 불가' })
  })

  it('shows the preparing notice for NOT_STRUCTURED', async () => {
    renderSection(context({ loadComparison: vi.fn(async () => happyView([], { availability: 'NOT_STRUCTURED' })) }, { hasProfile: false }))
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
    expect((await screen.findAllByText('기본 조건을 충족해요'))[0]).toBeInTheDocument()
    await act(async () => { resolveFirst(happyView([newlywedNot])) })
    expect(screen.queryByText('맞지 않는 조건이 있어요')).not.toBeInTheDocument()
    expect(screen.getAllByText('기본 조건을 충족해요')[0]).toBeInTheDocument()
  })

  it('shows a retry for a failed comparison', async () => {
    const load = vi.fn().mockRejectedValueOnce(new Error('x')).mockResolvedValue(happyView([seniorMatched]))
    renderSection(context({ loadComparison: load }))
    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))
    expect((await screen.findAllByText('기본 조건을 충족해요'))[0]).toBeInTheDocument()
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

  it('keeps a gap above the invite button when no type chips are shown', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/public-housing/components/EligibilityComparisonSection.module.css'), 'utf8')
    expect(css).not.toMatch(/\.invite > \.wide:first-of-type/)
    expect(css).toMatch(/\.wide\s*\{[^}]*margin-top:\s*var\(--ds-space-4\);/)
  })
})
