import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { BookmarkProvider } from '../bookmarks/BookmarkProvider'
import type { BookmarkItem, BookmarkRepository } from '../bookmarks/bookmarkRepository'
import { MyPage } from './MyPage'
const complex: BookmarkItem = { type: 'COMPLEX', targetId: '1', name: '서울 행복주택', createdAt: '2026-10-26T09:00:00', available: true, address: '서울시 중구', status: null, applicationStartDate: null, applicationEndDate: null }
const announcement: BookmarkItem = { ...complex, type: 'ANNOUNCEMENT', targetId: '2', name: '가을 공고', address: null, available: false, status: 'CLOSED' }
function Location() { const l = useLocation(); return <output aria-label="주소">{l.pathname}{l.search}{l.hash}</output> }
function setup(path = '/mypage', repository: BookmarkRepository = { list: vi.fn().mockResolvedValue([complex, announcement]), save: vi.fn(), remove: vi.fn() }, member = true) {
  render(<MemoryRouter initialEntries={[path]}><BookmarkProvider repository={repository} loadUser={async () => member ? { id: 7, email: null } : null}><MyPage /><Location /></BookmarkProvider></MemoryRouter>)
}
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear() })
it('기본 단지 목록과 공고 필터, 조회 불가, 실제 일정 부재를 표시한다', async () => {
  setup()
  expect(await screen.findByRole('link', { name: '서울 행복주택' })).toHaveAttribute('href', '/explore?complexId=1')
  expect(screen.getByRole('table', { name: '단지 북마크 내역' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '공고 1' }))
  expect(screen.getByText('가을 공고')).toBeVisible()
  expect(screen.queryByRole('link', { name: '가을 공고' })).not.toBeInTheDocument()
  expect(screen.getByText('현재 상세 정보를 조회할 수 없어요.')).toBeVisible()
  expect(screen.getByText(/확인된 일정이 없어요/)).toBeVisible()
  expect(screen.getByText('접수마감')).toBeVisible()
})
it('조회 실패를 빈 결과와 구분하고 재시도한다', async () => {
  setup('/mypage', { list: vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue([]), save: vi.fn(), remove: vi.fn() })
  fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }))
  expect(await screen.findByText('저장한 단지가 없어요')).toBeVisible()
})
it('이메일이 없는 계정을 표시하고 로그아웃 실패 시 화면을 유지한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(new Response(null, { status: 500 })))
  setup('/mypage/account')
  expect(await screen.findByText('등록된 이메일이 없어요')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('로그아웃하지 못했어요.')
  expect(screen.getByLabelText('주소')).toHaveTextContent('/mypage/account')
  expect(screen.getByText('로그인됨')).toBeVisible()
})
it('로그아웃 성공 시 지도 필터와 선택으로 돌아간다', async () => {
  sessionStorage.setItem('toadzip:last-explore-path', '/explore?complexId=1&mapZoom=9#map')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(new Response(null, { status: 204 })))
  setup('/mypage/account')
  fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))
  await waitFor(() => expect(screen.getByLabelText('주소')).toHaveTextContent('/explore?complexId=1&mapZoom=9#map'), { timeout: 5000 })
})
it('로그인 실패 복귀 시 마이페이지 안에서 실패 안내와 재시도를 제공한다', async () => {
  setup('/mypage/account?login=failed', undefined, false)
  expect(await screen.findByRole('dialog', { name: '로그인' })).toBeVisible()
  expect(screen.getByRole('alert')).toHaveTextContent('로그인을 완료하지 못했어요.')
})
it('이전 지도 경로를 새로고침 이후에도 복구한다', async () => {
  sessionStorage.setItem('toadzip:last-explore-path', '/explore?mapZoom=10&announcementId=2#detail')
  setup()
  await screen.findByText('내 북마크')
  expect(screen.getByRole('link', { name: /지도로 돌아가기/ })).toHaveAttribute('href', '/explore?mapZoom=10&announcementId=2#detail')
})
it('북마크 조회 장애에도 계정과 로그아웃을 사용할 수 있다', async () => {
  setup('/mypage/account', { list: vi.fn().mockRejectedValue(new Error('offline')), save: vi.fn(), remove: vi.fn() })
  expect(await screen.findByText('등록된 이메일이 없어요')).toBeVisible()
  expect(screen.getByRole('button', { name: '로그아웃' })).toBeEnabled()
})

it('내부 오류와 주소를 노출하지 않고 재시도 경로를 제공한다', async () => {
  setup('/mypage/bookmarks', { list: vi.fn().mockRejectedValue(new Error('Harness API fixture missing: /api/v1/bookmarks/me')), save: vi.fn(), remove: vi.fn() })
  expect(await screen.findByRole('alert')).toHaveTextContent('북마크를 불러오지 못했어요')
  expect(screen.queryByText(/Harness|\/api\//)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다시 시도' })).toBeEnabled()
})

it('마이페이지 공통 푸터에서 개인정보 링크에 접근하고 정보 탭은 노출하지 않는다', async () => {
  setup('/mypage/account')
  expect(await screen.findByRole('link', { name: '개인정보처리방침' })).toHaveAttribute('href', '/privacy')
  expect(screen.getByRole('link', { name: '분석 설정' })).toHaveAttribute('href', '/privacy/settings')
  expect(screen.getByRole('link', { name: '개인정보 문의·삭제 요청' })).toHaveAttribute('href', 'mailto:toadzip.official@gmail.com')
  expect(screen.queryByRole('link', { name: '정보 및 설정' })).not.toBeInTheDocument()
  expect(screen.getByRole('contentinfo')).toHaveTextContent('공공주택 복덕방')
})

it('북마크 세션은 정상이어도 알림 초기화 오류는 재시도할 수 있다', async () => {
  const { InterestContext } = await import('../../public-housing/interest/NotificationInterestContext')
  const refreshStatus = vi.fn()
  render(<MemoryRouter initialEntries={['/mypage/notifications']}>
    <BookmarkProvider repository={{ list: vi.fn().mockResolvedValue([]), save: vi.fn(), remove: vi.fn() }} loadUser={async () => ({ id: 7, email: null })}>
      <InterestContext value={{ mode: 'error', userId: null, blocked: false, clearingAll: false, batchError: '', clearAll: vi.fn(), retrying: false, requested: new Map(), targets: [], resetSession: vi.fn(), refreshStatus, request: vi.fn(), expose: vi.fn() }}><MyPage /></InterestContext>
    </BookmarkProvider>
  </MemoryRouter>)
  expect(await screen.findByRole('alert')).toHaveTextContent('알림 설정을 불러오지 못했어요')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(refreshStatus).toHaveBeenCalledOnce()
})

it('공고 분류 주소로 복귀하면 공고 표를 유지한다', async () => {
  setup('/mypage/bookmarks?type=ANNOUNCEMENT')
  expect(await screen.findByRole('table', { name: '공고 북마크 내역' })).toBeVisible()
  expect(screen.getByRole('button', { name: '공고 1' })).toHaveAttribute('aria-pressed', 'true')
})

it('북마크의 주소와 접수 일정은 이름·관리 사이의 별도 열에서 표시한다', async () => {
  const scheduled = { ...announcement, available: true, status: 'APPLYING', applicationStartDate: '2026-10-20', applicationEndDate: '2026-10-30' }
  setup('/mypage/bookmarks', { list: vi.fn().mockResolvedValue([complex, scheduled]), save: vi.fn(), remove: vi.fn() })
  const complexes = await screen.findByRole('table', { name: '단지 북마크 내역' })
  expect(within(complexes).getAllByRole('columnheader').map(cell => cell.textContent)).toEqual(['관심 대상', '주소', '관리'])
  const complexRow = within(complexes).getByRole('row', { name: /서울 행복주택/ })
  const cells = within(complexRow).getAllByRole('cell')
  expect(cells).toHaveLength(3)
  expect(cells[0]).not.toHaveTextContent('서울시 중구')
  expect(cells[1]).toHaveTextContent('서울시 중구')
  fireEvent.click(screen.getByRole('button', { name: '공고 1' }))
  const announcements = screen.getByRole('table', { name: '공고 북마크 내역' })
  expect(within(announcements).getAllByRole('columnheader').map(cell => cell.textContent)).toEqual(['접수 상태', '관심 대상', '접수 일정', '관리'])
  const announcementRow = within(announcements).getByRole('row', { name: /가을 공고/ })
  const announcementCells = within(announcementRow).getAllByRole('cell')
  expect(announcementCells).toHaveLength(4)
  expect(announcementCells[0]).toHaveTextContent('접수중')
  expect(announcementCells[1]).toHaveTextContent('가을 공고')
  expect(announcementCells[1]).not.toHaveTextContent('접수중')
  expect(within(announcements).getByRole('cell', { name: '2026-10-20 ~ 2026-10-30' })).toBeVisible()
})

it('접수 상태가 없는 공고도 맨 왼쪽 열에 공고문 확인을 표시한다', async () => {
  setup('/mypage/bookmarks?type=ANNOUNCEMENT', { list: vi.fn().mockResolvedValue([{ ...announcement, status: null }]), save: vi.fn(), remove: vi.fn() })
  const table = await screen.findByRole('table', { name: '공고 북마크 내역' })
  const row = within(table).getByRole('row', { name: /가을 공고/ })
  expect(within(row).getAllByRole('cell')[0]).toHaveTextContent('공고문 확인')
})
