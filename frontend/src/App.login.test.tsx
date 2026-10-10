import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from './App'

vi.mock('./public-housing/interest/notificationInterestRepository', async (importOriginal) => {
  const original = await importOriginal<typeof import('./public-housing/interest/notificationInterestRepository')>()
  return { ...original, notificationInterestRepository: original.createNotificationInterestRepository((...args) => globalThis.fetch(...args)) }
})

function LocationDisplay() {
  const location = useLocation()
  return <output aria-label="현재 주소">{location.pathname}{location.search}{location.hash}</output>
}

beforeEach(() => {
  localStorage.setItem('toadzip:welcome-completed', JSON.stringify({ expiresAt: Date.now() + 7 * 24 * 60 * 60 * 1000 }))
  vi.stubEnv('VITE_NAVER_MAPS_CLIENT_ID', '')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401, ok: false }))
})

afterEach(() => {
  localStorage.clear()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

it('기존 로그인 주소에 접근하면 메인 화면에서 로그인 모달을 연다', async () => {
  render(<MemoryRouter initialEntries={['/login?region=seoul#map']}><App /><LocationDisplay /></MemoryRouter>)
  expect(await screen.findByRole('dialog', { name: '로그인' })).toBeVisible()
  expect(screen.getByRole('link', { name: '공공주택 복덕방 홈' })).toBeVisible()
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/?region=seoul&login=required#map')
  fireEvent.click(screen.getByRole('button', { name: '로그인 닫기' }))
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/?region=seoul#map')
})

it('이전 로그인 콜백 주소로 인증이 완료되면 모달 없이 메인 화면으로 이동한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => url.endsWith('/notification-subscriptions/me') ? { userId: '7', settingsRevision: 0, targets: [] } : { id: 7 } })))
  render(<MemoryRouter initialEntries={['/login']}><App /><LocationDisplay /></MemoryRouter>)
  expect(await screen.findByText('마이페이지')).toBeVisible()
  expect(screen.getByRole('link', { name: '공공주택 복덕방 홈' })).toBeVisible()
  await waitFor(() => expect(screen.getByLabelText('현재 주소').textContent).toBe('/'))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('메인 화면으로 인증이 완료되면 로그인 상태만 표시한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => url.endsWith('/notification-subscriptions/me') ? { userId: '7', settingsRevision: 0, targets: [] } : { id: 7 } })))
  render(<MemoryRouter><App /></MemoryRouter>)
  expect(await screen.findByText('마이페이지')).toBeVisible()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('첫 방문의 실패 복귀는 로그인 모달만 먼저 표시하고 닫은 뒤 환영 안내를 표시한다', async () => {
  localStorage.clear()
  render(<MemoryRouter initialEntries={['/?login=failed']}><App /></MemoryRouter>)
  expect(await screen.findByRole('dialog', { name: '로그인' })).toBeVisible()
  expect(screen.queryByRole('dialog', { name: /살고 싶은 동네의/ })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '로그인 닫기' }))
  expect(screen.getByRole('dialog', { name: /살고 싶은 동네의/ })).toBeVisible()
})

it.each(['/login?login=failed', '/?login=failed'])(
  '%s로 실패하면 메인 화면 위 모달에서 재시도할 수 있다', async (path) => {
    render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)
    const dialog = await screen.findByRole('dialog', { name: '로그인' })
    expect(within(dialog).getByRole('alert')).toHaveTextContent('로그인을 완료하지 못했습니다.')
    expect(within(dialog).getByRole('link', { name: 'Google로 로그인' })).toBeVisible()
    expect(screen.getByRole('link', { name: '공공주택 복덕방 홈' })).toBeVisible()
  },
)

it('보관함과 로그인 진입 정보가 함께 있어도 로그인 모달을 닫을 수 있다', async () => {
  render(<MemoryRouter initialEntries={['/login?inbox=open&region=seoul#map']}><App /><LocationDisplay /></MemoryRouter>)
  await screen.findByRole('dialog', { name: '로그인' })
  fireEvent.click(screen.getByRole('button', { name: '로그인 닫기' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/?region=seoul#map')
})

vi.mock('./privacy/usePrivacy', async (importOriginal) => {
  const original = await importOriginal<typeof import('./privacy/usePrivacy')>()
  const notices = [{ key: 'PRIVACY_POLICY', version: 'privacy-2026-10-09-v1' }, { key: 'NOTIFICATION_NOTICE', version: 'notification-2026-10-09-v1' }]
  return { ...original, usePrivacyNotices: () => ({ notices, error: false, retry: vi.fn() }) }
})
