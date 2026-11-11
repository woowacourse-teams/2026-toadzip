import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from '../App'

afterEach(() => vi.unstubAllGlobals())

it('비로그인 사용자도 헤더에서 의견 보내기로 이동한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 401 })))
  render(<MemoryRouter initialEntries={['/explore']}><App /></MemoryRouter>)
  fireEvent.click(screen.getByRole('link', { name: '의견 보내기' }))
  expect(await screen.findByRole('heading', { name: '의견 보내기' })).toBeVisible()
  expect(screen.getByTitle('의견 설문')).toBeVisible()
})

it('의견 보내기 주소로 직접 접근할 수 있다', () => {
  render(<MemoryRouter initialEntries={['/feedback']}><App /></MemoryRouter>)
  expect(screen.getByRole('heading', { name: '의견 보내기' })).toBeVisible()
})

it('지도 상태를 유지한 채 모달을 열고 닫으면 진입 링크로 돌아간다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 401 })))
  render(<MemoryRouter initialEntries={['/explore?tab=announcements']}><App /></MemoryRouter>)
  const trigger = screen.getByRole('link', { name: '의견 보내기' })
  trigger.focus()
  const map = document.querySelector('.map-main')
  fireEvent.click(trigger)
  expect(await screen.findByRole('dialog', { name: '의견 보내기' })).toBeVisible()
  expect(document.querySelector('.map-main')).toBe(map)
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기 닫기' }))
  expect(screen.queryByRole('dialog', { name: '의견 보내기' })).not.toBeInTheDocument()
  expect(document.querySelector('.map-main')).toBe(map)
  expect(trigger).toHaveFocus()
})

it('직접 접근한 모달을 Escape로 닫으면 탐색 화면을 보여준다', () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 401 })))
  render(<MemoryRouter initialEntries={['/feedback']}><App /></MemoryRouter>)
  fireEvent(screen.getByRole('dialog', { name: '의견 보내기' }), new Event('cancel', { cancelable: true }))
  expect(screen.queryByRole('dialog', { name: '의견 보내기' })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '의견 보내기' })).toBeVisible()
})

it.each([false, true])('배경 클릭은 데스크톱에서만 모달을 닫는다: mobile=%j', mobile => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 401 })))
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: mobile,
    addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  render(<MemoryRouter initialEntries={['/feedback']}><App /></MemoryRouter>)
  const dialog = screen.getByRole('dialog', { name: '의견 보내기' })
  fireEvent.click(screen.getByTitle('의견 설문'))
  expect(dialog).toBeInTheDocument()
  fireEvent.click(dialog, { clientX: -10, clientY: -10 })
  if (mobile) expect(dialog).toBeInTheDocument()
  else expect(screen.queryByRole('dialog', { name: '의견 보내기' })).not.toBeInTheDocument()
})
