import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { ConsentStore } from './consentStore'
import { privacyApi, PrivacyError, type ConsentContext, type EffectiveStatus } from './api'

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
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
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
it('동의 확인 중 겹친 탭 복귀 이벤트와 주기 요청은 같은 조회를 사용한다', async () => {
  vi.useFakeTimers()
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
  const response = deferred<ConsentContext>(); api.context.mockReturnValueOnce(response.promise)
  const cleanup = store.start()
  try {
    window.dispatchEvent(new Event('focus'))
    window.dispatchEvent(new Event('pageshow'))
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.context).toHaveBeenCalledOnce()
    response.resolve(context('MEMBER', 'GRANTED', 1))
    await vi.advanceTimersByTimeAsync(0)
    expect(store.allowed()).toBe(true)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.context).toHaveBeenCalledTimes(2)
  } finally { cleanup() }
})

it('숨긴 탭의 자동 확인은 보내지 않고 복귀할 때 서버를 확인한다', async () => {
  vi.useFakeTimers()
  Object.defineProperty(document, 'hidden', { configurable: true, value: true })
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
  const cleanup = store.start()
  try {
    window.dispatchEvent(new Event('focus'))
    window.dispatchEvent(new Event('pageshow'))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(api.context).not.toHaveBeenCalled()
    expect(store.allowed()).toBe(false)
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)
    expect(api.context).toHaveBeenCalledOnce()
    expect(store.allowed()).toBe(true)
  } finally { cleanup() }
})

it('오프라인 자동 조회는 보내지 않고 온라인 복귀 시 한 번 확인한다', async () => {
  vi.useFakeTimers()
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: false })
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
  const cleanup = store.start()
  try {
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(api.context).not.toHaveBeenCalled()
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
    window.dispatchEvent(new Event('online'))
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(0)
    expect(api.context).toHaveBeenCalledOnce()
    expect(store.allowed()).toBe(true)
  } finally { cleanup() }
})

it('숨기기 전에 시작한 조회를 복귀 후의 새 인증 확인으로 재사용하지 않는다', async () => {
  vi.useFakeTimers()
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
  const oldResponse = deferred<ConsentContext>(); api.context.mockReturnValueOnce(oldResponse.promise)
  const cleanup = store.start()
  try {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    const freshResponse = deferred<ConsentContext>(); api.context.mockReturnValueOnce(freshResponse.promise)
    document.dispatchEvent(new Event('visibilitychange'))
    expect(api.context).toHaveBeenCalledTimes(2)
    oldResponse.resolve(context('MEMBER', 'GRANTED', 1))
    await vi.advanceTimersByTimeAsync(0)
    expect(store.allowed()).toBe(false)
    freshResponse.resolve(context('MEMBER', 'DENIED', 2))
    await vi.advanceTimersByTimeAsync(0)
    expect(store.getSnapshot().context?.consent.decision).toBe('DENIED')
    expect(store.allowed()).toBe(false)
  } finally { cleanup() }
})

it('BFCache 복귀는 진행 중이던 과거 조회 대신 새 서버 상태를 확인한다', async () => {
  vi.useFakeTimers()
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
  const oldResponse = deferred<ConsentContext>(); api.context.mockReturnValueOnce(oldResponse.promise)
  const cleanup = store.start()
  try {
    const freshResponse = deferred<ConsentContext>(); api.context.mockReturnValueOnce(freshResponse.promise)
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    expect(api.context).toHaveBeenCalledTimes(2)
    oldResponse.resolve(context('MEMBER', 'GRANTED', 1))
    await vi.advanceTimersByTimeAsync(0)
    expect(store.allowed()).toBe(false)
    freshResponse.resolve(context('MEMBER', 'DENIED', 2))
    await vi.advanceTimersByTimeAsync(0)
    expect(store.getSnapshot().context?.consent.decision).toBe('DENIED')
  } finally { cleanup() }
})

it('자동 확인 중 계정이 바뀌면 이전 요청과 합치지 않고 새 계정을 확인한다', async () => {
  vi.useFakeTimers()
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
  const oldResponse = deferred<ConsentContext>(); api.context.mockReturnValueOnce(oldResponse.promise)
  const cleanup = store.start()
  try {
    const freshResponse = deferred<ConsentContext>(); api.context.mockReturnValueOnce(freshResponse.promise)
    localStorage.setItem('toadzip.privacy.auth-changed', 'new-account')
    window.dispatchEvent(new StorageEvent('storage', { key: 'toadzip.privacy.auth-changed' }))
    expect(api.context).toHaveBeenCalledTimes(2)
    oldResponse.resolve(context('MEMBER', 'GRANTED', 1))
    await vi.advanceTimersByTimeAsync(0)
    expect(store.allowed()).toBe(false)
    freshResponse.resolve(context('GUEST', 'DENIED', 2))
    await vi.advanceTimersByTimeAsync(0)
    expect(store.getSnapshot().context?.subject.kind).toBe('GUEST')
    expect(store.allowed()).toBe(false)
  } finally { cleanup() }
})

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
  expect(stopped).toHaveBeenCalledWith('pause')
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
  expect(stopped).toHaveBeenCalledWith('reset')
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

it('포커스 재확인은 gate를 먼저 닫고 pause 뒤 구독자에게 즉시 알린다', async () => {
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
  const cleanup = store.start()
  await vi.waitFor(() => expect(store.allowed()).toBe(true))
  const response = deferred<ConsentContext>()
  api.context.mockReturnValueOnce(response.promise)
  const order: string[] = []
  const removeStop = store.onStop(reason => order.push(`stop:${reason}:${store.allowed()}`))
  const unsubscribe = store.subscribe(() => order.push(`subscriber:${store.allowed()}`))
  try {
    window.dispatchEvent(new Event('focus'))
    expect(order).toEqual(['stop:pause:false', 'subscriber:false'])
    response.resolve(context('MEMBER', 'GRANTED', 1))
    await vi.waitFor(() => expect(store.allowed()).toBe(true))
    expect(order.at(-1)).toBe('subscriber:true')
  } finally { removeStop(); unsubscribe(); cleanup() }
})

it('탭 숨김과 복귀는 pause이며 같은 허용 확인 후 다시 수집한다', async () => {
  const { store } = setup(context('MEMBER', 'GRANTED', 1))
  const cleanup = store.start()
  await vi.waitFor(() => expect(store.allowed()).toBe(true))
  const stopped = vi.fn(); const changed = vi.fn()
  store.onStop(stopped); store.subscribe(changed)
  try {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
    expect(store.allowed()).toBe(false)
    expect(stopped).toHaveBeenLastCalledWith('pause')
    expect(changed).toHaveBeenCalledOnce()
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.waitFor(() => expect(store.allowed()).toBe(true))
    expect(stopped.mock.calls.every(([reason]) => reason === 'pause')).toBe(true)
  } finally { cleanup() }
})

it('확인 lease가 끝난 뒤 실제 동의도 만료되면 다음 중지는 reset이다', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] })
  const granted = context('MEMBER', 'GRANTED', 1)
  granted.consent.expiresAt = '2026-10-09T00:02:00Z'
  const { store, api } = setup(granted)
  const stopped = vi.fn(); store.onStop(stopped)
  await store.refresh()
  await vi.advanceTimersByTimeAsync(60_000)
  expect(stopped).toHaveBeenLastCalledWith('pause')
  await vi.advanceTimersByTimeAsync(60_000)
  api.context.mockRejectedValueOnce(new Error('offline'))
  await store.refresh()
  expect(stopped).toHaveBeenLastCalledWith('reset')
  expect(store.allowed()).toBe(false)
})

it('일시적인 서버 조회 실패는 기존 허용을 폐기하는 reset으로 알리지 않는다', async () => {
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
  await store.refresh()
  const stopped = vi.fn(); store.onStop(stopped)
  api.context.mockRejectedValueOnce(new Error('network'))
  await store.refresh()
  expect(stopped).toHaveBeenCalledWith('pause')
  expect(store.allowed()).toBe(false)
  await store.refresh()
  expect(store.allowed()).toBe(true)
})

it.each<EffectiveStatus>(['UNSET', 'DENIED', 'WITHDRAWN', 'EXPIRED', 'RECONSENT_REQUIRED'])(
  '서버의 %s 상태는 reset을 통지한다', async effectiveStatus => {
    const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
    await store.refresh()
    const stopped = vi.fn(); store.onStop(stopped)
    const next = context('MEMBER', 'GRANTED', 2)
    next.consent.effectiveStatus = effectiveStatus; next.collectionAllowed = false
    api.context.mockResolvedValue(next)
    await store.refresh()
    expect(store.allowed()).toBe(false)
    expect(stopped).toHaveBeenCalledWith('reset')
  },
)

it.each(['DENY', 'WITHDRAW'] as const)('%s 선택은 서버 응답을 기다리지 않고 reset을 알린다', async action => {
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
  await store.refresh()
  const response = deferred<ConsentContext>(); api.choose.mockReturnValueOnce(response.promise)
  const stopped = vi.fn(); store.onStop(stopped)
  const choosing = store.choose(action, 'SETTINGS')
  expect(store.allowed()).toBe(false)
  expect(stopped).toHaveBeenCalledWith('reset')
  await vi.waitFor(() => expect(api.choose).toHaveBeenCalledOnce())
  response.resolve(context('MEMBER', action === 'DENY' ? 'DENIED' : 'WITHDRAWN', 2))
  await choosing
  expect(store.allowed()).toBe(false)
})

it('인증 전환과 조회에서 발견한 회원 교체는 context-change로 구분한다', async () => {
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
  await store.refresh()
  const stopped = vi.fn(); store.onStop(stopped)
  const operation = store.beginAuthTransition('logout')
  expect(stopped).toHaveBeenLastCalledWith('context-change')
  await store.finishAuthTransition(operation)
  stopped.mockClear()
  const another = context('MEMBER', 'GRANTED', 1)
  another.subject.userId = '2'; another.subject.contextId = 'second-context'
  api.context.mockResolvedValue(another)
  await store.refresh()
  expect(stopped).toHaveBeenCalledWith('context-change')
  expect(stopped).not.toHaveBeenCalledWith('reset')
  expect(store.allowed()).toBe(true)
})

it.each(['clear', 'pending-stop', 'reselect'] as const)('다른 탭의 %s는 재조회 전에 reset을 알린다', async change => {
  const granted = context('MEMBER', 'GRANTED', 1)
  granted.consent.decidedAt = '2026-10-08T00:00:00Z'
  const { store, api } = setup(granted)
  const cleanup = store.start()
  await vi.waitFor(() => expect(store.allowed()).toBe(true))
  const response = deferred<ConsentContext>(); api.context.mockReturnValueOnce(response.promise)
  const stopped = vi.fn(); store.onStop(stopped)
  try {
    let key: string | null = null
    if (change === 'clear') localStorage.clear()
    else if (change === 'pending-stop') {
      key = 'toadzip.privacy.pending-stop:MEMBER:1'
      localStorage.setItem(key, JSON.stringify({ commandId: 'another-tab', createdAt: Date.parse(granted.checkedAt) }))
    } else {
      key = 'toadzip.privacy.reselect-before'
      localStorage.setItem(key, String(Date.parse(granted.checkedAt)))
    }
    window.dispatchEvent(new StorageEvent('storage', { key }))
    expect(store.allowed()).toBe(false)
    expect(stopped).toHaveBeenLastCalledWith('reset')
    response.resolve(granted)
  } finally { cleanup() }
})

it('허용 저장의 현재 응답이 이미 철회이면 새 ID를 보존하지 않도록 reset을 알린다', async () => {
  const { store, api } = setup(context('MEMBER', 'GRANTED', 1))
  await store.refresh()
  const stopped = vi.fn(); store.onStop(stopped)
  api.choose.mockResolvedValue(context('MEMBER', 'WITHDRAWN', 2))
  await store.choose('GRANT', 'SETTINGS')
  expect(stopped).toHaveBeenLastCalledWith('reset')
  expect(store.allowed()).toBe(false)
})

it('같은 비회원 scope라도 쿠키 context가 교체되면 이전 허용 세대를 폐기한다', async () => {
  const { store, api } = setup(context('GUEST', 'GRANTED', 1))
  await store.refresh()
  const generation = store.generation()
  const stopped = vi.fn(); store.onStop(reason => stopped(reason, store.allowed(), store.generation()))
  const replacement = context('GUEST', 'GRANTED', 1)
  replacement.subject.contextId = 'replacement-guest-context'
  api.context.mockResolvedValue(replacement)
  await store.refresh()
  expect(stopped).toHaveBeenCalledWith('consent-replaced', false, generation + 1)
  expect(stopped.mock.calls.some(([reason]) => reason === 'reset')).toBe(false)
  expect(store.generation()).toBe(generation + 1)
  expect(store.getSnapshot().context?.subject.contextId).toBe('replacement-guest-context')
  expect(store.allowed()).toBe(true)
})

it('교체된 비회원 context가 비허용이면 교체 신호 뒤에도 reset을 유지한다', async () => {
  const { store, api } = setup(context('GUEST', 'GRANTED', 1))
  await store.refresh()
  const stopped = vi.fn(); store.onStop(stopped)
  const replacement = context('GUEST')
  replacement.subject.contextId = 'ungranted-guest-context'
  api.context.mockResolvedValue(replacement)
  await store.refresh()
  expect(stopped.mock.calls.map(([reason]) => reason)).toEqual(['consent-replaced', 'reset'])
  expect(store.allowed()).toBe(false)
  expect(store.remainingConsentLifetime()).toBe(0)
})

it.each(['revision', 'decidedAt', 'scopeVersion'] as const)(
  '중간 철회를 놓쳤어도 허용의 %s 변경을 발견하면 이전 허용 세대를 폐기한다', async field => {
    const initial = context('MEMBER', 'GRANTED', 1)
    initial.consent.decidedAt = '2026-10-08T00:00:00Z'
    const { store, api } = setup(initial)
    await store.refresh()
    const generation = store.generation()
    const stopped = vi.fn(); store.onStop(stopped)
    const replacement: ConsentContext = { ...initial, consent: { ...initial.consent } }
    if (field === 'revision') replacement.consent.revision = 3
    else if (field === 'decidedAt') replacement.consent.decidedAt = '2026-10-09T00:00:00Z'
    else replacement.consent.scopeVersion = 'scope-2'
    api.context.mockResolvedValue(replacement)
    await store.refresh()
    expect(stopped).toHaveBeenCalledWith('consent-replaced')
    expect(stopped).not.toHaveBeenCalledWith('reset')
    expect(store.generation()).toBe(generation + 1)
    expect(store.allowed()).toBe(true)
  },
)

it('동일한 허용의 서버 확인 시각만 갱신되면 ID와 대기 행동의 세대를 보존한다', async () => {
  const initial = context('MEMBER', 'GRANTED', 1)
  initial.consent.decidedAt = '2026-10-08T00:00:00Z'
  const { store, api } = setup(initial)
  await store.refresh()
  const generation = store.generation()
  const stopped = vi.fn(); store.onStop(stopped)
  api.context.mockResolvedValue({ ...initial, consent: { ...initial.consent }, checkedAt: '2026-10-09T00:00:30Z' })
  await store.refresh()
  expect(stopped).not.toHaveBeenCalled()
  expect(store.generation()).toBe(generation)
  expect(store.allowed()).toBe(true)
})

it('동일 context의 과거 revision 응답은 새 허용 교체로 오인하지 않는다', async () => {
  const { store, api } = setup(context('MEMBER', 'GRANTED', 3))
  await store.refresh()
  const generation = store.generation()
  const stopped = vi.fn(); store.onStop(stopped)
  api.context.mockResolvedValue(context('MEMBER', 'GRANTED', 1))
  await store.refresh()
  expect(stopped).not.toHaveBeenCalled()
  expect(store.generation()).toBe(generation)
  expect(store.getSnapshot().context?.consent.revision).toBe(3)
  expect(store.allowed()).toBe(true)
})

it('검증 전에는 동의 보관 가능 기간을 제공하지 않고 서버 확인 지연도 남은 기간에서 뺀다', async () => {
  const now = vi.spyOn(performance, 'now').mockReturnValue(1000)
  const granted = context('MEMBER', 'GRANTED', 1)
  granted.consent.expiresAt = '2026-10-09T00:02:00Z'
  const { store, api } = setup(granted)
  expect(store.remainingConsentLifetime()).toBe(0)
  const response = deferred<ConsentContext>()
  api.context.mockReturnValueOnce(response.promise)
  const refreshing = store.refresh()
  now.mockReturnValue(3500)
  response.resolve(granted)
  await refreshing
  expect(store.remainingConsentLifetime()).toBe(117_500)
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2025-01-01T00:00:00Z'))
  now.mockReturnValue(4500)
  expect(store.remainingConsentLifetime()).toBe(116_500)
})

it.each([null, '', 'invalid', '2026-10-08T23:59:59Z'])(
  '만료 시각 %s가 없거나 유효한 미래 시각이 아니면 보관 가능 기간은 0이다', async expiresAt => {
    const granted = context('MEMBER', 'GRANTED', 1)
    granted.consent.expiresAt = expiresAt
    const { store } = setup(granted)
    await store.refresh()
    expect(store.remainingConsentLifetime()).toBe(0)
  },
)

it.each<EffectiveStatus>(['UNSET', 'DENIED', 'WITHDRAWN', 'EXPIRED', 'RECONSENT_REQUIRED'])(
  '미래 만료 시각이 있어도 서버의 %s 상태는 보관 가능 기간을 주지 않는다', async effectiveStatus => {
    const snapshot = context('MEMBER', 'GRANTED', 1)
    snapshot.consent.expiresAt = '2026-10-09T00:02:00Z'
    snapshot.consent.effectiveStatus = effectiveStatus
    snapshot.collectionAllowed = false
    const { store } = setup(snapshot)
    await store.refresh()
    expect(store.remainingConsentLifetime()).toBe(0)
  },
)

it('포커스 재확인 중에도 마지막 검증 만료 시각을 유지하고 재확인으로 기간을 연장하지 않는다', async () => {
  const now = vi.spyOn(performance, 'now').mockReturnValue(1000)
  const granted = context('MEMBER', 'GRANTED', 1)
  granted.consent.expiresAt = '2026-10-09T00:02:00Z'
  const { store, api } = setup(granted)
  const cleanup = store.start()
  try {
    await vi.waitFor(() => expect(store.allowed()).toBe(true))
    const response = deferred<ConsentContext>()
    api.context.mockReturnValueOnce(response.promise)
    now.mockReturnValue(2000)
    window.dispatchEvent(new Event('focus'))
    expect(store.allowed()).toBe(false)
    expect(store.remainingConsentLifetime()).toBe(119_000)
    response.resolve({ ...granted, checkedAt: '2026-10-09T00:00:01Z' })
    await vi.waitFor(() => expect(store.allowed()).toBe(true))
    expect(store.remainingConsentLifetime()).toBe(119_000)
  } finally { cleanup() }
})

it('명시적 철회는 서버 응답 전에 보관 가능 기간을 0으로 만들고 stop 구독자도 0을 읽는다', async () => {
  vi.spyOn(performance, 'now').mockReturnValue(1000)
  const granted = context('MEMBER', 'GRANTED', 1)
  granted.consent.expiresAt = '2026-10-09T00:02:00Z'
  const { store, api } = setup(granted)
  await store.refresh()
  expect(store.remainingConsentLifetime()).toBe(120_000)
  const stopped = vi.fn()
  store.onStop(reason => stopped(reason, store.remainingConsentLifetime()))
  const response = deferred<ConsentContext>()
  api.choose.mockReturnValueOnce(response.promise)
  const choosing = store.choose('WITHDRAW', 'SETTINGS')
  expect(store.remainingConsentLifetime()).toBe(0)
  expect(stopped).toHaveBeenCalledWith('reset', 0)
  await vi.waitFor(() => expect(api.choose).toHaveBeenCalledOnce())
  response.resolve(context('MEMBER', 'WITHDRAWN', 2))
  await choosing
  expect(store.remainingConsentLifetime()).toBe(0)
})

it('lease 중단 뒤 재확인이 없어도 실제 동의 만료 시 남은 기간은 0이지만 추가 reset 타이머는 없다', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] })
  const granted = context('MEMBER', 'GRANTED', 1)
  granted.consent.expiresAt = '2026-10-09T00:02:00Z'
  const { store } = setup(granted)
  const stopped = vi.fn(); store.onStop(stopped)
  await store.refresh()
  await vi.advanceTimersByTimeAsync(60_000)
  expect(store.allowed()).toBe(false)
  expect(store.remainingConsentLifetime()).toBe(60_000)
  expect(stopped).toHaveBeenCalledExactlyOnceWith('pause')
  await vi.advanceTimersByTimeAsync(60_000)
  expect(store.remainingConsentLifetime()).toBe(0)
  expect(stopped).toHaveBeenCalledOnce()
})

it('예상하지 못한 저장 오류의 내부 원문을 사용자에게 노출하지 않는다', async () => {
  const { store, api } = setup(); await store.refresh()
  api.choose.mockRejectedValue(new Error('SQL internal /api/v1/privacy failure'))
  await store.choose('GRANT', 'SETTINGS')
  expect(store.getSnapshot().error).toBe('선택을 저장하지 못했어요. 다시 시도해 주세요.')
  expect(store.allowed()).toBe(false)
})
