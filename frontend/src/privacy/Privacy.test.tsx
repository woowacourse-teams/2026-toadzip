import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { NoticeContent, PrivacyBoundary, PrivacyMenu, PrivacyPolicyPage, PrivacySettingsPage } from './Privacy'
import { consentStore } from './consentStore'
import { type ConsentContext } from './api'
import { LoginModal } from '../user/auth/LoginModal'

const notice = { key: 'ANALYTICS_NOTICE', version: 'analytics-2026-10-09-v2', scopeVersion: 'analytics-scope-2', effectiveAt: '2026-10-09T00:00:00Z', contentHash: 'hash', documentUrl: '/api/v1/privacy/notices/ANALYTICS_NOTICE/analytics-2026-10-09-v2' }
const policy = { ...notice, key: 'PRIVACY_POLICY', version: 'privacy-2026-10-09-v2', scopeVersion: null }
let current: ConsentContext
let writes: Array<{ url: string; body: Record<string, unknown> }>
let failWrite: boolean
beforeEach(() => {
  localStorage.clear(); failWrite = false; writes = []
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: async (_name: string, callback: () => unknown) => callback() } })
  current = { subject: { kind: 'GUEST', contextId: null }, consent: { decision: 'UNSET', effectiveStatus: 'UNSET', revision: 0, noticeVersion: null, scopeVersion: null, decidedAt: null, expiresAt: null }, requiredNoticeVersion: notice.version, requiredScopeVersion: 'analytics-scope-2', collectionAllowed: false, checkedAt: '2026-10-09T00:00:00Z', maxAgeSeconds: 60 }
  consentStore.transition()
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/notices/current')) return Response.json({ documents: [notice, policy] })
    if (url.includes('/notices/ANALYTICS_NOTICE/')) return Response.json({ ...notice, format: 'markdown', content: '## 목적 및 보유기간\n\n분석 선택은 180일 유지합니다. 유효한 동의 후 이용 정보를 수집합니다.' })
    if (url.includes('/notices/PRIVACY_POLICY/')) return Response.json({ ...policy, format: 'markdown', content: '## 회원 이메일\n\n관리자의 회원 조회·검색에 사용하며, 회원 탈퇴 또는 목적 종료 시 삭제합니다.' })
    if (url.endsWith('/analytics-context')) return Response.json(current)
    if (url.endsWith('/csrf')) return Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })
    if (url.endsWith('/guest-context')) { current = { ...current, subject: { kind: 'GUEST', contextId: 'guest-context' } }; return Response.json(current) }
    if (url.endsWith('/analytics/guest')) {
      const body = JSON.parse(String(options?.body)); writes.push({ url, body })
      if (failWrite) return new Response(null, { status: 503 })
      const decision = body.action === 'GRANT' ? 'GRANTED' : body.action === 'DENY' ? 'DENIED' : 'WITHDRAWN'
      current = { ...current, collectionAllowed: decision === 'GRANTED', consent: { ...current.consent, decision, effectiveStatus: decision, revision: current.consent.revision + 1, noticeVersion: notice.version, scopeVersion: notice.scopeVersion } }
      return Response.json({ receipt: { commandId: body.commandId }, current })
    }
    throw new Error(`Unexpected request ${url}`)
  }))
})
afterEach(() => { act(() => consentStore.transition()); vi.unstubAllGlobals() })
function app(path = '/') { return <MemoryRouter initialEntries={[path]}><PrivacyBoundary /><Routes><Route path="/" element={<PrivacyMenu />} /><Route path="/privacy" element={<PrivacyPolicyPage />} /><Route path="/privacy/:key/:version" element={<PrivacyPolicyPage />} /><Route path="/privacy/settings" element={<PrivacySettingsPage />} /></Routes></MemoryRouter> }
it('안내문 강조를 표시하고 원문 HTML은 실행하지 않는다', () => {
  const { container } = render(<NoticeContent content={'## 이용 정보 안내\n\n개인을 식별할 수 없는 **익명 통계 데이터**로 수집합니다.\n\n<img src=x onerror=alert(1)> **문의**'} />)
  expect(screen.getByText('익명 통계 데이터').tagName).toBe('STRONG')
  expect(screen.getByText('문의').tagName).toBe('STRONG')
  expect(screen.getByText(/<img src=x onerror=alert\(1\)>/)).toBeVisible()
  expect(container.querySelector('img')).toBeNull()
  expect(container.textContent).not.toContain('**')
})
it('compact 카드에 실제 수집 범위와 직접 식별정보 미연결을 안내하고 상세를 접어 제공한다', async () => {
  render(app())
  expect(await screen.findByRole('heading', { name: '서비스 개선을 위한 이용 정보 수집' })).toBeVisible()
  expect(screen.getByText('이름·이메일·회원 ID와 연결하지 않습니다.').tagName).toBe('STRONG')
  expect(screen.queryByText('익명 통계 데이터')).not.toBeInTheDocument()
  expect(screen.getByText('동의하지 않아도 서비스를 이용할 수 있습니다.')).toBeVisible()
  expect(screen.getByRole('button', { name: /자세히 보기/ })).toHaveAttribute('aria-expanded', 'false')
  fireEvent.click(screen.getByRole('button', { name: /자세히 보기/ }))
  expect(await screen.findByText(/분석 선택은 180일/)).toBeVisible()
  expect(writes).toHaveLength(0)
})
it('명시적 허용과 비회원 쿠키 재확인이 끝나면 수집을 허용한다', async () => {
  render(app()); const grant = await screen.findByRole('button', { name: '동의하기' }); await waitFor(() => expect(grant).toBeEnabled())
  fireEvent.click(grant)
  await waitFor(() => expect(writes).toHaveLength(1))
  expect(writes[0]?.body).toMatchObject({ action: 'GRANT', contextId: 'guest-context', noticeVersion: notice.version, scopeVersion: notice.scopeVersion, expectedRevision: 0 })
  await waitFor(() => expect(screen.queryByLabelText('이용 정보 수집 선택')).not.toBeInTheDocument())
  expect(consentStore.allowed()).toBe(true)
})
it('거부 저장 실패를 성공으로 닫지 않고 동일 명령으로 재시도한다', async () => {
  failWrite = true; render(app()); fireEvent.click(await screen.findByRole('button', { name: '동의하지 않음' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('선택을 저장하지 못했어요.')
  expect(consentStore.allowed()).toBe(false); failWrite = false
  fireEvent.click(screen.getByRole('button', { name: '다시 확인' }))
  await waitFor(() => expect(writes).toHaveLength(2))
  expect(writes[1]?.body).toEqual(writes[0]?.body)
})
it('정책 고정 주소를 비회원도 읽을 수 있고 선택을 만들지 않는다', async () => {
  render(app(`/privacy/PRIVACY_POLICY/${policy.version}`))
  expect(await screen.findByText(/관리자의 회원 조회/)).toBeVisible()
  expect(screen.getByRole('link', { name: 'toadzip.official@gmail.com' })).toHaveAttribute('href', 'mailto:toadzip.official@gmail.com')
  expect(writes).toHaveLength(0)
})
it('설정은 적용 범위와 현재 선택을 표시하고 같은 화면에서 철회할 수 있다', async () => {
  current.subject.contextId = 'guest-context'; current.consent.decision = 'GRANTED'; current.consent.effectiveStatus = 'GRANTED'; current.consent.revision = 1
  render(app('/privacy/settings'))
  expect(await screen.findByText(/현재 브라우저의 비회원 설정 · 허용/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '동의 철회' }))
  await waitFor(() => expect(writes[0]?.body.action).toBe('WITHDRAW'))
  expect(await screen.findByText(/현재 브라우저의 비회원 설정 · 철회/)).toBeVisible()
})
it('소셜 로그인은 현재 정책 버전을 확인한 링크를 사용하고 별도 체크박스를 만들지 않는다', async () => {
  render(<MemoryRouter><LoginModal loginFailed={false} sessionError={false} onClose={() => {}} returnFocusRef={{ current: null }} /></MemoryRouter>)
  const login = await screen.findByRole('link', { name: 'Google로 로그인' })
  await waitFor(() => expect(login).toHaveAttribute('href', expect.stringContaining(`policyVersion=${policy.version}`)))
  expect(screen.getByRole('link', { name: '개인정보처리방침' })).toHaveAttribute('href', `/privacy/PRIVACY_POLICY/${policy.version}`)
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
})

it('만료한 다른 계정 marker를 정리한 브라우저는 과거 허용 대신 재선택 카드를 표시한다', async () => {
  current.subject.contextId = 'guest-context'; current.consent.decision = 'GRANTED'; current.consent.effectiveStatus = 'GRANTED'; current.consent.decidedAt = '2026-10-08T00:00:00Z'
  localStorage.setItem('toadzip.privacy.pending-stop:MEMBER:77', JSON.stringify({ commandId: 'expired', createdAt: Date.parse(current.checkedAt) - 181 * 86400000 }))
  render(app())
  expect(await screen.findByText(/이 브라우저에 오래 보관된 선택을 정리했어요/)).toBeVisible()
  expect(screen.getByRole('button', { name: '동의하기' })).toBeVisible()
  expect(localStorage.getItem('toadzip.privacy.pending-stop:MEMBER:77')).toBeNull()
})

it('공개 화면 이동은 검증된 동의를 폐기하거나 동일 페이지 수집을 재시작하지 않는다', async () => {
  current.subject.contextId = 'guest-context'
  current.consent.decision = 'GRANTED'; current.consent.effectiveStatus = 'GRANTED'
  current.collectionAllowed = true
  render(app('/privacy/settings'))
  await waitFor(() => expect(consentStore.allowed()).toBe(true))
  const generation = consentStore.generation()
  fireEvent.click(screen.getByRole('link', { name: '← 지도로 돌아가기' }))
  expect(await screen.findByLabelText('정보')).toBeVisible()
  expect(consentStore.generation()).toBe(generation)
  expect(consentStore.allowed()).toBe(true)
})
