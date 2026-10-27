import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { InterestContext } from '../../public-housing/interest/NotificationInterestContext'
import { BookmarkProvider } from '../bookmarks/BookmarkProvider'
import type { BookmarkItem, BookmarkRepository } from '../bookmarks/bookmarkRepository'
import { MyPageView } from './MyPageView'

const complex: BookmarkItem = { type: 'COMPLEX', targetId: '1', name: '서울 행복주택', createdAt: '2026-10-26T09:00:00', available: true, address: '서울시 중구', status: null, applicationStartDate: null, applicationEndDate: null }
const announcement: BookmarkItem = { ...complex, type: 'ANNOUNCEMENT', targetId: '2', name: '가을 공고', address: null, available: false, status: 'CLOSED' }
const handlers = { onOpenComplex: vi.fn(), onOpenAnnouncement: vi.fn(), onBrowseAnnouncements: vi.fn(), onClose: vi.fn() }

function Location() { const l = useLocation(); return <output aria-label="주소">{l.pathname}{l.search}{l.hash}</output> }
function repositoryOf(items: readonly BookmarkItem[]): BookmarkRepository { return { list: vi.fn().mockResolvedValue(items), save: vi.fn(), remove: vi.fn() } }
function setup(path = '/mypage', repository = repositoryOf([complex, announcement]), member = true) {
  render(<MemoryRouter initialEntries={[path]}><BookmarkProvider repository={repository} loadUser={async () => member ? { id: 7, email: null } : null}>
    <MyPageView {...handlers} /><Location />
  </BookmarkProvider></MemoryRouter>)
}

beforeEach(() => { vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-10-27T10:00:00')) })
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.clearAllMocks(); sessionStorage.clear() })

it('북마크 관리는 단지를 먼저 보여주고 탭으로 공고를 고르며 조회할 수 없는 항목은 열지 않는다', async () => {
  setup('/mypage/bookmarks')
  const saved = await screen.findByRole('region', { name: '북마크 관리' })
  fireEvent.click((await within(saved).findByText('서울 행복주택')).closest('button')!)
  expect(handlers.onOpenComplex).toHaveBeenCalledWith('1')
  fireEvent.click(within(saved).getByRole('tab', { name: '공고 1' }))
  expect(screen.getByLabelText('주소')).toHaveTextContent('/mypage/bookmarks?type=ANNOUNCEMENT')
  expect(within(saved).getByText('가을 공고').closest('button')).toBeDisabled()
  expect(within(saved).getByText('지금은 상세 정보를 볼 수 없어요')).toBeVisible()
  expect(within(saved).getByText('접수마감')).toBeVisible()
})

it('공고 북마크는 접수 마감이 가까운 순서로 정렬하고 가장 급한 공고를 요약에 D-day와 함께 보여준다', async () => {
  const applyingLater = { ...announcement, targetId: '3', name: '늦게 마감하는 공고', available: true, status: 'APPLYING', applicationStartDate: '2026-10-20', applicationEndDate: '2026-11-05' }
  const applyingSoon = { ...applyingLater, targetId: '4', name: '곧 마감하는 행복주택', applicationEndDate: '2026-10-31' }
  const upcoming = { ...applyingLater, targetId: '5', name: '접수 전 공고', status: 'BEFORE_APPLICATION', applicationStartDate: '2026-11-10', applicationEndDate: '2026-11-15' }
  setup('/mypage/bookmarks?type=ANNOUNCEMENT', repositoryOf([announcement, upcoming, applyingLater, applyingSoon]))
  const saved = await screen.findByRole('region', { name: '북마크 관리' })
  await within(saved).findByText('곧 마감하는 행복주택')
  expect(within(saved).getAllByRole('listitem').map(item => item.querySelector('strong')?.textContent))
    .toEqual(['곧 마감하는 행복주택', '늦게 마감하는 공고', '접수 전 공고', '가을 공고'])
  expect(within(saved).queryByText('D-4')).not.toBeInTheDocument()
  const urgent = screen.getByText('곧 마감하는 공고').closest('section')!
  expect(within(urgent).getByLabelText('접수 마감까지 4일')).toHaveTextContent('D-4')
  fireEvent.click(within(urgent).getByRole('button', { name: /곧 마감하는 행복주택/ }))
  expect(handlers.onOpenAnnouncement).toHaveBeenCalledWith('4')
})

it('분류 탭은 방향키와 Home·End로 이동한다', async () => {
  setup('/mypage/bookmarks')
  const complexes = await screen.findByRole('tab', { name: '단지 1' })
  complexes.focus()
  fireEvent.keyDown(complexes, { key: 'ArrowRight' })
  expect(screen.getByRole('tab', { name: '공고 1' })).toHaveFocus()
  expect(screen.getByRole('tab', { name: '공고 1' })).toHaveAttribute('aria-selected', 'true')
  fireEvent.keyDown(document.activeElement!, { key: 'Home' })
  expect(screen.getByRole('tab', { name: '단지 1' })).toHaveAttribute('aria-selected', 'true')
})

it('조회 실패를 빈 결과와 구분하고 내부 오류 없이 재시도한다', async () => {
  setup('/mypage/bookmarks', { list: vi.fn().mockRejectedValueOnce(new Error('Harness API fixture missing: /api/v1/bookmarks/me')).mockResolvedValue([]), save: vi.fn(), remove: vi.fn() })
  expect(await screen.findByRole('alert')).toHaveTextContent('북마크를 불러오지 못했어요')
  expect(screen.queryByText(/Harness|\/api\//)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '로그아웃' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(await screen.findByText('저장한 단지가 없어요')).toBeVisible()
})

it('이메일이 없는 계정을 표시하고 로그아웃 실패 시 화면을 유지한다', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(new Response(null, { status: 500 })))
  setup()
  expect(await screen.findByText('등록된 이메일이 없어요')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('로그아웃하지 못했어요.')
  expect(screen.getByLabelText('주소')).toHaveTextContent('/mypage')
})

it('로그아웃 성공 시 지도 필터와 선택으로 돌아간다', async () => {
  sessionStorage.setItem('toadzip:last-explore-path', '/explore?complexId=1&mapZoom=9#map')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(new Response(null, { status: 204 })))
  setup()
  fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))
  await waitFor(() => expect(screen.getByLabelText('주소')).toHaveTextContent('/explore?complexId=1&mapZoom=9#map'), { timeout: 5000 })
})

it('제목 줄에서 지도로 돌아갈 수 있고 하단에 의견 보내기와 개인정보 링크를 제공한다', async () => {
  setup()
  await screen.findByText('등록된 이메일이 없어요')
  fireEvent.click(screen.getByRole('button', { name: '지도로 돌아가기' }))
  expect(handlers.onClose).toHaveBeenCalledOnce()
  expect(screen.getByRole('link', { name: '의견 보내기' })).toHaveAttribute('href', '/feedback')
  expect(screen.getByRole('link', { name: '개인정보처리방침' })).toHaveAttribute('href', '/privacy')
  expect(screen.getByRole('link', { name: '분석 설정' })).toHaveAttribute('href', '/privacy/settings')
  expect(screen.getByRole('link', { name: '개인정보 문의·삭제 요청' })).toHaveAttribute('href', 'mailto:toadzip.official@gmail.com')
})

it('비회원에게 로그인 안내를 보여주고 로그인 실패 복귀 시 실패 안내를 연다', async () => {
  setup('/mypage?login=failed', undefined, false)
  expect(await screen.findByText('로그인하고 관심 주택을 챙겨 보세요')).toBeVisible()
  expect(await screen.findByRole('dialog', { name: '로그인' })).toBeVisible()
  expect(screen.getByRole('alert')).toHaveTextContent('로그인을 완료하지 못했어요.')
})

it('북마크 세션은 정상이어도 알림 초기화 오류는 재시도할 수 있다', async () => {
  const refreshStatus = vi.fn()
  render(<MemoryRouter initialEntries={['/mypage/notifications']}>
    <BookmarkProvider repository={repositoryOf([])} loadUser={async () => ({ id: 7, email: null })}>
      <InterestContext value={{ mode: 'error', userId: null, blocked: false, clearingAll: false, batchError: '', clearAll: vi.fn(), retrying: false, requested: new Map(), targets: [], resetSession: vi.fn(), refreshStatus, request: vi.fn(), expose: vi.fn() }}>
        <MyPageView {...handlers} />
      </InterestContext>
    </BookmarkProvider>
  </MemoryRouter>)
  const alarms = await screen.findByRole('region', { name: '알림 관리' })
  expect(await within(alarms).findByRole('alert')).toHaveTextContent('알림을 불러오지 못했어요')
  fireEvent.click(within(alarms).getByRole('button', { name: '다시 시도' }))
  expect(refreshStatus).toHaveBeenCalledOnce()
})
