import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'
vi.mock('./public-housing/DefaultPublicHousingExplorer', async () => {
  const { useLocation } = await import('react-router')
  const { BookmarkButton } = await import('./user/bookmarks/BookmarkButton')
  const { MyPageView } = await import('./user/mypage/MyPageView')
  return { DefaultPublicHousingExplorer: () => useLocation().pathname.startsWith('/mypage')
    ? <MyPageView onOpenComplex={vi.fn()} onOpenAnnouncement={vi.fn()} onBrowseAnnouncements={vi.fn()} onClose={vi.fn()} />
    : <BookmarkButton type="COMPLEX" targetId="1" name="서울 주택" /> }
})
vi.mock('./public-housing/interest/notificationInterestRepository', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./public-housing/interest/notificationInterestRepository')>()),
  notificationInterestRepository: { loadStatus: vi.fn().mockResolvedValue({ emailConfirmed: false, targets: [] }), record: vi.fn().mockResolvedValue(undefined) },
}))
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear() })
it('지도에서 저장 중 마이페이지로 이동해도 저장 완료 목록이 반영된다', async () => {
  let complete!: () => void
  let saved = false
  const write = new Promise<void>((resolve) => { complete = resolve })
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/api/auth/me')) return Response.json({ id: 7, email: null })
    if (url.endsWith('/api/auth/csrf')) return Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })
    if (options?.method === 'PUT') { await write; saved = true; return new Response(null, { status: 204 }) }
    if (url.endsWith('/bookmarks/me')) return Response.json({ items: saved ? [{ type: 'COMPLEX', targetId: '1', name: '서울 주택', createdAt: '2026-10-26T09:00:00', available: true, address: null, status: null, applicationStartDate: null, applicationEndDate: null }] : [] })
    throw new Error(`Unexpected URL ${url}`)
  }))
  render(<MemoryRouter initialEntries={['/explore']}><App /></MemoryRouter>)
  const save = await screen.findByRole('button', { name: '서울 주택 북마크 저장' })
  await waitFor(() => expect(save).toBeEnabled())
  fireEvent.click(save)
  fireEvent.click(await screen.findByRole('link', { name: '마이페이지' }))
  await screen.findByText('저장한 단지가 없어요')
  await act(async () => complete())
  expect(await screen.findByText('서울 주택')).toBeVisible()
  expect(screen.getByRole('button', { name: '서울 주택 북마크 해제' })).toBeVisible()
})
