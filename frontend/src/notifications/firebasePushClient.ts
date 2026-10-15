import type { PushDeviceRepository, PushDeviceState } from './pushDeviceRepository'
export interface PushSdk {
  permission(): NotificationPermission
  requestPermission(): Promise<NotificationPermission>
  register(): Promise<void>
  onRegistered(listener: (fid: string) => void): () => void
  onMessage(listener: () => void): () => void
}
export interface PushState { readonly status: 'loading' | 'off' | 'on' | 'busy' | 'error'; readonly message: string }
export interface PushClient {
  start(initialState?: PushDeviceState): Promise<void>; enable(): Promise<void>; disable(): Promise<void>; afterLogout(): Promise<void>
  subscribe(listener: () => void): () => void; getSnapshot(): PushState
  subscribeRefresh(listener: () => void): () => void; dispose(): void
}
export function createPushClient(userId: string, sdk: PushSdk, repository: PushDeviceRepository): PushClient {
  let state: PushState = { status: 'loading', message: '' }
  let bindingVersion: string | null = null
  let active = false
  let disposed = false
  let generation = 0
  let writes: Promise<void> = Promise.resolve()
  let completionMessage = ''
  let registrationDone: ((ok: boolean) => void) | null = null
  const controller = new AbortController()
  const listeners = new Set<() => void>()
  const refreshListeners = new Set<() => void>()
  const publish = (next: PushState) => { if (!disposed) { state = next; listeners.forEach(listener => listener()) } }
  const registrationError = () => { active = false; publish({ status: 'error', message: '기기 설정을 저장하지 못했어요. 다시 시도해 주세요.' }); registrationDone?.(false) }
  const stopRegistered = sdk.onRegistered(fid => {
    if (!active || disposed) return
    const version = generation
    writes = writes.then(async () => {
      if (!active || disposed || version !== generation) return
      const result = await repository.register(fid, userId, bindingVersion, controller.signal)
      // Retain this request's version for an immediately following disable, even if it just became inactive.
      bindingVersion = result.bindingVersion
      if (!active || disposed || version !== generation) return
      publish({ status: 'on', message: completionMessage }); completionMessage = ''; registrationDone?.(true)
    }).catch(() => { if (active && !disposed && version === generation) registrationError() })
  })
  const stopMessages = sdk.onMessage(() => { if (active && !disposed) refreshListeners.forEach(listener => listener()) })
  const stop = () => { active = false; generation++; registrationDone?.(false) }
  async function registerDevice(message: string) {
    completionMessage = message
    let timer: ReturnType<typeof setTimeout> | undefined
    const saved = new Promise<boolean>(resolve => {
      registrationDone = resolve
      timer = setTimeout(() => {
        stop()
        publish({ status: 'error', message: '기기 등록이 지연되고 있어요. 다시 시도해 주세요.' })
        resolve(false)
      }, 30_000)
    })
    try { await Promise.race([sdk.register().then(() => saved), saved]) }
    catch { if (!disposed && active) registrationError() }
    finally { clearTimeout(timer); registrationDone = null }
  }
  return {
    async start(initialState) {
      try {
        const current = initialState ?? await repository.current(controller.signal)
        if (disposed) return
        bindingVersion = current.bindingVersion
        active = (current.enabled || current.resumeAfterLogin === true) && sdk.permission() === 'granted'
        const restoring = active && current.resumeAfterLogin === true
        publish({ status: restoring ? 'busy' : active ? 'on' : 'off', message: restoring ? '기기 알림을 복원하고 있어요…' : '' })
        if (active) await registerDevice(restoring ? '이 기기에서 알림을 다시 받아요.' : '')
      } catch { if (!disposed) registrationError() }
    },
    async enable() {
      if (disposed || state.status === 'loading' || state.status === 'busy' || state.status === 'on') return
      // Invoke before the first await so the browser still has the button's user activation.
      const permission = sdk.permission() === 'default' ? sdk.requestPermission() : Promise.resolve(sdk.permission())
      publish({ status: 'busy', message: '브라우저 알림 권한을 확인하고 있어요…' })
      const version = ++generation
      let granted: NotificationPermission
      try { granted = await permission } catch { publish({ status: 'error', message: '브라우저 알림 권한을 확인하지 못했어요.' }); return }
      if (granted !== 'granted') { publish({ status: 'off', message: '브라우저 설정에서 알림 권한을 허용해 주세요.' }); return }
      if (disposed || version !== generation) return
      active = true
      publish({ status: 'busy', message: '기기에 알림을 연결하고 있어요…' })
      await registerDevice('알림 설정이 완료됐어요. 새 공고가 올라오면 알려 드릴게요.')
    },
    async disable() {
      if (disposed || state.status === 'busy') return
      stop(); publish({ status: 'busy', message: '기기 알림을 끄고 있어요…' })
      try {
        await writes
        if (disposed) return
        if (bindingVersion) await repository.disable(userId, bindingVersion, controller.signal)
        // Refresh only after our fenced disable succeeded; never retry a conflict with a newer owner's version.
        if (bindingVersion) bindingVersion = String(BigInt(bindingVersion) + 1n)
        // The Firebase installation is shared by tabs and accounts. Only the server binding is account-scoped.
        publish({ status: 'off', message: '이 기기의 알림을 껐어요.' })
      } catch { if (!disposed) publish({ status: 'error', message: '알림을 끄지 못했어요. 다시 시도해 주세요.' }) }
    },
    async afterLogout() { stop(); publish({ status: 'off', message: '' }) },
    subscribe(listener) { listeners.add(listener); return () => { listeners.delete(listener) } },
    getSnapshot() { return state },
    subscribeRefresh(listener) { refreshListeners.add(listener); return () => { refreshListeners.delete(listener) } },
    dispose() { stop(); disposed = true; controller.abort(); stopRegistered(); stopMessages(); listeners.clear(); refreshListeners.clear() },
  }
}
