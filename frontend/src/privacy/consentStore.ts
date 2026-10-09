import { privacyApi, PrivacyError, record, type Choice, type ConsentContext } from './api'

const STOP_PREFIX = 'toadzip.privacy.pending-stop:'
const CHANGE_KEY = 'toadzip.privacy.changed'
const RESELECT_BEFORE_KEY = 'toadzip.privacy.reselect-before'
const AUTH_CHANGE_KEY = 'toadzip.privacy.auth-changed'
const AUTH_PENDING_PREFIX = 'toadzip.privacy.auth-pending:'
const OAUTH_TRANSITION_KEY = 'toadzip.privacy.oauth-transition'
const MAX_STOP_AGE = 180 * 24 * 60 * 60 * 1000
interface Stop { commandId: string; createdAt: number; choice?: Choice }
interface State { context: ConsentContext | null; loading: boolean; saving: boolean; error: string; pendingStop: boolean; requiresChoice: boolean }
const scopeOf = (context: ConsentContext) => context.subject.kind === 'MEMBER' ? `MEMBER:${context.subject.userId}` : 'GUEST'
export class ConsentStore {
  private state: State = { context: null, loading: true, saving: false, error: '', pendingStop: false, requiresChoice: false }
  private readonly listeners = new Set<() => void>()
  private readonly stops = new Map<string, Stop>()
  private epoch = 0
  private sequence = 0
  private leaseUntil = 0
  private leaseTimer: number | undefined
  private checkedAuthVersion: string | null = null
  private readonly authPending = new Map<string, number>()
  private authChannel: BroadcastChannel | null = null
  private started = false
  private retryChoice: { kind: 'MEMBER' | 'GUEST'; scope: string; choice: Choice; markerId?: string } | null = null
  private stopListeners = new Set<() => void>()
  private readonly api: typeof privacyApi
  constructor(api = privacyApi) { this.api = api }
  getSnapshot = () => this.state
  generation = () => this.epoch
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  onStop = (listener: () => void) => { this.stopListeners.add(listener); return () => { this.stopListeners.delete(listener) } }
  private update(next: Partial<State>) { this.state = { ...this.state, ...next }; this.listeners.forEach(listener => listener()) }
  private stop() {
    window.clearTimeout(this.leaseTimer)
    this.leaseTimer = undefined
    this.leaseUntil = 0
    this.stopListeners.forEach(listener => listener())
  }
  private readStop(scope: string): Stop | null {
    let saved = this.stops.get(scope) ?? null
    try {
      const value: unknown = JSON.parse(localStorage.getItem(`${STOP_PREFIX}${scope}`) ?? 'null')
      if (record(value) && typeof value.commandId === 'string' && typeof value.createdAt === 'number') {
        saved = { commandId: value.commandId, createdAt: value.createdAt }
        const choice = value.choice
        if (record(choice) && choice.commandId === value.commandId && (choice.action === 'DENY' || choice.action === 'WITHDRAW')
          && Number.isSafeInteger(choice.expectedRevision) && Number(choice.expectedRevision) >= 0
          && (choice.source === 'SETTINGS' || choice.source === 'FIRST_VISIT')
          && (scope === 'GUEST' ? typeof choice.contextId === 'string' : choice.expectedUserId === scope.slice(7))) {
          saved.choice = { commandId: value.commandId, expectedRevision: Number(choice.expectedRevision), action: choice.action, source: choice.source,
            ...(scope === 'GUEST' ? { contextId: String(choice.contextId) } : { expectedUserId: scope.slice(7) }) }
        }
      }
    } catch { return { commandId: 'storage-unavailable', createdAt: Date.now() } }
    return saved
  }
  private reselectBefore() {
    try {
      const value = Number(localStorage.getItem(RESELECT_BEFORE_KEY) ?? '0')
      return Number.isFinite(value) && value >= 0 ? value : Infinity
    } catch { return Infinity }
  }
  private requiresChoice(context: ConsentContext) {
    const before = this.reselectBefore()
    if (!Number.isFinite(before)) return true
    if (!before || context.consent.effectiveStatus !== 'GRANTED') return false
    const decidedAt = Date.parse(context.consent.decidedAt ?? '')
    return !Number.isFinite(decidedAt) || decidedAt <= before
  }
  private async cleanupStops(context: ConsentContext, current: () => boolean) {
    // The cutoff carries no account or command identity. Write it before erasing
    // an unchecked subject's marker, so an older grant can never be resurrected.
    const serverNow = Date.parse(context.checkedAt)
    await this.lock('privacy-retention', async () => {
      if (!current()) return
      const cutoff = this.reselectBefore()
      if (!Number.isFinite(cutoff)) throw new Error('Privacy storage unavailable')
      if (cutoff && serverNow - cutoff >= MAX_STOP_AGE) localStorage.removeItem(RESELECT_BEFORE_KEY)
      const scopes = new Set(this.stops.keys())
      for (let index = 0; index < localStorage.length; index++) {
        const key = localStorage.key(index)
        if (key?.startsWith(STOP_PREFIX)) scopes.add(key.slice(STOP_PREFIX.length))
      }
      for (const scope of scopes) {
        if (!current()) return
        const marker = this.readStop(scope)
        if (!marker || serverNow - marker.createdAt < MAX_STOP_AGE) continue
        await this.lock(`privacy-stop:${scope}`, () => {
          if (!current() || this.readStop(scope)?.commandId !== marker.commandId) return
          if (scope !== scopeOf(context) || context.consent.effectiveStatus === 'GRANTED') {
            const previous = this.reselectBefore()
            if (!Number.isFinite(previous)) throw new Error('Privacy storage unavailable')
            localStorage.setItem(RESELECT_BEFORE_KEY, String(Math.max(previous, serverNow)))
          }
          localStorage.removeItem(`${STOP_PREFIX}${scope}`)
          this.stops.delete(scope)
          if (this.retryChoice?.scope === scope && this.retryChoice.choice.commandId === marker.commandId) this.retryChoice = null
          this.notify()
        })
      }
    })
  }
  private async lock<T>(name: string, callback: () => T | Promise<T>): Promise<T> {
    if (!navigator.locks) return callback()
    return navigator.locks.request(name, callback)
  }
  private async writeStop(scope: string, marker: Stop) {
    this.stops.set(scope, marker)
    this.stop()
    this.update({ pendingStop: true })
    await this.lock(`privacy-stop:${scope}`, () => {
      try { localStorage.setItem(`${STOP_PREFIX}${scope}`, JSON.stringify(marker)); this.notify() }
      catch { this.update({ error: '이 브라우저에서는 저장이 제한돼요. 현재 화면의 수집은 중지했지만 선택 저장은 다시 확인해 주세요.' }) }
    })
  }
  private async updateStop(scope: string, commandId: string, choice?: Choice) {
    await this.lock(`privacy-stop:${scope}`, () => {
      const existing = this.readStop(scope)
      if (!existing || existing.commandId !== commandId) return
      const marker = { commandId, createdAt: existing.createdAt, ...(choice ? { choice } : {}) }
      this.stops.set(scope, marker)
      try { localStorage.setItem(`${STOP_PREFIX}${scope}`, JSON.stringify(marker)); this.notify() }
      catch { this.update({ error: '브라우저 저장이 제한돼요. 현재 수집은 중지되어 있습니다.' }) }
    })
  }
  private async removeStop(scope: string, commandId: string | undefined) {
    if (!commandId) return
    await this.lock(`privacy-stop:${scope}`, () => {
      if (this.readStop(scope)?.commandId !== commandId) return
      try { localStorage.removeItem(`${STOP_PREFIX}${scope}`) } catch { return }
      this.stops.delete(scope)
    })
  }
  private lease(context: ConsentContext, started: number) {
    let milliseconds = context.maxAgeSeconds * 1000
    if (context.consent.expiresAt) milliseconds = Math.min(milliseconds, Date.parse(context.consent.expiresAt) - Date.parse(context.checkedAt))
    return started + Math.max(0, milliseconds)
  }
  private renewLease(context: ConsentContext, started: number) {
    window.clearTimeout(this.leaseTimer)
    this.leaseUntil = this.lease(context, started)
    // SDK batches/retries must stop at expiry even if no new application event occurs.
    this.leaseTimer = window.setTimeout(() => {
      this.stop()
      this.update({})
    }, Math.max(0, this.leaseUntil - performance.now()))
  }
  private notify() { try { localStorage.setItem(CHANGE_KEY, crypto.randomUUID()) } catch { /* No grant is inferred from storage. */ } }
  private authentication() {
    try {
      const now = Date.now()
      for (const [id, expiresAt] of this.authPending) if (expiresAt <= now) this.authPending.delete(id)
      let pending = [...this.authPending.values()].some(expiresAt => expiresAt > now)
      for (let index = 0; index < localStorage.length; index++) {
        const key = localStorage.key(index)
        if (key?.startsWith(AUTH_PENDING_PREFIX) && Number(localStorage.getItem(key)) > now) pending = true
      }
      return { version: localStorage.getItem(AUTH_CHANGE_KEY) ?? '', pending }
    } catch { return null }
  }
  private authenticationMatches(version: string) {
    const current = this.authentication()
    return current !== null && !current.pending && current.version === version
  }
  private broadcastAuth(id: string, expiresAt: number) {
    try { this.authChannel?.postMessage({ id, expiresAt }) } catch { /* Another tab's verified lease still expires. */ }
  }
  private cleanupAuthTransitions() {
    try {
      for (const key of Object.keys(localStorage)) {
        if (key.startsWith(AUTH_PENDING_PREFIX) && Number(localStorage.getItem(key)) <= Date.now()) localStorage.removeItem(key)
      }
    } catch { /* Expired markers never authorize collection without a new server check. */ }
  }
  beginAuthTransition = (kind: 'oauth' | 'logout') => {
    this.transition()
    const id = crypto.randomUUID()
    const expiresAt = Date.now() + (kind === 'oauth' ? 10 * 60_000 : 60_000)
    this.authPending.set(id, expiresAt)
    try {
      localStorage.setItem(`${AUTH_PENDING_PREFIX}${id}`, String(expiresAt))
      localStorage.setItem(AUTH_CHANGE_KEY, id)
    } catch { /* Authentication remains usable; BroadcastChannel and the existing lease limit other tabs. */ }
    if (kind === 'oauth') {
      try { sessionStorage.setItem(OAUTH_TRANSITION_KEY, id) } catch { /* Abandoned transitions expire. */ }
    }
    this.broadcastAuth(id, expiresAt)
    return id
  }
  private settleAuthTransition(id?: string) {
    this.transition()
    if (id) this.authPending.delete(id)
    const signalId = crypto.randomUUID()
    try {
      // Invalidate verified leases before removing the final pending operation.
      localStorage.setItem(AUTH_CHANGE_KEY, signalId)
      if (id) localStorage.removeItem(`${AUTH_PENDING_PREFIX}${id}`)
      this.cleanupAuthTransitions()
    } catch { /* A storage failure must not prevent login or logout. */ }
    try { if (id && sessionStorage.getItem(OAUTH_TRANSITION_KEY) === id) sessionStorage.removeItem(OAUTH_TRANSITION_KEY) } catch { /* Optional browser coordination only. */ }
    this.broadcastAuth(id ?? signalId, 0)
  }
  finishAuthTransition = async (id: string) => { this.settleAuthTransition(id); await this.refresh() }
  private returningOAuth() {
    try { return sessionStorage.getItem(OAUTH_TRANSITION_KEY) ?? undefined } catch { return undefined }
  }
  allowed = () => {
    const context = this.state.context
    return !!context && !!navigator.locks && !document.hidden && !this.state.loading && !this.state.saving
      && !this.retryChoice && !this.state.error && performance.now() < this.leaseUntil && context.collectionAllowed && context.consent.effectiveStatus === 'GRANTED'
      && this.checkedAuthVersion !== null && this.authenticationMatches(this.checkedAuthVersion)
      && !this.readStop(scopeOf(context)) && !this.requiresChoice(context)
  }
  transition = () => { this.epoch++; this.sequence++; this.stop(); this.checkedAuthVersion = null; this.retryChoice = null; this.update({ context: null, loading: true, saving: false, error: '', pendingStop: false, requiresChoice: false }) }
  refresh = async () => {
    this.cleanupAuthTransitions()
    const authentication = this.authentication()
    if (!authentication || authentication.pending) { this.stop(); this.update({ loading: false, error: authentication ? '로그인 상태가 변경 중이에요. 분석을 잠시 중지합니다.' : '브라우저 저장이 제한되어 분석을 중지했어요.' }); return }
    const epoch = this.epoch
    let expectedEpoch = epoch
    const sequence = ++this.sequence
    const started = performance.now()
    try {
      const context = await this.api.context()
      if (epoch !== this.epoch || sequence !== this.sequence || this.state.saving) return
      if (!this.authenticationMatches(authentication.version)) { this.stop(); return }
      const old = this.state.context
      if (old && scopeOf(old) !== scopeOf(context)) { this.stop(); this.retryChoice = null; expectedEpoch = ++this.epoch }
      if (old && scopeOf(old) === scopeOf(context) && old.subject.contextId === context.subject.contextId && context.consent.revision < old.consent.revision) return
      const scope = scopeOf(context)
      await this.cleanupStops(context, () => expectedEpoch === this.epoch && sequence === this.sequence && !this.state.saving)
      if (expectedEpoch !== this.epoch || sequence !== this.sequence || this.state.saving) return
      if (!this.authenticationMatches(authentication.version)) { this.stop(); return }
      const pending = this.readStop(scope)
      this.checkedAuthVersion = authentication.version
      this.renewLease(context, started)
      this.update({ context, loading: false, pendingStop: !!pending, requiresChoice: this.requiresChoice(context), error: pending?.commandId === 'storage-unavailable' ? '브라우저 저장이 제한되어 분석을 중지했어요.' : this.retryChoice ? this.state.error : '' })
      if (!this.allowed()) this.stop()
      if (pending?.choice && !this.retryChoice) {
        this.retryChoice = { kind: context.subject.kind, scope, choice: pending.choice }
        void this.retry()
      }
    } catch {
      if (expectedEpoch !== this.epoch || sequence !== this.sequence) return
      this.stop(); this.update({ loading: false, error: '분석 설정을 불러오지 못했어요. 수집은 중지된 상태입니다.' })
    }
  }
  choose = async (action: Choice['action'], source: Choice['source']) => {
    const initial = this.state.context
    if (!initial || this.state.saving) return
    const authVersion = this.checkedAuthVersion
    if (authVersion === null || !this.authenticationMatches(authVersion)) { this.transition(); void this.refresh(); return }
    if (action === 'GRANT' && !navigator.locks) {
      this.stop(); this.update({ error: '이 브라우저에서는 선택을 안전하게 저장할 수 없어 분석을 허용하지 않습니다.' }); return
    }
    const epoch = this.epoch
    const scope = scopeOf(initial)
    const commandId = crypto.randomUUID()
    const oldMarker = this.readStop(scope)?.commandId
    this.retryChoice = null
    this.stop(); this.sequence++
    this.update({ saving: true, error: '' })
    if (action !== 'GRANT') await this.writeStop(scope, { commandId, createdAt: Date.now() })
    const perform = async () => {
      let current = initial
      if (initial.subject.kind === 'GUEST') current = await this.api.prepareGuest()
      if (epoch !== this.epoch || !this.authenticationMatches(authVersion) || scopeOf(current) !== scope) return
      const choice: Choice = { commandId, expectedRevision: current.consent.revision, action, source,
        ...(current.subject.kind === 'MEMBER' ? { expectedUserId: current.subject.userId } : { contextId: current.subject.contextId ?? undefined }),
        ...(action === 'GRANT' ? { noticeVersion: initial.requiredNoticeVersion, scopeVersion: initial.requiredScopeVersion } : {}) }
      this.retryChoice = { kind: current.subject.kind, scope, choice, markerId: oldMarker }
      if (action !== 'GRANT') await this.updateStop(scope, commandId, choice)
      if (epoch !== this.epoch || !this.authenticationMatches(authVersion)) return
      const started = performance.now()
      const result = await this.api.choose(current.subject.kind, choice)
      const verified = current.subject.kind === 'GUEST' ? await this.api.context() : result
      if (epoch !== this.epoch || !this.authenticationMatches(authVersion)) return
      if (scopeOf(verified) !== scope || verified.subject.contextId !== result.subject.contextId) throw new Error('브라우저 쿠키를 확인하지 못했습니다.')
      await this.removeStop(scope, action === 'GRANT' ? oldMarker : commandId)
      if (epoch !== this.epoch || !this.authenticationMatches(authVersion)) return
      this.retryChoice = null
      this.renewLease(verified, started)
      this.update({ context: verified, pendingStop: !!this.readStop(scope), requiresChoice: this.requiresChoice(verified) }); this.notify()
    }
    try { await (initial.subject.kind === 'GUEST' ? this.lock('privacy-guest-context', perform) : perform()) }
    catch (error) { if (epoch === this.epoch) this.handleError(error) }
    finally { if (epoch === this.epoch) this.update({ saving: false }) }
  }
  private handleError(error: unknown) {
    this.stop()
    if (error instanceof PrivacyError && error.status === 409) {
      const failed = this.retryChoice
      this.retryChoice = null
      if (failed && failed.choice.action !== 'GRANT') void this.updateStop(failed.scope, failed.choice.commandId)
      if (error.code === 'PRIVACY_NOTICE_CHANGED') window.dispatchEvent(new Event('privacy-notices-refresh'))
      this.update({ error: '설정이나 안내가 변경됐어요. 현재 내용을 확인하고 다시 선택해 주세요.' })
    } else this.update({ error: error instanceof Error ? error.message : '선택을 저장하지 못했어요. 다시 시도해 주세요.' })
  }
  retry = async () => {
    if (this.state.saving) return
    const retry = this.retryChoice
    const context = this.state.context
    if (!retry || !context || scopeOf(context) !== retry.scope) { await this.refresh(); return }
    const authVersion = this.checkedAuthVersion
    if (authVersion === null || !this.authenticationMatches(authVersion)) { this.transition(); void this.refresh(); return }
    const epoch = this.epoch
    this.stop(); this.sequence++; this.update({ saving: true, error: '' })
    const perform = async () => {
      if (epoch !== this.epoch || !this.authenticationMatches(authVersion)) return
      const started = performance.now()
      const result = await this.api.choose(retry.kind, retry.choice)
      const verified = retry.kind === 'GUEST' ? await this.api.context() : result
      if (epoch !== this.epoch || !this.authenticationMatches(authVersion)) return
      if (scopeOf(verified) !== retry.scope || verified.subject.contextId !== result.subject.contextId) throw new Error('브라우저 쿠키를 확인하지 못했습니다.')
      await this.removeStop(retry.scope, retry.choice.action === 'GRANT' ? retry.markerId : retry.choice.commandId)
      if (epoch !== this.epoch || !this.authenticationMatches(authVersion)) return
      this.retryChoice = null
      this.renewLease(verified, started)
      this.update({ context: verified, pendingStop: !!this.readStop(retry.scope), requiresChoice: this.requiresChoice(verified) }); this.notify()
    }
    try { await (retry.kind === 'GUEST' ? this.lock('privacy-guest-context', perform) : perform()) }
    catch (error) { if (epoch === this.epoch) this.handleError(error) }
    finally { if (epoch === this.epoch) this.update({ saving: false }) }
  }
  start = () => {
    try {
      this.authChannel = new BroadcastChannel('toadzip.privacy.auth')
      this.authChannel.onmessage = (event: MessageEvent<unknown>) => {
        if (!record(event.data) || typeof event.data.id !== 'string' || typeof event.data.expiresAt !== 'number' || !Number.isFinite(event.data.expiresAt)) return
        if (event.data.expiresAt > Date.now()) this.authPending.set(event.data.id, event.data.expiresAt)
        else this.authPending.delete(event.data.id)
        this.transition(); void this.refresh()
      }
    } catch { /* Storage events remain the primary cross-tab signal. */ }
    if (!this.started) { this.started = true; this.settleAuthTransition(this.returningOAuth()) }
    void this.refresh()
    const interval = window.setInterval(() => { if (!document.hidden) void this.refresh() }, 30_000)
    const focus = () => { this.stop(); void this.refresh() }
    const offline = () => { this.sequence++; this.stop(); this.update({ loading: false, error: '오프라인 상태예요. 분석을 중지했어요. 연결되면 다시 확인합니다.' }) }
    const visibility = () => { this.stop(); if (!document.hidden) void this.refresh() }
    const pageshow = () => { this.settleAuthTransition(this.returningOAuth()); void this.refresh() }
    const storage = (event: StorageEvent) => {
      if (event.key === AUTH_CHANGE_KEY || event.key?.startsWith(AUTH_PENDING_PREFIX)) {
        if (event.key.startsWith(AUTH_PENDING_PREFIX) && event.newValue === null) this.authPending.delete(event.key.slice(AUTH_PENDING_PREFIX.length))
        this.transition(); void this.refresh(); return
      }
      if (event.key === null || event.key === CHANGE_KEY || event.key === RESELECT_BEFORE_KEY || event.key.startsWith(STOP_PREFIX)) {
        if (event.key === null) this.stops.clear()
        else if (event.key.startsWith(STOP_PREFIX)) this.stops.delete(event.key.slice(STOP_PREFIX.length))
        this.stop(); void this.refresh()
      }
    }
    window.addEventListener('focus', focus); window.addEventListener('online', focus); window.addEventListener('offline', offline)
    window.addEventListener('pageshow', pageshow)
    window.addEventListener('storage', storage); document.addEventListener('visibilitychange', visibility)
    return () => { window.clearInterval(interval); window.removeEventListener('focus', focus); window.removeEventListener('online', focus); window.removeEventListener('offline', offline); window.removeEventListener('pageshow', pageshow); window.removeEventListener('storage', storage); document.removeEventListener('visibilitychange', visibility); this.authChannel?.close(); this.authChannel = null; this.transition() }
  }
}
export const consentStore = new ConsentStore()
export const analyticsCollectionAllowed = () => consentStore.allowed()
