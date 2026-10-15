import { pushDeviceMetadata } from './pushDeviceMetadata'
import { getApiBaseUrl } from '../api/apiBaseUrl'
import { record } from '../privacy/api'
import { NotificationApiError } from './notificationRepository'
export interface PushDeviceState { readonly deviceId: string | null; readonly enabled: boolean; readonly bindingVersion: string | null; readonly resumeAfterLogin?: boolean; readonly knownForUser?: boolean }
export interface PushDeviceRepository {
  current(signal: AbortSignal): Promise<PushDeviceState>
  register(fid: string, expectedUserId: string, expectedBindingVersion: string | null, signal: AbortSignal): Promise<PushDeviceState>
  disable(expectedUserId: string, bindingVersion: string, signal: AbortSignal): Promise<void>
}
export interface PushDeviceSummary {
  readonly deviceId: string; readonly bindingVersion: string; readonly platform: string | null; readonly browser: string | null
  readonly lastSeenAt: string; readonly enabled: boolean; readonly resumeAfterLogin: boolean; readonly current: boolean
}
export interface PushDeviceManagementRepository {
  devices(signal: AbortSignal): Promise<readonly PushDeviceSummary[]>
  disableDevice(deviceId: string, expectedUserId: string, bindingVersion: string, signal: AbortSignal): Promise<void>
}
function parseDevices(value: unknown): readonly PushDeviceSummary[] {
  if (!Array.isArray(value)) throw new Error('기기 목록을 확인하지 못했어요.')
  return value.map((item: unknown) => {
    const state = parseState(item)
    if (!record(item) || !state.deviceId || !state.bindingVersion || typeof item.current !== 'boolean'
      || !(item.platform === null || typeof item.platform === 'string') || !(item.browser === null || typeof item.browser === 'string')
      || typeof item.lastSeenAt !== 'string' || !Number.isFinite(Date.parse(item.lastSeenAt))) throw new Error('기기 목록을 확인하지 못했어요.')
    return { ...state, deviceId: state.deviceId, bindingVersion: state.bindingVersion, platform: item.platform, browser: item.browser,
      current: item.current, lastSeenAt: item.lastSeenAt, resumeAfterLogin: state.resumeAfterLogin === true }
  })
}
function parseState(value: unknown): PushDeviceState {
  if (!record(value) || typeof value.enabled !== 'boolean'
    || !(value.knownForUser === undefined || typeof value.knownForUser === 'boolean')
    || !(value.resumeAfterLogin === undefined || typeof value.resumeAfterLogin === 'boolean')
    || !(value.deviceId === null || typeof value.deviceId === 'string' && /^[0-9a-f-]{36}$/i.test(value.deviceId))
    || !(value.bindingVersion === null || typeof value.bindingVersion === 'string' && /^[1-9][0-9]{0,18}$/.test(value.bindingVersion))
    || ((value.deviceId === null) !== (value.bindingVersion === null)) || (value.enabled && value.deviceId === null)) throw new Error('기기 설정 응답을 확인하지 못했어요.')
  return { deviceId: value.deviceId, enabled: value.enabled, bindingVersion: value.bindingVersion, resumeAfterLogin: value.resumeAfterLogin === true, knownForUser: value.knownForUser === true || value.knownForUser === undefined && value.deviceId !== null }
}
export function createPushDeviceRepository(fetcher: typeof fetch = (...args) => globalThis.fetch(...args)): PushDeviceRepository & PushDeviceManagementRepository {
  const base = `${getApiBaseUrl()}/api/v1/notification-push-devices`
  async function request(path: string, signal: AbortSignal, method = 'GET', body?: object) {
    signal.throwIfAborted()
    const bounded = AbortSignal.any([signal, AbortSignal.timeout(15_000)])
    let headers: Record<string, string> | undefined
    if (body) {
      const response = await fetcher(`${getApiBaseUrl()}/api/auth/csrf`, { credentials: 'include', cache: 'no-store', signal: bounded })
      if (!response.ok) throw new NotificationApiError(response.status)
      const csrf: unknown = await response.json()
      if (!record(csrf) || csrf.headerName !== 'X-XSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token) throw new Error('기기 설정을 준비하지 못했어요.')
      headers = { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }
    }
    signal.throwIfAborted()
    const response = await fetcher(`${base}${path}`, { credentials: 'include', cache: 'no-store', signal: bounded, method, headers, body: body ? JSON.stringify(body) : undefined })
    if (!response.ok) throw new NotificationApiError(response.status)
    return response
  }
  return {
    async devices(signal) { return parseDevices(await (await request('', signal)).json()) },
    async disableDevice(deviceId, expectedUserId, bindingVersion, signal) {
      await request(`/${encodeURIComponent(deviceId)}`, signal, 'DELETE', { expectedUserId, bindingVersion })
    },
    async current(signal) { return parseState(await (await request('/current', signal)).json()) },
    async register(fid, expectedUserId, expectedBindingVersion, signal) {
      return parseState(await (await request('', signal, 'POST', { fid, expectedUserId, expectedBindingVersion, ...pushDeviceMetadata() })).json())
    },
    async disable(expectedUserId, bindingVersion, signal) { await request('/current', signal, 'DELETE', { expectedUserId, bindingVersion }) },
  }
}
export const pushDeviceRepository = createPushDeviceRepository()
