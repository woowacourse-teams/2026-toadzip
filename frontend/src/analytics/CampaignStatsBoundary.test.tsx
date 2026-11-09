import { StrictMode } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { BrowserRouter, Link } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { CampaignStatsBoundary } from './CampaignStatsBoundary'
import { campaignEntry } from './campaignAttribution'
import { initializeCampaignStats } from './campaignStats'

afterEach(() => { cleanup(); sessionStorage.clear(); campaignEntry(); vi.unstubAllEnvs(); vi.unstubAllGlobals() })

it('소개 페이지부터 집계하며 StrictMode와 내부 이동이 새 유입을 만들지 않는다', () => {
  vi.stubEnv('MODE', 'development')
  vi.stubEnv('VITE_POSTHOG_KEY', 'phc_testpublictoken')
  vi.stubEnv('VITE_POSTHOG_HOST', 'https://us.i.posthog.com')
  vi.stubEnv('VITE_ANALYTICS_ENV', 'dev')
  vi.stubEnv('VITE_POSTHOG_LOCAL_ENABLED', 'true')
  localStorage.clear(); sessionStorage.clear()
  const network = vi.fn().mockResolvedValue(new Response('{}'))
  vi.stubGlobal('fetch', network)
  history.replaceState({}, '', '/?utm_source=community_a&utm_campaign=sprint_3&utm_content=post_1')
  initializeCampaignStats()
  render(<StrictMode><BrowserRouter><CampaignStatsBoundary /><Link to="/explore">탐색</Link></BrowserRouter></StrictMode>)
  const actions = () => network.mock.calls.map(([, options]) => JSON.parse(options.body).properties.action)
  expect(actions()).toEqual(['landing', 'page_view'])
  fireEvent.click(screen.getByRole('link', { name: '탐색' }))
  expect(location.pathname + location.search).toBe('/explore')
  expect(actions()).toEqual(['landing', 'page_view', 'page_view'])
  expect(campaignEntry()?.source.utm_content).toBe('post_1')
})
