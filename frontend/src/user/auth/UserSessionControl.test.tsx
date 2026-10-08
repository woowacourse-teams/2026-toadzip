import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { UserSessionControl } from './UserSessionControl'

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs() })

it('로그인 버튼은 현재 화면에서 카카오와 구글 로그인 모달을 연다', async () => {
  vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401, ok: false }))

  render(<MemoryRouter><UserSessionControl /></MemoryRouter>)

  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  const login = await screen.findByRole('button', { name: '로그인' })
  login.focus()
  fireEvent.click(login)
  const dialog = screen.getByRole('dialog', { name: '로그인' })
  expect(within(dialog).getByRole('link', { name: '카카오로 계속하기' })).toHaveAttribute(
    'href', 'https://api.example.com/api/auth/oauth2/authorization/kakao',
  )
  expect(within(dialog).getByRole('link', { name: 'Google로 계속하기' })).toHaveAttribute(
    'href', 'https://api.example.com/api/auth/oauth2/authorization/google',
  )
  fireEvent.click(within(dialog).getByRole('button', { name: '로그인 닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(login).toHaveFocus()
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

  await waitFor(() => expect(screen.getByRole('button', { name: '로그인' })).toBeVisible())
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

  expect(await screen.findByRole('button', { name: '로그인' })).toBeVisible()
})

it('Escape로 모달을 닫고 로그인 버튼에 포커스를 돌린다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401, ok: false }))
  render(<MemoryRouter><UserSessionControl /></MemoryRouter>)
  const login = await screen.findByRole('button', { name: '로그인' })
  login.focus()
  fireEvent.click(login)
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(login).toHaveFocus()
})

it('모달 내용 클릭은 유지하고 배경 클릭은 닫는다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401, ok: false }))
  render(<MemoryRouter><UserSessionControl /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '로그인' }))
  fireEvent.click(screen.getByRole('heading', { name: '로그인' }))
  const dialog = screen.getByRole('dialog')
  vi.spyOn(dialog, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 100, 400, 400))
  fireEvent.click(dialog, { clientX: 120, clientY: 120 })
  expect(dialog).toBeVisible()
  fireEvent.click(dialog, { clientX: 20, clientY: 20 })
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

function LocationDisplay() {
  const location = useLocation()
  return <output aria-label="현재 주소">{location.pathname}{location.search}</output>
}

it('실패 안내에 포커스를 주고 닫으면 실패 상태만 제거하며 다시 열 때 안내를 지운다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401, ok: false }))
  render(<MemoryRouter initialEntries={['/?login=failed&region=seoul']}>
    <UserSessionControl /><LocationDisplay />
  </MemoryRouter>)
  const dialog = await screen.findByRole('dialog', { name: '로그인' })
  const alert = within(dialog).getByRole('alert')
  expect(alert).toHaveTextContent('로그인을 완료하지 못했습니다.')
  expect(alert).toHaveTextContent('다시 시도해 주세요.')
  expect(alert).toHaveFocus()
  fireEvent.click(within(dialog).getByRole('button', { name: '로그인 닫기' }))
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('/?region=seoul')
  fireEvent.click(screen.getByRole('button', { name: '로그인' }))
  expect(within(screen.getByRole('dialog')).queryByRole('alert')).not.toBeInTheDocument()
})

it('실패 복귀 후 세션 조회에 실패해도 로그인 제공자로 재시도할 수 있다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network')))
  render(<MemoryRouter initialEntries={['/?login=failed']}><UserSessionControl /></MemoryRouter>)
  const dialog = await screen.findByRole('dialog', { name: '로그인' })
  expect(within(dialog).getByRole('alert')).toHaveTextContent('다시 시도해 주세요.')
  expect(within(dialog).getByRole('link', { name: '카카오로 계속하기' })).toBeVisible()
})

it('지도 사이드바의 로그인 계정은 마이페이지에서 기존 로그아웃을 제공한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 1 }) }))
  render(<MemoryRouter><UserSessionControl presentation="rail" /></MemoryRouter>)
  const account = await screen.findByText('마이페이지')
  expect(account.closest('details')).not.toHaveAttribute('open')
  fireEvent.click(account)
  expect(account.closest('details')).toHaveAttribute('open')
  expect(screen.getByRole('button', { name: '로그아웃' })).toBeVisible()
})
