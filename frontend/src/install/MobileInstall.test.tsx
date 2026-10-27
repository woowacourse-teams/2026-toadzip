import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MobileInstallProvider } from './MobileInstallProvider'
import { MobileInstallAction, MobileInstallCard } from './MobileInstall'

let width: MediaQueryList
let display: MediaQueryList
function media(matches: boolean) {
  const target = new EventTarget()
  return Object.assign(target, { matches, media: '', onchange: null, addListener: vi.fn(), removeListener: vi.fn() }) as MediaQueryList
}
beforeEach(() => {
  localStorage.clear()
  width = media(true); display = media(false)
  vi.stubGlobal('matchMedia', (query: string) => query.includes('max-width') ? width : display)
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Android Chrome/130')
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
function setup() { return render(<MobileInstallProvider><MobileInstallCard /><MobileInstallAction /></MobileInstallProvider>) }
function promptEvent(outcome: 'accepted' | 'dismissed' = 'accepted', error = false) {
  const prompt = vi.fn(() => error ? Promise.reject(new Error('unavailable')) : Promise.resolve())
  const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), { prompt, userChoice: Promise.resolve({ outcome }) })
  act(() => { window.dispatchEvent(event) })
  return { event, prompt }
}
it('모바일에는 로그인·알림 클릭 없이 설치 카드를 보여주고 데스크톱으로 넓히면 숨긴다', () => {
  setup()
  expect(screen.getByRole('complementary', { name: '복덕방 홈 화면 추가' })).toBeVisible()
  act(() => { Object.defineProperty(width, 'matches', { value: false }); width.dispatchEvent(new Event('change')) })
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /홈 화면에 추가|앱처럼 사용하기/ })).not.toBeInTheDocument()
})
it('닫으면 24시간 동안 카드를 숨기되 메뉴에서 설치 안내를 다시 열 수 있다', () => {
  const view = setup()
  fireEvent.click(screen.getByRole('button', { name: '홈 화면 추가 안내 24시간 숨기기' }))
  view.unmount(); setup()
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /홈 화면에 추가/ }))
  expect(screen.getByRole('dialog', { name: '홈 화면에 복덕방 추가하기' })).toBeVisible()
})
it('닫은 뒤 24시간 직전까지 숨기고 24시간 후 방문하면 다시 노출한다', () => {
  const now = Date.now()
  const clock = vi.spyOn(Date, 'now').mockReturnValue(now)
  const view = setup()
  fireEvent.click(screen.getByRole('button', { name: '홈 화면 추가 안내 24시간 숨기기' }))
  view.unmount()
  clock.mockReturnValue(now + 24 * 60 * 60 * 1000 - 1)
  const revisit = setup()
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  revisit.unmount()
  clock.mockReturnValue(now + 24 * 60 * 60 * 1000)
  setup()
  expect(screen.getByRole('complementary')).toBeVisible()
})
it('데스크톱 브라우저의 기본 설치 동작은 가로채지 않는다', () => {
  Object.defineProperty(width, 'matches', { value: false })
  setup()
  const { event, prompt } = promptEvent()
  expect(event.defaultPrevented).toBe(false)
  expect(prompt).not.toHaveBeenCalled()
})
it('아이폰은 실제 캡처와 짧은 설명을 단계별로 보여주고 닫으면 초점이 돌아온다', () => {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone OS 26_0')
  setup()
  const button = within(screen.getByRole('complementary')).getByRole('button', { name: '앱처럼 사용하기' })
  button.focus(); fireEvent.click(button)
  expect(screen.getByRole('img', { name: /iPhone Safari/ })).toHaveAttribute('src', '/install/iphone-share.png')
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  expect(screen.getByRole('heading', { name: '홈 화면에 추가를 선택해요' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  expect(screen.getByRole('heading', { name: '추가를 누르면 끝!' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '확인했어요' }))
  expect(button).toHaveFocus()
})
it('지원 브라우저에서는 사용자 클릭 때만 설치창을 한 번 열고 appinstalled 후 카드를 숨긴다', async () => {
  setup()
  const { event, prompt } = promptEvent()
  expect(event.defaultPrevented).toBe(true)
  expect(prompt).not.toHaveBeenCalled()
  const button = within(screen.getByRole('complementary')).getByRole('button', { name: '앱처럼 사용하기' })
  await act(async () => { fireEvent.click(button); fireEvent.click(button) })
  expect(prompt).toHaveBeenCalledOnce()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  act(() => { window.dispatchEvent(new Event('appinstalled')) })
  expect(screen.queryByRole('button', { name: /홈 화면에 추가|앱처럼 사용하기/ })).not.toBeInTheDocument()
})
it('설치를 취소해도 완료로 처리하지 않고 다시 누르면 수동 안내를 연다', async () => {
  setup(); promptEvent('dismissed')
  const card = screen.getByRole('complementary')
  await act(async () => fireEvent.click(within(card).getByRole('button', { name: '앱처럼 사용하기' })))
  expect(within(card).getByRole('status')).toHaveTextContent('설치를 취소했어요')
  fireEvent.click(within(card).getByRole('button', { name: '앱처럼 사용하기' }))
  expect(screen.getByRole('heading', { name: 'Chrome에서 더 보기를 눌러요' })).toBeVisible()
})
it('설치창 호출 실패 시 캡처 안내로 복구한다', async () => {
  setup(); promptEvent('accepted', true)
  await act(async () => fireEvent.click(within(screen.getByRole('complementary')).getByRole('button', { name: '앱처럼 사용하기' })))
  expect(within(screen.getByRole('dialog')).getByRole('status')).toHaveTextContent('설치창을 열지 못했어요')
})
it('설치된 앱과 넓은 화면에서는 안내를 노출하지 않는다', () => {
  Object.defineProperty(display, 'matches', { value: true })
  setup()
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
})
it('저장소가 차단되어도 닫기와 메뉴 재진입이 작동한다', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  setup()
  fireEvent.click(screen.getByRole('button', { name: '홈 화면 추가 안내 24시간 숨기기' }))
  expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /홈 화면에 추가/ }))
  expect(screen.getByRole('dialog')).toBeVisible()
})
