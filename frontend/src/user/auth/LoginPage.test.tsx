import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { LoginPage } from './LoginPage'

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs() })

it('비로그인 사용자는 카카오와 구글 로그인 경로를 선택할 수 있다', async () => {
  vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401, ok: false }))
  render(<MemoryRouter><LoginPage /></MemoryRouter>)
  expect(await screen.findByRole('link', { name: '카카오로 계속하기' })).toHaveAttribute(
    'href', 'https://api.example.com/api/auth/oauth2/authorization/kakao',
  )
  expect(screen.getByRole('link', { name: 'Google로 계속하기' })).toHaveAttribute(
    'href', 'https://api.example.com/api/auth/oauth2/authorization/google',
  )
})

it('로그인 실패 후 재시도 안내를 표시한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401, ok: false }))
  render(<MemoryRouter initialEntries={['/login?login=failed']}><LoginPage /></MemoryRouter>)
  expect(await screen.findByRole('alert')).toHaveTextContent('로그인을 완료하지 못했습니다.')
})

it('로그인한 사용자는 로그아웃할 수 있다', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce({ ok: true, json: async () => ({ id: 12 }) })
    .mockResolvedValueOnce({ ok: true, json: async () => ({ token: 'csrf', headerName: 'X-XSRF-TOKEN' }) })
    .mockResolvedValueOnce({ ok: true })
  vi.stubGlobal('fetch', fetchMock)
  render(<MemoryRouter><LoginPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))
  await waitFor(() => expect(screen.getByRole('link', { name: '카카오로 계속하기' })).toBeVisible())
  expect(fetchMock).toHaveBeenLastCalledWith(expect.stringContaining('/api/auth/logout'), expect.objectContaining({
    method: 'POST',
    credentials: 'include',
    headers: { 'X-XSRF-TOKEN': 'csrf' },
  }))
})
