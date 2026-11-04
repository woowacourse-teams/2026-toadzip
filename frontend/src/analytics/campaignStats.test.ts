import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { campaignEntry, consumeCampaignEntry, CAMPAIGN_LIFETIME } from './campaignAttribution'
import { captureCampaignStat, initializeCampaignStats } from './campaignStats'

const network = vi.fn<typeof fetch>()
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-04T08:23:45Z'))
  vi.stubGlobal('fetch', network)
  network.mockReset().mockResolvedValue(new Response(null, { status: 200 }))
  vi.stubEnv('MODE', 'development')
  vi.stubEnv('VITE_POSTHOG_KEY', 'phc_testpublictoken')
  vi.stubEnv('VITE_POSTHOG_HOST', 'https://us.i.posthog.com')
  vi.stubEnv('VITE_ANALYTICS_ENV', 'dev')
  vi.stubEnv('VITE_POSTHOG_LOCAL_ENABLED', 'true')
  localStorage.clear(); sessionStorage.clear()
  history.replaceState({}, '', '/?utm_source=community_a&utm_campaign=sprint_3&utm_content=post_1')
})
afterEach(() => {
  sessionStorage.clear(); campaignEntry()
  vi.useRealTimers(); vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.restoreAllMocks()
})
function payload(index = 0) { return JSON.parse(String(network.mock.calls[index][1]?.body)) }

it('유입과 후속 공고 조회를 세 UTM에 연결하되 방문자 식별값은 보내지 않는다', () => {
  initializeCampaignStats()
  history.replaceState({}, '', '/explore')
  expect(captureCampaignStat('view_announcement', {
    announcement_id: '22', complex_id: '17', user_id: 'private', email: 'private@example.test',
    distinct_id: 'private', $device_id: 'private', $session_id: 'private', event_id: crypto.randomUUID(),
    $set: { email: 'private' }, title: 'private', query: 'private', latitude: 37.123,
  })).toBe(true)
  expect(network.mock.calls[1][0]).toBe('https://us.i.posthog.com/i/v0/e/?ip=0')
  expect(network.mock.calls[1][1]).toMatchObject({ credentials: 'omit', referrerPolicy: 'no-referrer' })
  expect(payload(1)).toEqual({
    api_key: 'phc_testpublictoken', event: 'campaign_activity', distinct_id: 'campaign-statistics-v1',
    uuid: expect.any(String), timestamp: '2026-11-03T15:00:00.000Z',
    properties: { action: 'view_announcement', count: 1, complex_id: '17', announcement_id: '22',
      utm_source: 'community_a', utm_campaign: 'sprint_3', utm_content: 'post_1',
      environment: 'dev', schema_version: 1, measurement_kind: 'campaign_counts_v1',
      $process_person_profile: false, $geoip_disable: true, $ip: null },
  })
  expect(payload(0).properties.action).toBe('landing')
  expect(JSON.stringify(payload(1))).not.toMatch(/private|event_id|device_id|session_id|user_id|auth_state|current_url|referrer|latitude/)
})

it('새로고침은 새 유입을 세지 않고 저장된 출처를 재사용한다', () => {
  initializeCampaignStats()
  initializeCampaignStats()
  expect(network).toHaveBeenCalledOnce()
  expect(captureCampaignStat('notification_inbox_viewed')).toBe(true)
  expect(payload(1).properties.utm_content).toBe('post_1')
})

it('비동기 완료의 원래 출처도 30분 뒤에는 사용하지 않는다', () => {
  consumeCampaignEntry()
  const original = campaignEntry()
  vi.advanceTimersByTime(CAMPAIGN_LIFETIME)
  expect(captureCampaignStat('notification_preregistration_completed', {}, original)).toBe(false)
  expect(network).not.toHaveBeenCalled()
})

it('임의 행동, 계정 식별값을 공개 대상 ID로 보낸 값, UTM 없는 행동은 수집하지 않는다', () => {
  consumeCampaignEntry()
  expect(captureCampaignStat('auth_state_changed', { user_id: '17' })).toBe(false)
  expect(captureCampaignStat('view_complex', { complex_id: 'private@example.test' })).toBe(false)
  sessionStorage.clear(); campaignEntry()
  expect(captureCampaignStat('view_complex', { complex_id: '17' })).toBe(false)
  expect(network).not.toHaveBeenCalled()
})

it.each(['/admin', '/login', '/privacy/settings'])('비공개·설정 경로 %s에서는 전송하지 않는다', path => {
  consumeCampaignEntry()
  history.replaceState({}, '', path)
  expect(captureCampaignStat('page_view')).toBe(false)
  expect(network).not.toHaveBeenCalled()
})

it('제외 설정과 DNT를 따르고 누락된 프로젝트 설정으로 전송하지 않는다', () => {
  consumeCampaignEntry()
  localStorage.setItem('toadzip.analytics.disabled', 'true')
  expect(captureCampaignStat('page_view')).toBe(false)
  localStorage.clear()
  Object.defineProperty(navigator, 'doNotTrack', { configurable: true, get: () => undefined })
  vi.spyOn(navigator, 'doNotTrack', 'get').mockReturnValue('1')
  expect(captureCampaignStat('page_view')).toBe(false)
  vi.restoreAllMocks()
  vi.stubEnv('VITE_POSTHOG_KEY', '')
  expect(captureCampaignStat('page_view')).toBe(false)
  expect(network).not.toHaveBeenCalled()
})
