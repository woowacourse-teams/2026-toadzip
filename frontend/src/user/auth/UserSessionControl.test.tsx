import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { UserSessionControl } from './UserSessionControl'

afterEach(() => { vi.unstubAllGlobals() })

it('비로그인 상태에는 로그인 링크를 표시한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401, ok: false }))

  render(<MemoryRouter><UserSessionControl /></MemoryRouter>)

  expect(await screen.findByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login')
})

it('로그인 상태와 로그아웃 버튼을 표시하고 로그아웃한다', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce({ ok: true, json: async () => ({ id: 7 }) })
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }),
    })
    .mockResolvedValueOnce({ ok: true })
  vi.stubGlobal('fetch', fetchMock)

  render(<MemoryRouter><UserSessionControl /></MemoryRouter>)

  expect(await screen.findByText('로그인됨')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

  await waitFor(() => expect(screen.getByRole('link', { name: '로그인' })).toBeVisible())
  expect(fetchMock).toHaveBeenLastCalledWith(
    expect.stringContaining('/api/auth/logout'),
    expect.objectContaining({
      method: 'POST',
      credentials: 'include',
      headers: { 'X-XSRF-TOKEN': 'csrf-token' },
    }),
  )
})

it('상태 확인 실패 시 다시 확인할 수 있다', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce({ status: 500, ok: false })
    .mockResolvedValueOnce({ status: 401, ok: false })
  vi.stubGlobal('fetch', fetchMock)

  render(<MemoryRouter><UserSessionControl /></MemoryRouter>)

  fireEvent.click(await screen.findByRole('button', { name: '로그인 상태 다시 확인' }))

  expect(await screen.findByRole('link', { name: '로그인' })).toBeVisible()
})
