import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from '../App'

afterEach(() => vi.unstubAllGlobals())

it('비로그인 사용자도 헤더에서 의견 보내기로 이동한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 401 })))
  render(<MemoryRouter><App /></MemoryRouter>)
  fireEvent.click(screen.getByRole('link', { name: '의견 보내기' }))
  expect(await screen.findByRole('heading', { name: '의견 보내기' })).toBeVisible()
  expect(screen.getByRole('textbox', { name: '의견 내용' })).toBeVisible()
})

it('의견 보내기 주소로 직접 접근할 수 있다', () => {
  render(<MemoryRouter initialEntries={['/feedback']}><App /></MemoryRouter>)
  expect(screen.getByRole('heading', { name: '의견 보내기' })).toBeVisible()
})
