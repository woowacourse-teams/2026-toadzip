import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { BookmarkContext, type BookmarkContextValue } from '../bookmarks/BookmarkContext'
import { ConditionsProvider } from './ConditionsProvider'
import { useConditions } from './ConditionsContext'
import { LATER, emptyDraft, setAnswer, toProfile } from './conditionDraft'
import { saveLoginHandoff, saveMemberDraft } from './conditionStorage'
import {
  ConditionsConflictError,
  ConditionsSessionError,
  EMPTY_PROFILE,
  PROFILE_FIELDS,
  type ConditionProfile,
  type ConditionsRepository,
  type StoredConditionProfile,
} from './conditionsModel'

vi.mock('../auth/LoginModal', () => ({ LoginModal: ({ onClose }: { onClose: () => void }) => <div role="dialog" aria-label="로그인"><button type="button" onClick={onClose}>로그인 닫기</button></div> }))

afterEach(() => { sessionStorage.clear(); localStorage.clear(); vi.useRealTimers() })

const savedProfile = toProfile(setAnswer(setAnswer(emptyDraft(), 'BIRTH_DATE', '1996-03-02'), 'APPLICANT_ASSETS', LATER))

function fakeRepository(initial: StoredConditionProfile = { version: 0, savedAt: null, profile: EMPTY_PROFILE }) {
  let stored: StoredConditionProfile = { reevaluationState: 'DONE', ...initial }
  const repository = {
    loadProfile: vi.fn(async () => stored),
    saveProfile: vi.fn(async ({ expectedVersion, profile }: { expectedVersion: number; profile: ConditionProfile }) => {
      if (expectedVersion !== stored.version) throw new ConditionsConflictError()
      stored = { version: stored.version + 1, savedAt: '2026-11-06T03:00:00.000Z', profile, reevaluationState: 'PENDING' }
      return stored
    }),
    loadComparison: vi.fn(),
    loadListResults: vi.fn(async () => new Map()),
    loadProcessing: vi.fn(async () => ({ profileVersion: stored.version, requestedVersion: stored.version, state: stored.reevaluationState ?? 'DONE' as const, failedAttempts: 0 })),
    loadDiscoveries: vi.fn(async () => ({ items: [], nextCursor: 0, hasMore: false })),
    acknowledgeDiscovery: vi.fn(async () => {}),
    replace(next: StoredConditionProfile) { stored = next },
  }
  return repository
}

function session(mode: BookmarkContextValue['mode'], id = 7): BookmarkContextValue {
  return { mode, user: mode === 'member' ? { id, email: null } : null, listStatus: 'ready', items: [], optimistic: new Map(), pending: new Set(), error: '', reload: vi.fn(), toggle: vi.fn() }
}

function Probe() {
  const conditions = useConditions()
  return <>
    <p>mode:{conditions.mode}</p>
    <p>profile:{String(conditions.hasProfile)}</p>
    <p>revision:{conditions.revision}</p>
    <p>draft:{conditions.memberDraft ? `${conditions.memberDraft.step}` : 'none'}</p>
    <button type="button" onClick={() => conditions.openInput({ mode: 'full', step: 4, origin: 'detail' }, { announcementId: '1' })}>완료 화면 열기</button>
    <button type="button" onClick={() => conditions.openInput({ mode: 'full', step: 1, origin: 'detail' }, { resume: true })}>입력 열기</button>
    <button type="button" onClick={() => conditions.openNudge('list')}>목록 유도 열기</button>
    <button type="button" onClick={() => conditions.openNudge('detail', { announcementId: '1' })}>상세 유도 열기</button>
  </>
}

function renderProvider(repository: ConditionsRepository, mode: BookmarkContextValue['mode'] = 'member') {
  const factory = vi.fn(() => repository)
  const view = render(<MemoryRouter><BookmarkContext value={session(mode)}>
    <ConditionsProvider createRepository={factory}><Probe /></ConditionsProvider>
  </BookmarkContext></MemoryRouter>)
  return { ...view, factory }
}

describe('ConditionsProvider', () => {
  it('loads the profile once for a member and exposes hasProfile', async () => {
    const repository = fakeRepository({ version: 3, savedAt: '2026-11-05T00:00:00.000Z', profile: savedProfile })
    renderProvider(repository)
    expect(await screen.findByText('mode:member')).toBeInTheDocument()
    expect(screen.getByText('profile:true')).toBeInTheDocument()
    expect(repository.loadProfile).toHaveBeenCalledTimes(1)
  })

  it('stays guest without loading a profile', async () => {
    const repository = fakeRepository()
    renderProvider(repository, 'guest')
    expect(await screen.findByText('mode:guest')).toBeInTheDocument()
    expect(repository.loadProfile).not.toHaveBeenCalled()
  })

  it('saves with the loaded version and bumps the revision', async () => {
    const repository = fakeRepository()
    renderProvider(repository)
    await screen.findByText('mode:member')
    fireEvent.click(screen.getByRole('button', { name: '완료 화면 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '이 공고와 비교하기' }))
    expect(await screen.findByText('revision:1')).toBeInTheDocument()
    expect(repository.saveProfile).toHaveBeenCalledWith(expect.objectContaining({ expectedVersion: 0 }))
    expect(screen.getByRole('status')).toHaveTextContent('조건을 저장했어요. 입력하신 정보로 다시 확인할게요.')
    expect(screen.queryByRole('heading', { name: '입력을 마쳤어요' })).not.toBeInTheDocument()
  })

  it('keeps the draft and shows the error inside the dialog when saving fails', async () => {
    const repository = fakeRepository()
    repository.saveProfile.mockRejectedValueOnce(new Error('offline'))
    renderProvider(repository)
    await screen.findByText('mode:member')
    fireEvent.click(screen.getByRole('button', { name: '완료 화면 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '이 공고와 비교하기' }))
    const dialog = screen.getByRole('heading', { name: '입력을 마쳤어요' }).closest('dialog')!
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('저장하지 못했어요. 잠시 후 다시 시도해 주세요.')
    expect(screen.getByText('revision:0')).toBeInTheDocument()
  })

  it('hands the draft over to a new login when the session has ended', async () => {
    const repository = fakeRepository()
    repository.saveProfile.mockRejectedValueOnce(new ConditionsSessionError())
    renderProvider(repository)
    await screen.findByText('mode:member')
    fireEvent.click(screen.getByRole('button', { name: '완료 화면 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '이 공고와 비교하기' }))
    expect(await screen.findByRole('dialog', { name: '로그인' })).toBeInTheDocument()
    expect(sessionStorage.getItem('toadzip:condition-handoff')).not.toBeNull()
    expect(screen.getByRole('heading', { name: '입력을 마쳤어요' })).toBeInTheDocument()
  })

  it('keeps the member state while the session is being rechecked', async () => {
    const repository = fakeRepository({ version: 3, savedAt: null, profile: savedProfile })
    const factory = vi.fn(() => repository)
    const tree = (mode: BookmarkContextValue['mode']) => <MemoryRouter><BookmarkContext value={session(mode)}>
      <ConditionsProvider createRepository={factory}><Probe /></ConditionsProvider></BookmarkContext></MemoryRouter>
    const { rerender } = render(tree('member'))
    expect(await screen.findByText('profile:true')).toBeInTheDocument()
    rerender(tree('loading'))
    expect(screen.getByText('mode:member')).toBeInTheDocument()
    expect(screen.getByText('profile:true')).toBeInTheDocument()
    rerender(tree('member'))
    await screen.findByText('profile:true')
    expect(repository.loadProfile).toHaveBeenCalledTimes(1)
    rerender(tree('guest'))
    expect(await screen.findByText('mode:guest')).toBeInTheDocument()
  })

  it('hides the previous account profile and open editor while another account loads', async () => {
    const first = fakeRepository({ version: 3, savedAt: null, profile: savedProfile })
    const second = fakeRepository()
    let resolve: (value: StoredConditionProfile) => void = () => {}
    second.loadProfile.mockImplementation(() => new Promise((done) => { resolve = done }))
    const factory = (id: string | null) => id === '7' ? first : second
    const tree = (id: number) => <MemoryRouter><BookmarkContext value={session('member', id)}>
      <ConditionsProvider createRepository={factory}><Probe /></ConditionsProvider></BookmarkContext></MemoryRouter>
    const { rerender } = render(tree(7))
    await screen.findByText('profile:true')
    fireEvent.click(screen.getByRole('button', { name: '입력 열기' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    rerender(tree(8))
    expect(screen.getByText('profile:false')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await act(async () => resolve({ version: 0, savedAt: null, profile: EMPTY_PROFILE, reevaluationState: 'DONE' }))
    await screen.findByText('mode:member')
    expect(second.saveProfile).not.toHaveBeenCalled()
  })

  it('asks before a login handoff replaces saved conditions', async () => {
    saveLoginHandoff({ draft: setAnswer(emptyDraft(), 'BIRTH_DATE', '1996-03-02'), origin: 'detail', announcementId: '1' })
    const repository = fakeRepository({ version: 3, savedAt: null, profile: savedProfile })
    renderProvider(repository)
    const confirm = await screen.findByRole('alertdialog', { name: '이미 저장한 내 조건이 있어요' })
    expect(repository.saveProfile).not.toHaveBeenCalled()
    fireEvent.click(within(confirm).getByRole('button', { name: '방금 입력한 내용으로 바꾸기' }))
    expect(await screen.findByText('revision:1')).toBeInTheDocument()
    expect(repository.saveProfile).toHaveBeenCalledWith(expect.objectContaining({ expectedVersion: 3 }))
  })

  it('asks before overwriting a newer profile', async () => {
    const repository = fakeRepository()
    renderProvider(repository)
    await screen.findByText('mode:member')
    repository.replace({ version: 2, savedAt: '2026-11-06T01:00:00.000Z', profile: savedProfile })
    fireEvent.click(screen.getByRole('button', { name: '완료 화면 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '이 공고와 비교하기' }))
    const confirm = await screen.findByRole('alertdialog', { name: '이미 저장한 내 조건이 있어요' })
    fireEvent.click(within(confirm).getByRole('button', { name: '방금 입력한 내용으로 바꾸기' }))
    expect(await screen.findByText('revision:1')).toBeInTheDocument()
    expect(repository.saveProfile).toHaveBeenLastCalledWith(expect.objectContaining({ expectedVersion: 2 }))
  })

  it('hands a guest draft over a real login and saves it after login', async () => {
    saveLoginHandoff({ draft: setAnswer(emptyDraft(), 'BIRTH_DATE', '1996-03-02'), origin: 'list', announcementId: null })
    const repository = fakeRepository({ version: 0, savedAt: null, profile: {
      familyDetailsState: 'NOT_STARTED', facts: Object.fromEntries(PROFILE_FIELDS.map((field) => [field, { state: 'UNKNOWN' as const }])),
    } })
    renderProvider(repository)
    expect(await screen.findByText('revision:1')).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('조건을 저장했어요. 입력하신 정보로 다시 확인할게요.')
    expect(sessionStorage.getItem('toadzip:condition-handoff')).toBeNull()
  })

  it('ignores an expired or malformed login handoff', async () => {
    sessionStorage.setItem('toadzip:condition-handoff', '{"draft":1,"origin":"detail","expiresAt":1}')
    const repository = fakeRepository()
    renderProvider(repository)
    await screen.findByText('mode:member')
    expect(repository.saveProfile).not.toHaveBeenCalled()
    expect(screen.getByText('revision:0')).toBeInTheDocument()
  })

  it('opens the login for a guest and keeps the done screen when it closes', async () => {
    const repository = fakeRepository()
    renderProvider(repository, 'guest')
    await screen.findByText('mode:guest')
    fireEvent.click(screen.getByRole('button', { name: '완료 화면 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '로그인하고 이 공고와 비교하기' }))
    expect(screen.getByRole('dialog', { name: '로그인' })).toBeInTheDocument()
    expect(sessionStorage.getItem('toadzip:condition-handoff')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '로그인 닫기' }))
    expect(screen.getByRole('heading', { name: '입력을 마쳤어요' })).toBeInTheDocument()
    expect(sessionStorage.getItem('toadzip:condition-handoff')).toBeNull()
  })

  it('auto-saves a member first draft and resumes it next time', async () => {
    saveMemberDraft('7', { draft: setAnswer(emptyDraft(), 'BIRTH_DATE', '1996-03-02'), step: 2, maxStep: 2 })
    renderProvider(fakeRepository())
    expect(await screen.findByText('draft:2')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '입력 열기' }))
    expect(screen.getByText('2 / 3 단계')).toBeInTheDocument()
    fireEvent.click(within(screen.getByRole('radiogroup', { name: '본인 명의의 주택이 있나요?' })).getByRole('radio', { name: '아니요' }))
    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    expect(screen.getByRole('status')).toHaveTextContent('작성하던 내용은 이 기기에 저장해 뒀어요')
    expect(JSON.parse(localStorage.getItem('toadzip:condition-draft:7') ?? '{}').draft.answers.APPLICANT_OWNS_HOUSE).toBe(false)
  })

  it('clears the member draft on logout', async () => {
    saveMemberDraft('7', { draft: setAnswer(emptyDraft(), 'BIRTH_DATE', '1996-03-02'), step: 2, maxStep: 2 })
    renderProvider(fakeRepository())
    await screen.findByText('draft:2')
    act(() => window.dispatchEvent(new Event('toadzip:logout')))
    expect(localStorage.getItem('toadzip:condition-draft:7')).toBeNull()
    expect(screen.getByText('draft:none')).toBeInTheDocument()
  })

  it('starts the full input from the list nudge', async () => {
    renderProvider(fakeRepository())
    await screen.findByText('mode:member')
    fireEvent.click(screen.getByRole('button', { name: '목록 유도 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '지금 입력해 보기' }))
    expect(screen.queryByRole('heading', { name: /모아 볼 수 있어요/ })).not.toBeInTheDocument()
    expect(screen.getByText('1 / 3 단계')).toBeInTheDocument()
    expect(sessionStorage.getItem('toadzip:condition-nudge-shown')).toBe('true')
  })

  it('closes an open nudge once conditions are saved', async () => {
    const repository = fakeRepository()
    renderProvider(repository)
    await screen.findByText('mode:member')
    fireEvent.click(screen.getByRole('button', { name: '상세 유도 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '완료 화면 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '이 공고와 비교하기' }))
    expect(await screen.findByText('revision:1')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '지금 입력해 보기' })).not.toBeInTheDocument()
  })

  it('snoozes the detail nudge for later', async () => {
    renderProvider(fakeRepository())
    await screen.findByText('mode:member')
    fireEvent.click(screen.getByRole('button', { name: '상세 유도 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '다음에 할게요' }))
    expect(screen.getByRole('status')).toHaveTextContent('마이페이지 > 내 조건 관리에서도 입력할 수 있어요')
    expect(localStorage.getItem('toadzip:condition-nudge-snooze-until')).not.toBeNull()
  })

})
