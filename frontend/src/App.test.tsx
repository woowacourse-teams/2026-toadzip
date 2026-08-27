import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.tsx'

describe('App', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('기본 화면을 표시한다', () => {
    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: '두꺼비집' })).toBeInTheDocument()
    expect(screen.getByText('프론트엔드 개발 환경이 준비되었습니다.')).toBeVisible()
  })

  it('존재하지 않는 경로에서 안내 화면을 표시한다', () => {
    render(
      <MemoryRouter initialEntries={['/unknown']}>
        <App />
      </MemoryRouter>,
    )

    expect(
      screen.getByRole('heading', { name: '페이지를 찾을 수 없습니다.' }),
    ).toBeVisible()
  })

  it('만료된 세션의 로그아웃 응답이 401이어도 로그인 상태를 지우고 다시 로그인한다', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' }))
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ message: '인증이 필요합니다.' }, 401))
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ loginIdentifier: 'admin', role: 'ADMIN' }))

    render(
      <MemoryRouter initialEntries={['/admin']}>
        <App />
      </MemoryRouter>,
    )

    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

    const loginIdentifierInput = await screen.findByLabelText('로그인 식별자')
    fireEvent.change(loginIdentifierInput, { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } })
    fireEvent.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('heading', { name: '관리자 페이지' })).toBeVisible()
  })

  it('StrictMode의 오래된 인증 상태 응답이 로그인 후 세션을 덮어쓰지 않는다', async () => {
    const firstSessionResponse = deferredResponse()
    const latestSessionResponse = deferredResponse()
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock
      .mockReturnValueOnce(firstSessionResponse.promise)
      .mockReturnValueOnce(latestSessionResponse.promise)
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ loginIdentifier: 'current-admin', role: 'ADMIN' }))

    render(
      <StrictMode>
        <MemoryRouter initialEntries={['/admin/login']}>
          <App />
        </MemoryRouter>
      </StrictMode>,
    )

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(2)
    })

    await act(async () => {
      latestSessionResponse.resolve(jsonResponse({ message: '인증이 필요합니다.' }, 401))
    })

    fireEvent.change(await screen.findByLabelText('로그인 식별자'), {
      target: { value: 'current-admin' },
    })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } })
    fireEvent.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByText('current-admin')).toBeVisible()

    await act(async () => {
      firstSessionResponse.resolve(jsonResponse({ loginIdentifier: 'stale-admin', role: 'ADMIN' }))
    })

    expect(screen.getByText('current-admin')).toBeVisible()
    expect(screen.queryByText('stale-admin')).not.toBeInTheDocument()
  })
})

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response
}

function deferredResponse(): {
  promise: Promise<Response>
  resolve: (response: Response) => void
} {
  let resolveResponse: (response: Response) => void
  const promise = new Promise<Response>((resolve) => {
    resolveResponse = resolve
  })
  return { promise, resolve: resolveResponse! }
}
