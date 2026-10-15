import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'

afterEach(() => vi.unstubAllGlobals())

it.each(['/notifications/cancel', '/admin/notification-cancellations'])(
  '제거된 비회원 알림 주소 %s는 찾을 수 없는 페이지를 표시한다', async path => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/api/admin/auth/me')
      ? Response.json({ loginIdentifier: 'admin' }) : Response.json([])))
    render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)
    expect(await screen.findByRole('heading', { name: '페이지를 찾을 수 없습니다.' })).toBeVisible()
    expect(screen.queryByRole('link', { name: '알림 취소 요청' })).not.toBeInTheDocument()
  },
)
