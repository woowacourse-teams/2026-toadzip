import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { StrictMode } from 'react'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'

vi.mock('./public-housing/DefaultPublicHousingExplorer', () => ({
  DefaultPublicHousingExplorer: () => <h1>공공임대주택 지도</h1>,
}))

vi.mock('./public-housing/interest/notificationInterestRepository', async (importOriginal) => {
  const original = await importOriginal<typeof import('./public-housing/interest/notificationInterestRepository')>()
  return { ...original, notificationInterestRepository: original.createNotificationInterestRepository((...args) => globalThis.fetch(...args)) }
})

function NavigationProbe() {
  const location = useLocation()
  const navigate = useNavigate()
  return <><output aria-label="현재 주소">{location.pathname}{location.search}{location.hash}</output>
    <button onClick={() => void navigate(-1)}>뒤로</button></>
}

function open(path = '/') {
  return render(<StrictMode><MemoryRouter initialEntries={[path]}><App /><NavigationProbe /></MemoryRouter></StrictMode>)
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); sessionStorage.clear() })

it('비로그인 첫 방문에는 API 없이 서비스 소개와 시작하기를 표시한다', () => {
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  open()
  expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('공공주택 찾기,이제 한곳에서.')
  expect(screen.getAllByRole('link', { name: '시작하기' })).toHaveLength(3)
  expect(screen.getByRole('region', { name: '복덕방 사용법' })).toBeVisible()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(fetcher).not.toHaveBeenCalled()
})

it('기술 소개 없이 마지막 시작하기 영역으로 본문을 끝낸다', () => {
  vi.stubGlobal('fetch', vi.fn())
  open()
  const navigation = screen.getByRole('navigation', { name: '랜딩페이지 메뉴' })
  expect(within(navigation).getAllByRole('link').map(link => link.textContent)).toEqual([
    '서비스 소개', '사용법', '시작하기↗',
  ])
  const closing = screen.getByRole('region', { name: /살고 싶은 동네에,.*기다리고 있던 내 집/ })
  expect(within(closing).getByRole('link', { name: '시작하기' })).toHaveAttribute('href', '/explore')
  expect(closing.nextElementSibling).toBeNull()
  expect(screen.queryByRole('region', { name: /기술/ })).not.toBeInTheDocument()
})

it('푸터에서 지도·의견 보내기·개인정보처리방침으로 이동할 수 있다', () => {
  vi.stubGlobal('fetch', vi.fn())
  open()
  const footerLinks = within(screen.getByRole('navigation', { name: '사이트 안내' })).getAllByRole('link')
  expect(footerLinks.map(link => [link.textContent, link.getAttribute('href')])).toEqual([
    ['공공주택 지도', '/explore'], ['의견 보내기', '/feedback'], ['개인정보처리방침', '/privacy'],
  ])
})

it('시작하기는 로그인 없이 지도를 열고 뒤로가기는 랜딩으로 돌아온다', () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
  open()
  fireEvent.click(screen.getAllByRole('link', { name: '시작하기' })[0])
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/explore')
  expect(screen.getByRole('heading', { name: '공공임대주택 지도' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '뒤로' }))
  expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('공공주택 찾기,')
})

it.each([
  ['/?complexId=7', '/complexes/7'],
  ['/?announcementId=9&mapZoom=12', '/announcements/9?mapZoom=12'],
  ['/?boundaryRegionCode=11680', '/explore?boundaryRegionCode=11680'],
  ['/?complexMinDeposit=1000', '/explore?complexMinDeposit=1000'],
  ['/?login=failed', '/explore?login=failed'],
  ['/?inbox=open', '/explore?inbox=open'],
])('기존 탐색 공유 주소 %s의 상태를 보존한다', (path, expected) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
  open(path)
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent(expected)
  expect(screen.getByRole('heading', { name: '공공임대주택 지도' })).toBeVisible()
})

it('캠페인 파라미터나 사용법 앵커로 방문해도 랜딩을 표시한다', () => {
  open('/?utm_source=share#how-it-works')
  expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('공공주택 찾기,')
})

it('일반 루트 방문은 예전 로그인 복귀 표식이 남아 있어도 랜딩을 표시한다', () => {
  sessionStorage.setItem('toadzip:login-return-until', String(Date.now() + 60_000))
  open()
  expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('공공주택 찾기,')
})

it.each(['카카오톡으로 로그인', 'Google로 로그인'])('%s 선택 후 서버가 지정한 탐색 주소로 복귀한다', async (provider) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
  const { unmount } = open('/explore')
  fireEvent.click(await screen.findByRole('button', { name: '로그인' }))
  const link = screen.getByRole('link', { name: provider })
  link.addEventListener('click', (event) => event.preventDefault(), { once: true })
  fireEvent.click(link)
  unmount()
  open('/explore')
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/explore')
  expect(sessionStorage.getItem('toadzip:login-return-until')).toBeNull()
})

it('저장소 접근이 차단되어도 랜딩에서 탐색을 시작할 수 있다', () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked') })
  open()
  fireEvent.click(screen.getAllByRole('link', { name: '시작하기' })[0])
  expect(screen.getByRole('heading', { name: '공공임대주택 지도' })).toBeVisible()
})

it.each(['/explore', '/?login=failed'])('저장소가 차단되어도 로그인 복귀 주소 %s에서 탐색할 수 있다', path => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  open(path)
  expect(screen.getByLabelText('현재 주소').textContent).toBe(path === '/explore' ? '/explore' : '/explore?login=failed')
})


it.each(['/explore/', '/EXPLORE///', '/%65xplore', '/explor%65/'])('탐색 주소 %s를 정규화하면서 공유 조건과 앵커를 보존한다', (path) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
  open(`${path}?complexId=7&mapZoom=12#detail`)
  expect(screen.getByLabelText('현재 주소').textContent).toBe('/complexes/7?mapZoom=12#detail')
})

it('탐색 주소 정규화는 뒤로가기에 중간 주소를 남기지 않는다', () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
  render(<MemoryRouter initialEntries={['/', '/explore/?complexId=7']}><App /><NavigationProbe /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: '뒤로' }))
  expect(screen.getByLabelText('현재 주소').textContent).toBe('/')
})

it.each([
  ['/regions/28200', '/regions/28200'],
  ['/regions/28/national-rental', '/regions/28/national-rental'],
  ['/regions/28200?boundaryRegionCode=11&mapZoom=12', '/regions/28200?mapZoom=12'],
])('지역 목록 주소 %s는 주소를 유지한 채 지도 화면을 연다', (path, expected) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
  open(path)
  expect(screen.getByLabelText('현재 주소').textContent).toBe(expected)
  expect(screen.getByRole('heading', { name: '공공임대주택 지도' })).toBeVisible()
})

it.each(['/complexes/abc', '/announcements/0', '/regions/2820', '/regions/28200/unknown'])('잘못된 상세·지역 주소 %s는 없는 페이지로 보여 준다', (path) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
  open(path)
  expect(screen.getByRole('heading', { name: '페이지를 찾을 수 없어요.' })).toBeVisible()
})

it('서버 이동 뒤 상세 주소에 남은 상세 쿼리는 지우고 나머지 조건은 남긴다', () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
  open('/complexes/7?complexId=7&mapZoom=12')
  expect(screen.getByLabelText('현재 주소').textContent).toBe('/complexes/7?mapZoom=12')
})
