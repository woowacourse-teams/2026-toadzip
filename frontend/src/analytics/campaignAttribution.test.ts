import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { CAMPAIGN_LIFETIME, CAMPAIGN_STORAGE_KEY, campaignEntry, consumeCampaignEntry } from './campaignAttribution'

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-04T01:00:00Z'))
  sessionStorage.clear()
  history.replaceState({}, '', '/')
})
afterEach(() => { sessionStorage.clear(); campaignEntry(); vi.useRealTimers() })

it('출처와 고정 만료만 저장하고 다른 주소와 라우터 상태를 보존한다', () => {
  const state = { idx: 1, usr: { from: 'community' }, key: 'route' }
  history.replaceState(state, '', '/?utm_source=community_a&utm_campaign=sprint_3&utm_content=post_1&keep=yes#intro')
  expect(consumeCampaignEntry()).toBe(true)
  expect(location.pathname + location.search + location.hash).toBe('/?keep=yes#intro')
  expect(history.state).toEqual(state)
  expect(JSON.parse(sessionStorage.getItem(CAMPAIGN_STORAGE_KEY)!)).toEqual({
    source: { utm_source: 'community_a', utm_campaign: 'sprint_3', utm_content: 'post_1' },
    expiresAt: Date.now() + CAMPAIGN_LIFETIME,
  })
})

it('새로고침과 활동으로 30분을 연장하지 않고 만료된 출처를 삭제한다', async () => {
  history.replaceState({}, '', '/?utm_source=community_a')
  consumeCampaignEntry()
  vi.advanceTimersByTime(CAMPAIGN_LIFETIME - 1)
  vi.resetModules()
  const reload = await import('./campaignAttribution')
  expect(reload.consumeCampaignEntry()).toBe(false)
  expect(reload.campaignEntry()?.source).toEqual({ utm_source: 'community_a' })
  vi.advanceTimersByTime(1)
  expect(reload.campaignEntry()).toBeUndefined()
  expect(sessionStorage.getItem(CAMPAIGN_STORAGE_KEY)).toBeNull()
})

it('새 게시글은 이전 필드를 섞지 않고 새 30분을 시작한다', () => {
  history.replaceState({}, '', '/?utm_source=old&utm_campaign=sprint_3&utm_content=post_1')
  consumeCampaignEntry()
  vi.advanceTimersByTime(1000)
  history.replaceState({}, '', '/explore?utm_content=post_2')
  consumeCampaignEntry()
  expect(campaignEntry()).toEqual({ source: { utm_content: 'post_2' }, expiresAt: Date.now() + CAMPAIGN_LIFETIME })
})

it.each(['utm_source=a&utm_source=b', 'utm_content=private%40example.test', 'utm_campaign=https%3A%2F%2Fprivate.test'])(
  '중복·자유 입력 코드는 저장하지 않는다: %s', parameters => {
    history.replaceState({}, '', '/?' + parameters)
    expect(consumeCampaignEntry()).toBe(false)
    expect(campaignEntry()).toBeUndefined()
    expect(location.search).toBe('')
  },
)
