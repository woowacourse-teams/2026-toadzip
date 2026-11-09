import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { useMobileViewport } from '../../public-housing/components/useMobileViewport'
import { LoginModal } from '../auth/LoginModal'
import { loginPageUrl, rememberLoginReturn } from '../auth/loginReturn'
import { useBookmarks } from '../bookmarks/BookmarkContext'
import { ConditionConfirmDialog } from './ConditionConfirmDialog'
import { ConditionInputDialog } from './ConditionInputDialog'
import { ConditionNudgeDialog } from './ConditionNudgeDialog'
import { ConditionToast } from './ConditionToast'
import {
  ConditionsContext,
  type ConditionsContextValue,
  type ConditionsMode,
  type LastSave,
  type NudgeVariant,
  type OpenInputOptions,
} from './ConditionsContext'
import { FIELD_COPY } from './conditionFieldCopy'
import { draftHasAnswers, emptyDraft, fromProfile, toProfile, todayInSeoul, type ConditionDraft } from './conditionDraft'
import { withJosa } from './conditionFormat'
import type { InputOrigin, InputRequest } from './conditionInput'
import {
  clearMemberDraft,
  loadMemberDraft,
  markNudgeShown,
  saveLoginHandoff,
  saveMemberDraft,
  snoozeNudge,
  takeLoginHandoff,
  type MemberDraft,
} from './conditionStorage'
import {
  ConditionsConflictError,
  ConditionsSessionError,
  EMPTY_PROFILE,
  type ConditionProfile,
  type ConditionsRepository,
  type EligibilityProcessing,
  type StoredConditionProfile,
} from './conditionsModel'
import { eligibilityRepository } from './eligibilityRepository'

function defaultRepository(_userId: string | null): ConditionsRepository {
  return eligibilityRepository
}

interface OpenInput {
  readonly key: number
  readonly request: InputRequest
  readonly announcementId: string | null
  readonly initialDraft: ConditionDraft
  readonly editing: boolean
  readonly initialMaxStep: number
}

interface SaveMeta {
  readonly origin: InputOrigin
  readonly kind: LastSave['kind']
  readonly fields: LastSave['fields']
  readonly announcementId: string | null
  readonly afterLogin?: boolean
  /** 세션이 끝나 저장하지 못하면 로그인으로 넘겨줄 입력 */
  readonly draft?: ConditionDraft
}

function saveMessage(meta: SaveMeta): string {
  const head = meta.kind === 'clear' ? '입력 정보를 모두 지웠어요'
    : meta.kind === 'partial' ? `${withJosa(meta.fields.length === 1 ? FIELD_COPY[meta.fields[0]].label : '추가 정보', '을/를')} 저장했어요`
    : meta.afterLogin ? '로그인하고 내 조건을 저장했어요' : '내 조건을 저장했어요'
  return `${head} · 현재 정보로 다시 확인하고 있어요`
}

export function ConditionsProvider({ children, createRepository = defaultRepository }: { readonly children: ReactNode; readonly createRepository?: (userId: string | null) => ConditionsRepository }) {
  const bookmarks = useBookmarks()
  const location = useLocation()
  const navigate = useNavigate()
  const mobile = useMobileViewport()
  // 북마크 세션은 창으로 돌아올 때마다 다시 확인한다. 확인하는 동안에는 직전 회원을 그대로 둬서 화면이 초기화되지 않게 한다.
  const bookmarkMode = bookmarks?.mode ?? 'guest'
  const bookmarkUserId = bookmarkMode === 'member' && bookmarks?.user ? String(bookmarks.user.id) : null
  const [confirmedUserId, setConfirmedUserId] = useState<string | null>(bookmarkUserId)
  useEffect(() => {
    if (bookmarkMode === 'member') setConfirmedUserId(bookmarkUserId)
    else if (bookmarkMode !== 'loading') setConfirmedUserId(null)
  }, [bookmarkMode, bookmarkUserId])
  const keptUserId = bookmarkMode === 'member' ? bookmarkUserId : bookmarkMode === 'loading' ? confirmedUserId : null
  const sessionMode: 'loading' | 'guest' | 'member' | 'error' = keptUserId !== null ? 'member' : bookmarkMode
  const userId = keptUserId
  const repository = useMemo(() => createRepository(userId), [createRepository, userId])

  const [stored, setStored] = useState<StoredConditionProfile | null>(null)
  const [profileOwner, setProfileOwner] = useState<string | null>(null)
  const [processing, setProcessing] = useState<EligibilityProcessing | null>(null)
  const [processingError, setProcessingError] = useState(false)
  const [profileStatus, setProfileStatus] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const [profileAttempt, setProfileAttempt] = useState(0)
  const [uiOwner, setUiOwner] = useState(userId)
  const [memberDraft, setMemberDraft] = useState<MemberDraft | null>(null)
  const [revision, setRevision] = useState(0)
  const [lastSave, setLastSave] = useState<LastSave | null>(null)
  const [input, setInput] = useState<OpenInput | null>(null)
  const [saving, setSaving] = useState(false)
  const [conflict, setConflict] = useState<{ readonly profile: ConditionProfile; readonly meta: SaveMeta; readonly editing: boolean } | null>(null)
  const [loginOpen, setLoginOpen] = useState(false)
  const [nudge, setNudge] = useState<{ readonly variant: NudgeVariant; readonly announcementId: string | null } | null>(null)
  const [toast, setToast] = useState<{ readonly text: string; readonly key: number } | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const loginTrigger = useRef<HTMLButtonElement>(null)
  const currentDraft = useRef<{ draft: ConditionDraft; step: number } | null>(null)
  const revisionRef = useRef(0)
  const handoffChecked = useRef(false)
  const savingRef = useRef(false)
  const activeUser = useRef(userId)
  activeUser.current = userId
  const today = todayInSeoul()

  const notify = useCallback((text: string) => setToast({ text, key: Date.now() }), [])
  const clearToast = useCallback(() => setToast(null), [])

  const retryProfile = useCallback(() => setProfileAttempt((value) => value + 1), [])
  useEffect(() => {
    setUiOwner(userId)
    setInput(null)
    setConflict(null)
    setNudge(null)
    setToast(null)
    setSaveError(null)
    setLastSave(null)
    setSaving(false)
    setProcessing(null)
    setProcessingError(false)
    currentDraft.current = null
    savingRef.current = false
  }, [userId])

  useEffect(() => {
    if (sessionMode !== 'member' || userId === null) {
      setStored(null)
      setProfileOwner(null)
      setProcessing(null)
      setProfileStatus('idle')
      setMemberDraft(null)
      return
    }
    const controller = new AbortController()
    handoffChecked.current = false
    setProfileStatus('loading')
    setMemberDraft(loadMemberDraft(userId))
    repository.loadProfile(controller.signal).then(
      (profile) => { if (!controller.signal.aborted) { setStored(profile); setProfileOwner(userId); setProfileStatus('ready') } },
      (error: unknown) => { if (!controller.signal.aborted && !(error instanceof DOMException && error.name === 'AbortError')) setProfileStatus('error') },
    )
    return () => controller.abort()
  }, [repository, sessionMode, userId, profileAttempt])

  useEffect(() => {
    if (sessionMode !== 'member' || profileOwner !== userId || !stored) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    let completed = stored.reevaluationState === 'DONE'
    setProcessing(null)
    const refresh = async () => {
      try {
        const next = await repository.loadProcessing(controller.signal)
        if (controller.signal.aborted) return
        if (next.profileVersion !== stored.version) {
          const profile = await repository.loadProfile(controller.signal)
          if (controller.signal.aborted) return
          setStored(profile)
          revisionRef.current += 1
          setRevision(revisionRef.current)
          return
        }
        setProcessing(next)
        setProcessingError(false)
        if (next.state === 'DONE' && !completed) {
          completed = true
          revisionRef.current += 1
          setRevision(revisionRef.current)
        }
        timer = setTimeout(() => { void refresh() }, next.state === 'DONE' ? 30_000 : 1500)
      } catch {
        if (!controller.signal.aborted) {
          setProcessingError(true)
          timer = setTimeout(() => { void refresh() }, 5000)
        }
      }
    }
    void refresh()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [profileOwner, repository, sessionMode, stored, userId])

  useEffect(() => {
    const reset = () => {
      if (userId !== null) clearMemberDraft(userId)
      setMemberDraft(null)
      setInput(null)
      setConflict(null)
      setToast(null)
      setNudge(null)
      setLastSave(null)
      currentDraft.current = null
    }
    window.addEventListener('toadzip:logout', reset)
    window.addEventListener('toadzip:session-ended', reset)
    return () => {
      window.removeEventListener('toadzip:logout', reset)
      window.removeEventListener('toadzip:session-ended', reset)
    }
  }, [userId])

  const ownsProfile = sessionMode === 'member' && profileOwner === userId
  const hasProfile = Boolean(ownsProfile && stored && Object.values(stored.profile.facts).some((fact) => fact.state !== 'UNKNOWN'))

  const openLogin = useCallback(() => {
    if (mobile) {
      const returnTo = location.pathname + location.search + location.hash
      rememberLoginReturn(returnTo)
      void navigate(loginPageUrl(returnTo))
      return
    }
    setLoginOpen(true)
  }, [location, mobile, navigate])

  const persist = useCallback(async (profile: ConditionProfile, meta: SaveMeta, editing: boolean, expectedVersion: number) => {
    if (userId === null || profileOwner !== userId || activeUser.current !== userId) return
    setSaving(true)
    setSaveError(null)
    savingRef.current = true
    try {
      const next = await repository.saveProfile({ expectedVersion, profile })
      if (activeUser.current !== userId) return
      setStored(next)
      if (userId !== null) clearMemberDraft(userId)
      setMemberDraft(null)
      setInput(null)
      setNudge(null)
      setConflict(null)
      revisionRef.current += 1
      setRevision(revisionRef.current)
      setLastSave({ revision: revisionRef.current, origin: meta.origin, kind: meta.kind, fields: meta.fields, announcementId: meta.announcementId })
      const message = saveMessage(meta)
      if (message) notify(message)
    } catch (error) {
      if (activeUser.current !== userId) return
      if (error instanceof ConditionsConflictError) {
        const latest = await repository.loadProfile().catch(() => null)
        if (activeUser.current !== userId) return
        if (latest) setStored(latest)
        setConflict({ profile, meta, editing })
      } else if (error instanceof ConditionsSessionError && meta.draft) {
        // 다시 로그인하면 입력한 내용을 이어서 저장한다.
        saveLoginHandoff({ draft: meta.draft, origin: meta.origin, announcementId: meta.announcementId })
        setSaveError('로그인이 끝나 저장하지 못했어요. 다시 로그인하면 이어서 저장해요.')
        openLogin()
      } else {
        setSaveError('저장하지 못했어요. 잠시 후 다시 시도해 주세요.')
        notify('저장하지 못했어요. 잠시 후 다시 시도해 주세요.')
      }
    } finally {
      if (activeUser.current === userId) {
        setSaving(false)
        savingRef.current = false
      }
    }
  }, [notify, openLogin, profileOwner, repository, userId])

  // 로그인 왕복으로 넘겨받은 입력은 로그인 확인 뒤 한 번만 저장한다.
  useEffect(() => {
    if (!ownsProfile || profileStatus !== 'ready' || !stored || handoffChecked.current) return
    handoffChecked.current = true
    const handoff = takeLoginHandoff()
    if (!handoff) return
    const meta: SaveMeta = { origin: handoff.origin, kind: 'full', fields: [], announcementId: handoff.announcementId, afterLogin: true }
    // 이미 저장한 내 조건이 있으면 바꿀지 먼저 묻는다.
    if (hasProfile) setConflict({ profile: toProfile(handoff.draft), meta, editing: false })
    else void persist(toProfile(handoff.draft), meta, false, stored.version)
  }, [hasProfile, ownsProfile, persist, profileStatus, stored])

  const openInput = useCallback((request: InputRequest, options: OpenInputOptions = {}) => {
    setNudge(null)
    setSaveError(null)
    let initialDraft = emptyDraft()
    let editing = false
    let maxStep = request.mode === 'full' && request.step === 4 ? 4 : 1
    let next = request
    if (hasProfile && stored) {
      initialDraft = fromProfile(stored.profile)
      editing = request.mode === 'full'
      maxStep = 4
    } else if (sessionMode === 'member' && memberDraft && request.mode === 'full') {
      initialDraft = memberDraft.draft
      const step = options.resume ? Math.min(4, Math.max(1, memberDraft.step)) as 1 | 2 | 3 | 4 : request.step
      next = { ...request, step }
      maxStep = Math.max(memberDraft.maxStep, step)
    }
    currentDraft.current = null
    setInput({ key: Date.now(), request: next, announcementId: options.announcementId ?? null, initialDraft, editing, initialMaxStep: maxStep })
  }, [hasProfile, memberDraft, sessionMode, stored])

  const restartInput = useCallback((origin: InputOrigin, announcementId: string | null = null) => {
    if (userId !== null) clearMemberDraft(userId)
    setMemberDraft(null)
    currentDraft.current = null
    setInput({ key: Date.now(), request: { mode: 'full', step: 1, origin }, announcementId, initialDraft: emptyDraft(), editing: false, initialMaxStep: 1 })
    notify('임시 저장한 내용을 지우고 처음부터 시작해요')
  }, [notify, userId])

  const onDraftChange = useCallback((draft: ConditionDraft, step: number, maxStep: number) => {
    currentDraft.current = { draft, step }
    if (!input || input.editing || input.request.mode !== 'full' || sessionMode !== 'member' || userId === null || !draftHasAnswers(draft)) return
    saveMemberDraft(userId, { draft, step, maxStep })
    setMemberDraft({ draft, step, maxStep })
  }, [input, sessionMode, userId])

  const closeInput = useCallback(() => {
    const draft = currentDraft.current?.draft
    if (input && !input.editing && input.request.mode === 'full' && sessionMode === 'member' && draft && draftHasAnswers(draft)) {
      notify('작성하던 내용은 이 기기에 저장해 뒀어요')
    }
    setInput(null)
  }, [input, notify, sessionMode])

  const submit = useCallback((draft: ConditionDraft) => {
    if (!input) return
    const meta: SaveMeta = {
      origin: input.request.origin,
      kind: input.request.mode,
      fields: input.request.mode === 'partial' ? input.request.fields : [],
      announcementId: input.announcementId,
      draft,
    }
    if (sessionMode !== 'member') {
      saveLoginHandoff({ draft, origin: meta.origin, announcementId: meta.announcementId })
      openLogin()
      return
    }
    void persist(toProfile(draft), meta, input.editing, stored?.version ?? 0)
  }, [input, openLogin, persist, sessionMode, stored])

  const closeLogin = useCallback(() => {
    takeLoginHandoff()
    setLoginOpen(false)
  }, [])

  const clearProfile = useCallback(() => {
    void persist(EMPTY_PROFILE, { origin: 'mypage', kind: 'clear', fields: [], announcementId: null }, false, stored?.version ?? 0)
  }, [persist, stored])

  const openNudge = useCallback((variant: NudgeVariant, options: { readonly announcementId?: string | null } = {}) => {
    // 저장하는 중이면 곧 비교 결과가 보이므로 권하지 않는다.
    if (savingRef.current) return
    markNudgeShown()
    setNudge({ variant, announcementId: options.announcementId ?? null })
  }, [])
  const startFromNudge = useCallback(() => {
    if (!nudge) return
    openInput({ mode: 'full', step: 1, origin: nudge.variant === 'list' ? 'list' : 'detail' }, { announcementId: nudge.announcementId, resume: true })
  }, [nudge, openInput])
  const laterFromNudge = useCallback(() => {
    if (nudge && nudge.variant !== 'list') snoozeNudge()
    setNudge(null)
    notify('마이페이지 > 내 조건 관리에서도 입력할 수 있어요')
  }, [notify, nudge])

  const mode: ConditionsMode = sessionMode === 'member'
    ? profileStatus === 'ready' && profileOwner === userId ? 'member' : profileStatus === 'error' ? 'error' : 'loading'
    : sessionMode

  const value = useMemo<ConditionsContextValue>(() => ({
    mode,
    sessionKey: userId,
    processing: ownsProfile && processing?.profileVersion === stored?.version ? processing : null,
    processingError,
    retryProfile,
    repository,
    stored: ownsProfile ? stored : null,
    hasProfile,
    memberDraft: uiOwner === userId && memberDraft ? { step: memberDraft.step } : null,
    revision,
    lastSave: uiOwner === userId ? lastSave : null,
    openInput,
    restartInput,
    openNudge,
    clearProfile,
    notify,
  }), [clearProfile, hasProfile, lastSave, memberDraft, mode, notify, openInput, openNudge, ownsProfile, processing, processingError, repository, restartInput, retryProfile, revision, stored, uiOwner, userId])

  return <ConditionsContext value={value}>
    {children}
    {input && uiOwner === userId && <ConditionInputDialog key={input.key} request={input.request} initialDraft={input.initialDraft} editing={input.editing}
      member={sessionMode === 'member'} initialMaxStep={input.initialMaxStep} today={today} saving={saving} error={saveError}
      onDraftChange={onDraftChange} onSubmit={submit} onClose={closeInput} />}
    {nudge && uiOwner === userId && !input && <ConditionNudgeDialog variant={nudge.variant} draftExists={sessionMode === 'member' && memberDraft !== null}
      onStart={startFromNudge} onLater={laterFromNudge} />}
    {loginOpen && <LoginModal loginFailed={false} sessionError={false} onClose={closeLogin} returnFocusRef={loginTrigger} />}
    {conflict && uiOwner === userId && <ConditionConfirmDialog title="이미 저장한 내 조건이 있어요" body="다른 곳에서 먼저 저장한 내 조건이 있어요. 방금 입력한 내용으로 바꿀까요?"
      cancelLabel="저장된 내용 유지" confirmLabel="방금 입력한 내용으로 바꾸기" confirmDisabled={saving}
      onCancel={() => { setConflict(null); setInput(null) }}
      onDismiss={() => setConflict(null)}
      onConfirm={() => { void persist(conflict.profile, conflict.meta, conflict.editing, stored?.version ?? 0) }} />}
    <ConditionToast message={uiOwner === userId ? toast : null} onDone={clearToast} />
  </ConditionsContext>
}
