import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { BrowserRouter } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from './App'
import { consentStore } from './privacy/consentStore'
import type { ConsentContext } from './privacy/api'

// The SDK-backed map is independent of routing and privacy choices.
vi.mock('./public-housing/DefaultPublicHousingExplorer', () => ({ DefaultPublicHousingExplorer: () => null }))

const notice = { key: 'ANALYTICS_NOTICE', version: 'analytics-v3', scopeVersion: 'analytics-scope-3', effectiveAt: '2026-10-09T00:00:00Z', contentHash: 'hash', documentUrl: '/api/v1/privacy/notices/ANALYTICS_NOTICE/analytics-v3' }
const policy = { ...notice, key: 'PRIVACY_POLICY', version: 'privacy-v3', scopeVersion: null }
let current: ConsentContext

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear()
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: async (_name: string, callback: () => unknown) => callback() } })
  current = { subject: { kind: 'GUEST', contextId: null }, consent: { decision: 'UNSET', effectiveStatus: 'UNSET', revision: 0, noticeVersion: null, scopeVersion: null, decidedAt: null, expiresAt: null }, requiredNoticeVersion: notice.version, requiredScopeVersion: notice.scopeVersion, collectionAllowed: false, checkedAt: '2026-10-09T00:00:00Z', maxAgeSeconds: 60 }
  consentStore.transition()
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/notices/current')) return Response.json({ documents: [notice, policy] })
    if (url.includes('/notices/PRIVACY_POLICY/')) return Response.json({ ...policy, format: 'markdown', content: '## 개인정보 처리 목적\n\n회원 이메일의 처리 목적을 안내합니다.' })
    if (url.endsWith('/analytics-context')) return Response.json(current)
    if (url.endsWith('/csrf')) return Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })
    if (url.endsWith('/guest-context')) { current = { ...current, subject: { kind: 'GUEST', contextId: 'guest-context' } }; return Response.json(current) }
    if (url.endsWith('/analytics/guest')) {
      const body = JSON.parse(String(options?.body))
      const decision = body.action === 'GRANT' ? 'GRANTED' : body.action === 'DENY' ? 'DENIED' : 'WITHDRAWN'
      current = { ...current, collectionAllowed: decision === 'GRANTED', consent: { ...current.consent, decision, effectiveStatus: decision, revision: current.consent.revision + 1, noticeVersion: notice.version, scopeVersion: notice.scopeVersion } }
      return Response.json({ receipt: { commandId: body.commandId }, current })
    }
    return new Response(null, { status: 401 })
  }))
})

afterEach(() => { cleanup(); act(() => consentStore.transition()); vi.unstubAllGlobals(); window.history.replaceState({}, '', '/') })

it.each(['/explore/', '/EXPLORE///', '/%65xplore', '/explor%65/'])('%s에서도 동의를 선택하고 개인정보 설정에서 철회할 수 있다', async path => {
  window.history.replaceState({}, '', `${path}?complexId=7#detail`)
  render(<BrowserRouter><App /></BrowserRouter>)
  expect(await screen.findByRole('heading', { name: '서비스 개선을 위한 이용 정보 수집' })).toBeVisible()
  expect(window.location.pathname + window.location.search + window.location.hash).toBe('/explore?complexId=7#detail')
  expect(consentStore.allowed()).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: '동의하기' }))
  await waitFor(() => expect(consentStore.allowed()).toBe(true))
  fireEvent.click(await screen.findByRole('button', { name: '로그인' }))
  fireEvent.click(screen.getByRole('link', { name: '분석 설정' }))
  expect(await screen.findByText(/현재 브라우저의 비회원 설정 · 허용/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '동의 철회' }))
  await waitFor(() => expect(consentStore.allowed()).toBe(false))
  expect(await screen.findByText(/현재 브라우저의 비회원 설정 · 철회/)).toBeVisible()
})

it('동의한 사용자가 랜딩으로 돌아오면 수집을 중지하고 개인정보처리방침을 열어도 다시 동의시키지 않는다', async () => {
  window.history.replaceState({}, '', '/explore')
  render(<BrowserRouter><App /></BrowserRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '동의하기' }))
  await waitFor(() => expect(consentStore.allowed()).toBe(true))
  fireEvent.click(screen.getByRole('link', { name: '공공주택 복덕방 홈' }))
  expect(consentStore.allowed()).toBe(false)
  expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('공공주택 찾기,')
  fireEvent.click(screen.getAllByRole('link', { name: '시작하기' })[0])
  await waitFor(() => expect(consentStore.allowed()).toBe(true))
  expect(screen.queryByRole('button', { name: '동의하기' })).not.toBeInTheDocument()
  fireEvent.click(await screen.findByRole('button', { name: '로그인' }))
  fireEvent.click(screen.getByRole('link', { name: '개인정보처리방침' }))
  expect(await screen.findByText('회원 이메일의 처리 목적을 안내합니다.')).toBeVisible()
  expect(screen.queryByRole('button', { name: '동의하기' })).not.toBeInTheDocument()
})
