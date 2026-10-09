import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { ConsentStore } from './consentStore'
import { privacyApi, PrivacyError, type ConsentContext } from './api'

function context(kind: 'MEMBER' | 'GUEST' = 'MEMBER', decision: 'UNSET' | 'GRANTED' | 'DENIED' | 'WITHDRAWN' = 'UNSET', revision = 0): ConsentContext {
  return { subject: { kind, ...(kind === 'MEMBER' ? { userId: '1' } : {}), contextId: kind === 'MEMBER' ? 'member-context' : 'guest-context' }, consent: { decision, effectiveStatus: decision, revision, noticeVersion: 'analytics-v1', scopeVersion: 'scope-1', decidedAt: null, expiresAt: null }, requiredNoticeVersion: 'analytics-v1', requiredScopeVersion: 'scope-1', collectionAllowed: decision === 'GRANTED', checkedAt: '2026-10-09T00:00:00Z', maxAgeSeconds: 60 }
}
function setup(current = context()) {
  const api = { ...privacyApi, context: vi.fn().mockResolvedValue(current), choose: vi.fn().mockResolvedValue(context(current.subject.kind, 'GRANTED', 1)), prepareGuest: vi.fn().mockResolvedValue(context('GUEST')) }
  return { api, store: new ConsentStore(api) }
}
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (error: Error) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: vi.fn(async (_name: string, callback: () => unknown) => callback()) } })
})

it('다른 visible 탭의 인증 시작은 storage 이벤트 전에도 이전 허용을 중지한다', async () => {
  const first = setup(context('MEMBER', 'GRANTED', 1)); const second = setup(context('MEMBER', 'GRANTED', 1))
  await first.store.refresh(); await second.store.refresh()
  expect(first.store.allowed()).toBe(true)
  const operation = second.store.beginAuthTransition('logout')
  expect(first.store.allowed()).toBe(false)
  await first.store.refresh()
  expect(first.api.context).toHaveBeenCalledOnce()
  second.api.context.mockResolvedValue(context('GUEST'))
  await second.store.finishAuthTransition(operation)
  expect(first.store.allowed()).toBe(false)
  first.api.context.mockResolvedValue(context('GUEST')); await first.store.refresh()
  expect(first.store.getSnapshot().context?.subject.kind).toBe('GUEST')
})

it('인증 전환 전에 시작한 허용 조회는 전환 완료 뒤 도착해도 적용하지 않는다', async () => {
  const first = setup(context('MEMBER', 'GRANTED', 1)); const second = setup(context('MEMBER', 'GRANTED', 1))
  await first.store.refresh(); await second.store.refresh()
  const response = deferred<ConsentContext>(); first.api.context.mockReturnValueOnce(response.promise)
  const refreshing = first.store.refresh()
  const operation = second.store.beginAuthTransition('logout')
  second.api.context.mockResolvedValue(context('GUEST')); await second.store.finishAuthTransition(operation)
  response.resolve(context('MEMBER', 'GRANTED', 1)); await refreshing
  expect(first.store.allowed()).toBe(false)
})

it('먼저 끝난 인증은 다른 진행 중 인증의 차단을 해제하지 않는다', async () => {
  const { store } = setup(context('MEMBER', 'GRANTED', 1)); await store.refresh()
  const first = store.beginAuthTransition('logout'); const second = store.beginAuthTransition('logout')
  await store.finishAuthTransition(first); expect(store.allowed()).toBe(false)
  await store.finishAuthTransition(second); expect(store.allowed()).toBe(true)
})

it('OAuth에서 앱에 돌아오면 해당 탭의 전환을 끝내고 서버 상태를 재확인한다', async () => {
  const leaving = setup(context('GUEST', 'GRANTED', 1)); await leaving.store.refresh()
  leaving.store.beginAuthTransition('oauth')
  const returned = setup(context('MEMBER', 'DENIED', 1)); const cleanup = returned.store.start()
  try {
    await vi.waitFor(() => expect(returned.store.getSnapshot().context?.subject.kind).toBe('MEMBER'))
    expect(returned.store.allowed()).toBe(false)
    expect(sessionStorage.getItem('toadzip.privacy.oauth-transition')).toBeNull()
  } finally { cleanup() }
})

it('닫힌 OAuth 창의 차단은 10분 후 새 서버 확인을 거쳐 복구된다', async () => {
  const { store, api } = setup(context('GUEST', 'GRANTED', 1)); await store.refresh()
  const now = Date.now(); const time = vi.spyOn(Date, 'now').mockReturnValue(now)
  store.beginAuthTransition('oauth'); await store.refresh()
  expect(api.context).toHaveBeenCalledOnce(); expect(store.allowed()).toBe(false)
  time.mockReturnValue(now + 10 * 60_000)
  expect(store.allowed()).toBe(false)
  await store.refresh(); expect(store.allowed()).toBe(true)
  expect(Object.keys(localStorage).filter(key => key.startsWith('toadzip.privacy.auth-pending:'))).toHaveLength(0)
})

it('인증 변경 이벤트는 재전파하지 않고 현재 상태만 다시 확인한다', async () => {
  const { store } = setup(context('MEMBER', 'GRANTED', 1)); const cleanup = store.start()
  try {
    await vi.waitFor(() => expect(store.allowed()).toBe(true))
    localStorage.setItem('toadzip.privacy.auth-changed', 'other-operation')
    const write = vi.spyOn(Storage.prototype, 'setItem')
    window.dispatchEvent(new StorageEvent('storage', { key: 'toadzip.privacy.auth-changed' }))
    expect(store.allowed()).toBe(false)
    await vi.waitFor(() => expect(store.allowed()).toBe(true))
    expect(write).not.toHaveBeenCalled()
  } finally { cleanup() }
})

it('인증 시작 중 도착한 과거 동의 저장 결과로 수집을 다시 켜지 않는다', async () => {
  const first = setup(); const second = setup(); await first.store.refresh(); await second.store.refresh()
  const response = deferred<ConsentContext>(); first.api.choose.mockReturnValueOnce(response.promise)
  const choosing = first.store.choose('GRANT', 'SETTINGS')
  await vi.waitFor(() => expect(first.api.choose).toHaveBeenCalledOnce())
  const operation = second.store.beginAuthTransition('logout')
  await second.store.finishAuthTransition(operation)
  response.resolve(context('MEMBER', 'GRANTED', 1)); await choosing
  expect(first.store.allowed()).toBe(false)
})

it('storage 쓰기 실패는 인증을 막지 않고 BroadcastChannel로 다른 탭을 중지한다', async () => {
  const channels: Array<{ onmessage: ((event: MessageEvent<unknown>) => void) | null }> = []
  vi.stubGlobal('BroadcastChannel', class {
    onmessage: ((event: MessageEvent<unknown>) => void) | null = null
    constructor() { channels.push(this) }
    postMessage(data: unknown) { for (const channel of channels) if (channel !== this) channel.onmessage?.({ data } as MessageEvent<unknown>) }
    close() { this.onmessage = null }
  })
  const first = setup(context('MEMBER', 'GRANTED', 1)); const second = setup(context('MEMBER', 'GRANTED', 1))
  const cleanFirst = first.store.start(); const cleanSecond = second.store.start()
  try {
    await vi.waitFor(() => expect(first.store.allowed() && second.store.allowed()).toBe(true))
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    const operation = second.store.beginAuthTransition('logout')
    expect(first.store.allowed()).toBe(false); expect(second.store.allowed()).toBe(false)
    first.api.context.mockResolvedValue(context('GUEST')); second.api.context.mockResolvedValue(context('GUEST'))
    await expect(second.store.finishAuthTransition(operation)).resolves.toBeUndefined()
    await vi.waitFor(() => expect(first.store.getSnapshot().context?.subject.kind).toBe('GUEST'))
  } finally { cleanFirst(); cleanSecond(); vi.unstubAllGlobals() }
})
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })
it('새 이벤트가 없어도 확인 유효기간 정각에 SDK 중지와 상태 변경을 통지한다', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] })
  const { store } = setup(context('MEMBER', 'GRANTED', 1))
  const stopped = vi.fn(); const changed = vi.fn()
  store.onStop(stopped); store.subscribe(changed)
  await store.refresh(); changed.mockClear()
  await vi.advanceTimersByTimeAsync(59_999)
  expect(store.allowed()).toBe(true); expect(stopped).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(1)
  expect(store.allowed()).toBe(false); expect(stopped).toHaveBeenCalledOnce(); expect(changed).toHaveBeenCalledOnce()
})
it('서버 동의 만료가 더 빠르면 응답 대기 시간을 포함해 해당 시점에 중지한다', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] })
  const granted = context('MEMBER', 'GRANTED', 1)
  granted.consent.expiresAt = '2026-10-09T00:00:20Z'
  const { store, api } = setup(granted)
  const response = deferred<ConsentContext>(); api.context.mockReturnValueOnce(response.promise)
  const stopped = vi.fn(); store.onStop(stopped)
  const refreshing = store.refresh()
  await vi.advanceTimersByTimeAsync(5_000); response.resolve(granted); await refreshing
  await vi.advanceTimersByTimeAsync(14_999)
  expect(store.allowed()).toBe(true)
  await vi.advanceTimersByTimeAsync(1)
  expect(store.allowed()).toBe(false); expect(stopped).toHaveBeenCalledOnce()
})
it('재조회로 확인 기간이 갱신되면 이전 타이머가 유효한 수집을 중단하지 않는다', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] })
  const { store } = setup(context('MEMBER', 'GRANTED', 1))
  const stopped = vi.fn(); store.onStop(stopped)
  await store.refresh(); await vi.advanceTimersByTimeAsync(30_000); await store.refresh()
  await vi.advanceTimersByTimeAsync(30_000)
  expect(store.allowed()).toBe(true); expect(stopped).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(30_000)
  expect(store.allowed()).toBe(false); expect(stopped).toHaveBeenCalledOnce()
})
it('조회 전, 거부, 서버 수집 미활성화는 수집을 허용하지 않는다', async () => {
  const { store, api } = setup(); expect(store.allowed()).toBe(false)
  await store.refresh(); expect(store.allowed()).toBe(false)
  api.context.mockResolvedValue({ ...context('MEMBER', 'GRANTED', 1), collectionAllowed: false })
  await store.refresh(); expect(store.allowed()).toBe(false)
})
it('60초 유효기간과 숨긴 탭에서는 수집이 중단된다', async () => {
  const now = vi.spyOn(performance, 'now').mockReturnValue(1000)
  const { store } = setup(context('MEMBER', 'GRANTED', 1))
  await store.refresh(); expect(store.allowed()).toBe(true)
  now.mockReturnValue(61_000); expect(store.allowed()).toBe(false)
  now.mockReturnValue(2000); Object.defineProperty(document, 'hidden', { configurable: true, value: true }); expect(store.allowed()).toBe(false)
})
it('허용 응답 유실은 수집을 켜지 않고 동일 명령을 재시도한다', async () => {
  const { store, api } = setup(); await store.refresh(); api.choose.mockRejectedValueOnce(new Error('lost'))
  await store.choose('GRANT', 'FIRST_VISIT'); expect(store.allowed()).toBe(false)
  const original = api.choose.mock.calls[0]; await store.retry()
  expect(api.choose.mock.calls[1]).toEqual(original); expect(store.allowed()).toBe(true)
})
it('철회는 즉시 중지하고 실패와 재조회 후에도 marker를 유지한다', async () => {
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1)); await store.refresh()
  const pending = deferred<ConsentContext>(); api.choose.mockReturnValueOnce(pending.promise)
  const choosing = store.choose('WITHDRAW', 'SETTINGS'); expect(store.allowed()).toBe(false)
  await vi.waitFor(() => expect(api.choose).toHaveBeenCalled()); pending.reject(new Error('offline')); await choosing; await store.refresh()
  expect(store.allowed()).toBe(false); expect(localStorage.getItem('toadzip.privacy.pending-stop:MEMBER:1')).toContain('WITHDRAW')
})
it('늦은 허용 GET은 철회 성공을 되돌리지 않는다', async () => {
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1)); await store.refresh()
  const pending = deferred<ConsentContext>(); api.context.mockReturnValueOnce(pending.promise); const loading = store.refresh()
  api.choose.mockResolvedValue(context('MEMBER', 'WITHDRAWN', 2)); await store.choose('WITHDRAW', 'SETTINGS')
  pending.resolve(context('MEMBER', 'GRANTED', 1)); await loading
  expect(store.getSnapshot().context?.consent.decision).toBe('WITHDRAWN'); expect(store.allowed()).toBe(false)
})
it('회원 A의 늦은 응답은 계정 B에 적용되지 않는다', async () => {
  const { store, api } = setup(); await store.refresh()
  const pending = deferred<ConsentContext>(); api.choose.mockReturnValue(pending.promise)
  const choosing = store.choose('GRANT', 'SETTINGS'); await vi.waitFor(() => expect(api.choose).toHaveBeenCalled())
  store.transition(); api.context.mockResolvedValue({ ...context(), subject: { kind: 'MEMBER', userId: '2', contextId: null } }); await store.refresh()
  pending.resolve(context('MEMBER', 'GRANTED', 1)); await choosing
  expect(store.getSnapshot().context?.subject.userId).toBe('2'); expect(store.allowed()).toBe(false)
})
it('비회원 선택은 cookie context를 재확인하고 회원에게 복사하지 않는다', async () => {
  const { store, api } = setup(context('GUEST')); await store.refresh()
  api.context.mockResolvedValue(context('GUEST', 'GRANTED', 1)); await store.choose('GRANT', 'FIRST_VISIT')
  expect(api.prepareGuest).toHaveBeenCalledOnce(); expect(api.choose).toHaveBeenCalledWith('GUEST', expect.objectContaining({ contextId: 'guest-context', action: 'GRANT' })); expect(store.allowed()).toBe(true)
  store.transition(); api.context.mockResolvedValue(context('MEMBER')); await store.refresh()
  expect(store.allowed()).toBe(false); expect(api.choose).toHaveBeenCalledOnce()
})
it('쿠키 저장이 차단되면 허용 영수증만으로 수집하지 않는다', async () => {
  const { store, api } = setup(context('GUEST')); await store.refresh()
  api.context.mockResolvedValue({ ...context('GUEST'), subject: { kind: 'GUEST', contextId: null } }); await store.choose('GRANT', 'FIRST_VISIT')
  expect(store.allowed()).toBe(false); expect(store.getSnapshot().error).toContain('쿠키')
})
it('Web Locks 미지원이면 허용을 제출하지 않으며 거부는 적용한다', async () => {
  Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined })
  const { store, api } = setup(); await store.refresh(); await store.choose('GRANT', 'FIRST_VISIT')
  expect(api.choose).not.toHaveBeenCalled(); expect(store.allowed()).toBe(false)
  api.choose.mockResolvedValue(context('MEMBER', 'DENIED', 1)); await store.choose('DENY', 'FIRST_VISIT')
  expect(api.choose).toHaveBeenCalledOnce(); expect(store.allowed()).toBe(false)
})
it('허용 시작 뒤 다른 탭의 새 거부 marker는 지우지 않는다', async () => {
  const { store, api } = setup(); await store.refresh()
  localStorage.setItem('toadzip.privacy.pending-stop:MEMBER:1', JSON.stringify({ commandId: 'older', createdAt: Date.now() }))
  const pending = deferred<ConsentContext>(); api.choose.mockReturnValue(pending.promise)
  const choosing = store.choose('GRANT', 'SETTINGS'); await vi.waitFor(() => expect(api.choose).toHaveBeenCalled())
  localStorage.setItem('toadzip.privacy.pending-stop:MEMBER:1', JSON.stringify({ commandId: 'newer', createdAt: Date.now() }))
  pending.resolve(context('MEMBER', 'GRANTED', 1)); await choosing
  expect(localStorage.getItem('toadzip.privacy.pending-stop:MEMBER:1')).toContain('newer'); expect(store.allowed()).toBe(false)
})
it('revision 충돌은 자동 덮어쓰기 없이 재선택을 요구한다', async () => {
  const { store, api } = setup(); await store.refresh(); api.choose.mockRejectedValue(new PrivacyError(409, 'PRIVACY_REVISION_CONFLICT'))
  await store.choose('GRANT', 'SETTINGS'); await store.retry(); expect(api.choose).toHaveBeenCalledOnce(); expect(store.allowed()).toBe(false)
})

it('브라우저 저장소의 허용 명령은 서버에 자동 재생하지 않는다', async () => {
  localStorage.setItem('toadzip.privacy.pending-stop:MEMBER:1', JSON.stringify({ commandId: 'tampered', createdAt: Date.now(), choice: { commandId: 'tampered', expectedUserId: '1', expectedRevision: 0, action: 'GRANT', source: 'SETTINGS' } }))
  const { store, api } = setup(); await store.refresh()
  expect(api.choose).not.toHaveBeenCalled(); expect(store.allowed()).toBe(false)
})
it('비회원 준비 중 다른 탭의 최신 거부를 이전 명령 본문 보충이 덮지 않는다', async () => {
  const { store, api } = setup(context('GUEST')); await store.refresh()
  const preparing = deferred<ConsentContext>(); api.prepareGuest.mockReturnValue(preparing.promise)
  const choosing = store.choose('DENY', 'FIRST_VISIT')
  await vi.waitFor(() => expect(api.prepareGuest).toHaveBeenCalled())
  localStorage.setItem('toadzip.privacy.pending-stop:GUEST', JSON.stringify({ commandId: 'newer-stop', createdAt: Date.now() }))
  api.choose.mockResolvedValue(context('GUEST', 'DENIED', 1)); api.context.mockResolvedValue(context('GUEST', 'DENIED', 1))
  preparing.resolve(context('GUEST')); await choosing
  expect(localStorage.getItem('toadzip.privacy.pending-stop:GUEST')).toContain('newer-stop')
  expect(store.allowed()).toBe(false)
})
it('만료 marker 잠금 대기 중 계정이 또 바뀌면 이전 조회를 적용하지 않는다', async () => {
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1)); await store.refresh()
  const release = deferred<void>()
  Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: (_name: string, callback: () => unknown) => release.promise.then(callback) } })
  localStorage.setItem('toadzip.privacy.pending-stop:MEMBER:2', JSON.stringify({ commandId: 'expired', createdAt: Date.now() - 181 * 86400000 }))
  api.context.mockResolvedValue({ ...context('MEMBER', 'DENIED', 1), subject: { kind: 'MEMBER', userId: '2', contextId: 'two' } })
  const old = store.refresh(); await Promise.resolve(); await Promise.resolve()
  store.transition(); api.context.mockResolvedValue({ ...context(), subject: { kind: 'MEMBER', userId: '3', contextId: 'three' } }); const latest = store.refresh()
  release.resolve(); await Promise.all([old, latest])
  expect(store.getSnapshot().context?.subject.userId).toBe('3')
})
it('저장소 읽기 예외가 발생한 바로 그 전송부터 차단한다', async () => {
  const { store } = setup(context('MEMBER', 'GRANTED', 1)); await store.refresh(); expect(store.allowed()).toBe(true)
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  expect(store.allowed()).toBe(false)
})
it('조회 일시 실패는 차단하고 새 조회가 성공하면 복구한다', async () => {
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1)); await store.refresh()
  api.context.mockRejectedValueOnce(new Error('network')); await store.refresh(); expect(store.allowed()).toBe(false)
  await store.refresh(); expect(store.allowed()).toBe(true); expect(store.getSnapshot().error).toBe('')
})
it('오프라인 감지 시 즉시 차단하고 온라인에서 새 상태를 확인한다', async () => {
  const { store } = setup(context('MEMBER', 'GRANTED', 1)); const cleanup = store.start()
  await vi.waitFor(() => expect(store.allowed()).toBe(true))
  window.dispatchEvent(new Event('offline')); expect(store.allowed()).toBe(false)
  window.dispatchEvent(new Event('online')); await vi.waitFor(() => expect(store.allowed()).toBe(true)); cleanup()
})
it('선택의 실제 만료가 60초 lease보다 가까우면 그 정각부터 차단한다', async () => {
  const now = vi.spyOn(performance, 'now').mockReturnValue(1000)
  const snapshot = context('MEMBER', 'GRANTED', 1); snapshot.consent.expiresAt = '2026-10-09T00:00:20Z'
  const { store } = setup(snapshot); await store.refresh()
  now.mockReturnValue(20_999); expect(store.allowed()).toBe(true)
  now.mockReturnValue(21_000); expect(store.allowed()).toBe(false)
})
it('비회원 재시도도 다른 탭의 context 잠금을 기다리고 쿠키 확인 실패를 알린다', async () => {
  const { store, api } = setup(context('GUEST')); await store.refresh()
  api.choose.mockRejectedValueOnce(new Error('lost')); await store.choose('GRANT', 'FIRST_VISIT')
  const release = deferred<void>()
  Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: (_name: string, callback: () => unknown) => release.promise.then(callback) } })
  api.context.mockResolvedValue({ ...context('GUEST'), subject: { kind: 'GUEST', contextId: null } })
  const retrying = store.retry(); await Promise.resolve()
  expect(api.choose).toHaveBeenCalledTimes(1)
  release.resolve(); await retrying
  expect(api.choose.mock.calls[1]).toEqual(api.choose.mock.calls[0])
  expect(store.allowed()).toBe(false); expect(store.getSnapshot().error).toContain('쿠키')
})
it('비회원 재시도 잠금 대기 중 로그인하면 이전 비회원 명령을 보내지 않는다', async () => {
  const { store, api } = setup(context('GUEST')); await store.refresh()
  api.choose.mockRejectedValueOnce(new Error('lost')); await store.choose('GRANT', 'FIRST_VISIT')
  const release = deferred<void>()
  Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: (_name: string, callback: () => unknown) => release.promise.then(callback) } })
  const retrying = store.retry(); store.transition(); release.resolve(); await retrying
  expect(api.choose).toHaveBeenCalledTimes(1); expect(store.allowed()).toBe(false)
})

it('비회원으로 방문해도 모든 계정의 만료 marker를 정리하고 새 marker와 다른 저장소는 보존한다', async () => {
  const serverNow = Date.parse(context().checkedAt)
  for (const scope of ['MEMBER:1', 'MEMBER:2', 'GUEST']) localStorage.setItem(`toadzip.privacy.pending-stop:${scope}`, JSON.stringify({ commandId: `old-${scope}`, createdAt: serverNow - 180 * 86400000 }))
  localStorage.setItem('toadzip.privacy.pending-stop:MEMBER:3', JSON.stringify({ commandId: 'new', createdAt: serverNow - 179 * 86400000 }))
  localStorage.setItem('unrelated', 'keep')
  const { store, api } = setup(context('GUEST')); await store.refresh()
  for (const scope of ['MEMBER:1', 'MEMBER:2', 'GUEST']) expect(localStorage.getItem(`toadzip.privacy.pending-stop:${scope}`)).toBeNull()
  expect(localStorage.getItem('toadzip.privacy.pending-stop:MEMBER:3')).toContain('new')
  expect(localStorage.getItem('unrelated')).toBe('keep')
  expect(localStorage.getItem('toadzip.privacy.reselect-before')).toBe(String(serverNow))
  expect(api.choose).not.toHaveBeenCalled()
})
it('식별 marker 정리 후 새로고침·계정 전환으로 과거 허용이 살아나지 않고 새 명시적 허용만 인정한다', async () => {
  const granted = context('MEMBER', 'GRANTED', 1); granted.consent.decidedAt = '2026-10-08T00:00:00Z'
  localStorage.setItem('toadzip.privacy.pending-stop:MEMBER:2', JSON.stringify({ commandId: 'old-other', createdAt: Date.parse(granted.checkedAt) - 181 * 86400000 }))
  const first = setup(granted); await first.store.refresh()
  expect(first.store.allowed()).toBe(false); expect(first.store.getSnapshot().requiresChoice).toBe(true)
  const secondAccount = { ...granted, subject: { kind: 'MEMBER' as const, userId: '2', contextId: 'two' } }
  const { store, api } = setup(secondAccount); await store.refresh(); expect(store.allowed()).toBe(false)
  api.choose.mockResolvedValue({ ...secondAccount, consent: { ...granted.consent, revision: 2, decidedAt: '2026-10-09T00:00:01Z' } })
  await store.choose('GRANT', 'FIRST_VISIT')
  expect(store.allowed()).toBe(true); expect(store.getSnapshot().requiresChoice).toBe(false)
  await first.store.refresh(); expect(first.store.allowed()).toBe(false)
})
it('만료 정리 잠금 대기 중 새로 생긴 다른 계정 marker는 삭제하지 않는다', async () => {
  const serverNow = Date.parse(context().checkedAt); const key = 'toadzip.privacy.pending-stop:MEMBER:2'
  localStorage.setItem(key, JSON.stringify({ commandId: 'old', createdAt: serverNow - 181 * 86400000 }))
  const release = deferred<void>(); const entered = deferred<void>()
  Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: (name: string, callback: () => unknown) => {
    if (name === 'privacy-stop:MEMBER:2') { entered.resolve(); return release.promise.then(callback) }
    return Promise.resolve(callback())
  } } })
  const { store } = setup(context('GUEST')); const refreshing = store.refresh(); await entered.promise
  localStorage.setItem(key, JSON.stringify({ commandId: 'newer', createdAt: serverNow }))
  release.resolve(); await refreshing
  expect(localStorage.getItem(key)).toContain('newer'); expect(localStorage.getItem('toadzip.privacy.reselect-before')).toBeNull()
})
it('재선택 기준을 저장하지 못하면 식별 marker를 지우거나 수집을 허용하지 않는다', async () => {
  const key = 'toadzip.privacy.pending-stop:MEMBER:2'
  localStorage.setItem(key, JSON.stringify({ commandId: 'old', createdAt: Date.parse(context().checkedAt) - 181 * 86400000 }))
  const original = Storage.prototype.setItem
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, name, value) {
    if (name === 'toadzip.privacy.reselect-before') throw new Error('blocked')
    original.call(this, name, value)
  })
  const { store } = setup(context('MEMBER', 'GRANTED', 1)); await store.refresh()
  expect(localStorage.getItem(key)).toContain('old'); expect(store.allowed()).toBe(false); expect(store.getSnapshot().error).not.toBe('')
})
it('클라이언트 시계가 앞서더라도 서버 기준 180일이 지나기 전에 marker를 정리하지 않는다', async () => {
  const serverNow = Date.parse(context().checkedAt); vi.spyOn(Date, 'now').mockReturnValue(serverNow + 365 * 86400000)
  localStorage.setItem('toadzip.privacy.pending-stop:MEMBER:2', JSON.stringify({ commandId: 'recent', createdAt: serverNow - 86400000 }))
  const { store } = setup(context('GUEST')); await store.refresh()
  expect(localStorage.getItem('toadzip.privacy.pending-stop:MEMBER:2')).toContain('recent'); expect(localStorage.getItem('toadzip.privacy.reselect-before')).toBeNull()
})
it('서버 확인 실패 때는 정리하지 않고 재선택 기준은 과거 허용이 모두 만료되는 180일 후 정리한다', async () => {
  const serverNow = Date.parse(context().checkedAt)
  localStorage.setItem('toadzip.privacy.pending-stop:MEMBER:2', JSON.stringify({ commandId: 'old', createdAt: serverNow - 181 * 86400000 }))
  const { store, api } = setup(context('GUEST')); api.context.mockRejectedValueOnce(new Error('offline'))
  await store.refresh(); expect(localStorage.getItem('toadzip.privacy.pending-stop:MEMBER:2')).toContain('old')
  await store.refresh(); expect(localStorage.getItem('toadzip.privacy.reselect-before')).toBe(String(serverNow))
  api.context.mockResolvedValue({ ...context('GUEST'), checkedAt: new Date(serverNow + 180 * 86400000).toISOString() })
  await store.refresh(); expect(localStorage.getItem('toadzip.privacy.reselect-before')).toBeNull()
})
