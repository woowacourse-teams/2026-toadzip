import { campaignEntry, consumeCampaignEntry, safeCampaignSource, type CampaignEntry } from './campaignAttribution'
import { explorerPagePath } from '../public-housing/navigation/detailLocation'

const ACTIONS = new Set([
  'landing', 'page_view', 'view_complex', 'view_announcement', 'notification_inbox_viewed', 'notification_opened',
  'notification_cta_clicked', 'notification_preregistration_completed', 'search_executed',
  'apply_filter', 'announcement_source_clicked', 'document_download_requested',
])
const PATHS = new Set(['/', '/explore', '/feedback', '/notifications', '/mypage', '/mypage/notifications'])
const PUBLIC_IDS = ['complex_id', 'announcement_id'] as const

function configuration(): { key: string; environment: string } | undefined {
  if (import.meta.env.MODE === 'test') return
  const key = import.meta.env.VITE_POSTHOG_KEY?.trim()
  const environment = import.meta.env.VITE_ANALYTICS_ENV
  if (environment !== 'dev' && environment !== 'prod') return
  if (!key || !/^phc_[a-zA-Z0-9]+$/.test(key) || import.meta.env.VITE_POSTHOG_HOST !== 'https://us.i.posthog.com'
    || !PATHS.has(explorerPagePath(location.pathname))) return
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)
  if (local && (environment !== 'dev' || import.meta.env.VITE_POSTHOG_LOCAL_ENABLED !== 'true')) return
  if (location.hostname === 'bokduckbang.com' && environment !== 'prod') return
  if (location.hostname === 'dev.bokduckbang.com' && environment !== 'dev') return
  try { if (localStorage.getItem('toadzip.analytics.disabled') === 'true') return } catch { return }
  if (navigator.doNotTrack === '1' || Reflect.get(navigator, 'globalPrivacyControl') === true) return
  return { key, environment }
}

/** 출처별 발생 건수. 방문자·기기·세션·계정 식별값과 기존 이벤트의 연결 키를 받지 않는다. */
export function captureCampaignStat(action: string, input: Record<string, unknown> = {},
  entry: CampaignEntry | undefined = campaignEntry()): boolean {
  const config = configuration()
  if (!config || !ACTIONS.has(action) || !entry || entry.expiresAt <= Date.now()) return false
  const source = safeCampaignSource(entry.source)
  if (Object.keys(source).length === 0) return false
  const properties: Record<string, unknown> = {
    ...source, action, count: 1, environment: config.environment, schema_version: 1,
    measurement_kind: 'campaign_counts_v1', $process_person_profile: false, $geoip_disable: true, $ip: null,
  }
  for (const key of PUBLIC_IDS) {
    const value = input[key]
    if (typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)) properties[key] = value
  }
  if ((input.target_type === 'COMPLEX' || input.target_type === 'ANNOUNCEMENT')
    && typeof input.target_id === 'string' && /^[1-9]\d{0,18}$/.test(input.target_id)) {
    properties[input.target_type === 'COMPLEX' ? 'complex_id' : 'announcement_id'] = input.target_id
  }
  if (action === 'view_complex' && !properties.complex_id) return false
  if (action === 'view_announcement' && !properties.announcement_id) return false
  // 기존 SDK 이벤트의 정확한 행동 시각과 공유하지 않도록 일 단위로 내린다.
  const seoulOffset = 9 * 60 * 60 * 1000
  const day = new Date(Date.now() + seoulOffset)
  day.setUTCHours(0, 0, 0, 0)
  const timestamp = new Date(day.getTime() - seoulOffset).toISOString()
  try {
    void fetch('https://us.i.posthog.com/i/v0/e/?ip=0', {
      method: 'POST', credentials: 'omit', referrerPolicy: 'no-referrer', keepalive: true,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ api_key: config.key, event: 'campaign_activity',
        distinct_id: 'campaign-statistics-v1', uuid: crypto.randomUUID(), timestamp, properties }),
    }).catch(() => { /* 확정 업무 원장이 아니므로 응답 유실 시 행동을 다시 세지 않는다. */ })
    return true
  } catch { return false }
}

export function initializeCampaignStats(): void {
  if (consumeCampaignEntry()) captureCampaignStat('landing')
}
