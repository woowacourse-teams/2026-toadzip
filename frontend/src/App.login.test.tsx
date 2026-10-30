import type { NaverMapProps } from './maps/naver/NaverMap'
import { rememberLoginReturn } from './user/auth/loginReturn'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from './App'

vi.mock('./public-housing/interest/notificationInterestRepository', async (importOriginal) => {
  const original = await importOriginal<typeof import('./public-housing/interest/notificationInterestRepository')>()
  return { ...original, notificationInterestRepository: original.createNotificationInterestRepository((...args) => globalThis.fetch(...args)) }
})

vi.mock('./maps/naver/NaverMap', () => ({ default: ({ cameraTarget }: NaverMapProps) => <output aria-label="지도 카메라">{JSON.stringify(cameraTarget)}</output> }))

function LocationDisplay() {
  const navigate = useNavigate()
  const location = useLocation()
  return <><button onClick={() => navigate(-1)}>브라우저 뒤로</button><output aria-label="현재 주소">{location.pathname}{location.search}{location.hash}</output></>
}

beforeEach(() => {
  localStorage.setItem('toadzip:welcome-completed', JSON.stringify({ expiresAt: Date.now() + 7 * 24 * 60 * 60 * 1000 }))
  vi.stubEnv('VITE_NAVER_MAPS_CLIENT_ID', '')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401, ok: false }))
})

afterEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

it('모바일 로그인은 정보 링크가 있는 페이지를 열고 뒤로 가면 지도 조건을 복원한다', async () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  render(<MemoryRouter initialEntries={['/explore?mapLat=37.5&mapLng=127&mapZoom=12#map']}><App /><LocationDisplay /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '로그인' }))
  expect(await screen.findByRole('heading', { name: '로그인', level: 1 })).toBeVisible()
  expect(screen.queryByRole('dialog', { name: '로그인' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '분석 설정' })).toHaveAttribute('href', '/privacy/settings')
  const loginUrl = new URL(screen.getByLabelText('현재 주소').textContent!, window.location.origin)
  expect(loginUrl.pathname).toBe('/login')
  expect(loginUrl.searchParams.get('returnTo')).toBe('/explore?mapLat=37.5&mapLng=127&mapZoom=12#map')
  fireEvent.click(screen.getByRole('link', { name: '지도로 돌아가기' }))
  await waitFor(() => expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/explore#map'))
})

it('모바일 실패 콜백은 로그인 페이지에서 재시도할 수 있다', async () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  render(<MemoryRouter initialEntries={['/explore?login=failed']}><App /></MemoryRouter>)
  expect(await screen.findByRole('heading', { name: '로그인', level: 1 })).toBeVisible()
  expect(screen.getByText('로그인을 완료하지 못했어요.')).toBeVisible()
})

it('기존 로그인 주소에 접근하면 메인 화면에서 로그인 모달을 연다', async () => {
  render(<MemoryRouter initialEntries={['/login?region=seoul#map']}><App /><LocationDisplay /></MemoryRouter>)
  expect(await screen.findByRole('dialog', { name: '로그인' })).toBeVisible()
  expect(screen.getByRole('link', { name: '공공주택 복덕방 홈' })).toBeVisible()
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/explore?region=seoul&login=required#map')
  fireEvent.click(screen.getByRole('button', { name: '로그인 닫기' }))
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/explore?region=seoul#map')
})

it('이전 로그인 콜백 주소로 인증이 완료되면 모달 없이 메인 화면으로 이동한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => url.endsWith('/notification-subscriptions/me') ? { userId: '7', settingsRevision: 0, targets: [] } : { id: 7 } })))
  render(<MemoryRouter initialEntries={['/login']}><App /><LocationDisplay /></MemoryRouter>)
  expect(await screen.findByText('마이페이지')).toBeVisible()
  expect(screen.getByRole('link', { name: '공공주택 복덕방 홈' })).toBeVisible()
  await waitFor(() => expect(screen.getByLabelText('현재 주소').textContent).toBe('/explore'))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('메인 화면으로 인증이 완료되면 로그인 상태만 표시한다', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => url.endsWith('/notification-subscriptions/me') ? { userId: '7', settingsRevision: 0, targets: [] } : { id: 7 } })))
  render(<MemoryRouter initialEntries={['/explore']}><App /></MemoryRouter>)
  expect(await screen.findByText('마이페이지')).toBeVisible()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('첫 방문의 실패 복귀는 로그인 모달을 닫은 뒤 공개 탐색을 유지한다', async () => {
  localStorage.clear()
  render(<MemoryRouter initialEntries={['/?login=failed']}><App /></MemoryRouter>)
  expect(await screen.findByRole('dialog', { name: '로그인' })).toBeVisible()
  expect(screen.queryByRole('dialog', { name: /살고 싶은 동네의/ })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '로그인 닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByRole('searchbox', { name: '지역, 지하철역, 단지, 공고 검색' })).toBeVisible()
})

it.each(['/login?login=failed', '/?login=failed'])(
  '%s로 실패하면 메인 화면 위 모달에서 재시도할 수 있다', async (path) => {
    render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)
    const dialog = await screen.findByRole('dialog', { name: '로그인' })
    expect(within(dialog).getByRole('alert')).toHaveTextContent('로그인을 완료하지 못했어요.')
    expect(within(dialog).getByRole('link', { name: 'Google로 로그인' })).toBeVisible()
    expect(screen.getByRole('link', { name: '공공주택 복덕방 홈' })).toBeVisible()
  },
)

it('보관함과 로그인 진입 정보가 함께 있어도 로그인 모달을 닫을 수 있다', async () => {
  render(<MemoryRouter initialEntries={['/login?inbox=open&region=seoul#map']}><App /><LocationDisplay /></MemoryRouter>)
  await screen.findByRole('dialog', { name: '로그인' })
  fireEvent.click(screen.getByRole('button', { name: '로그인 닫기' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/explore?region=seoul#map')
})

vi.mock('./privacy/usePrivacy', async (importOriginal) => {
  const original = await importOriginal<typeof import('./privacy/usePrivacy')>()
  const notices = [{ key: 'PRIVACY_POLICY', version: 'privacy-2026-10-09-v1' }, { key: 'NOTIFICATION_NOTICE', version: 'notification-2026-10-09-v1' }]
  return { ...original, usePrivacyNotices: () => ({ notices, error: false, retry: vi.fn() }) }
})

function mobileViewport() {
  vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
}
function expectSavedCamera() {
  const camera = JSON.parse(screen.getByLabelText('지도 카메라').textContent!)
  expect(camera).toMatchObject({ latitude: 37.58, longitude: 126.88, zoom: 15 })
}
it('리뷰 회귀: 지역이 선택되어도 로그인 취소는 저장한 지도 카메라를 유지한다', async () => {
  mobileViewport()
  render(<MemoryRouter initialEntries={['/explore?boundaryRegionCode=11440&mapLat=37.58&mapLng=126.88&mapZoom=15']}><App /><LocationDisplay /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '로그인' }))
  await screen.findByRole('heading', { name: '로그인', level: 1 })
  fireEvent.click(screen.getByRole('link', { name: '지도로 돌아가기' }))
  await waitFor(expectSavedCamera)
})
it('리뷰 회귀: 모바일 로그인에서 브라우저 뒤로 가도 지도 카메라를 복원한다', async () => {
  mobileViewport()
  render(<MemoryRouter initialEntries={['/explore?mapLat=37.58&mapLng=126.88&mapZoom=15']}><App /><LocationDisplay /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '로그인' }))
  await screen.findByRole('heading', { name: '로그인', level: 1 })
  fireEvent.click(screen.getByRole('button', { name: '브라우저 뒤로' }))
  await waitFor(expectSavedCamera)
})
it('리뷰 회귀: OAuth 성공 후 이미 열린 지도에도 저장한 위치와 선택 지역을 적용한다', async () => {
  mobileViewport()
  rememberLoginReturn('/explore?boundaryRegionCode=11440&mapLat=37.58&mapLng=126.88&mapZoom=15', true)
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => url.endsWith('/notification-subscriptions/me') ? { userId: '7', settingsRevision: 0, targets: [] } : { id: 7 } })))
  render(<MemoryRouter initialEntries={['/explore']}><App /><LocationDisplay /></MemoryRouter>)
  await screen.findByRole('link', { name: '마이페이지' })
  await waitFor(expectSavedCamera)
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('boundaryRegionCode=11440')
})
