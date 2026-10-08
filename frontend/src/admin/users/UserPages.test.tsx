import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { UserListPage } from './UserListPage'
import { UserDetailPage } from './UserDetailPage'
import type { AdminUser, UserPage } from './api'

const mocks = vi.hoisted(() => ({ listUsers: vi.fn(), getUser: vi.fn() }))
vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), ...mocks }))
const user: AdminUser = { id: 7, email: 'member@example.test', provider: 'GOOGLE', createdAt: '2026-10-04T09:12:34' }
function page(item = user, number = 0): UserPage {
  return { items: [item], page: number, hasNext: number < 2, totalElements: 41, totalPages: 3 }
}
function deferred<T>() {
  let resolve: (value: T) => void = () => { throw new Error('Promise not initialized') }
  const promise = new Promise<T>(onResolve => { resolve = onResolve })
  return { promise, resolve }
}
function Location() {
  const location = useLocation(), navigate = useNavigate()
  return <><output aria-label="현재 경로">{location.pathname}{location.search}</output><button onClick={() => navigate(-1)}>뒤로 가기</button></>
}
function mount(entry = '/admin/users') {
  return render(<MemoryRouter initialEntries={[entry]}><Location /><Routes>
    <Route path="/admin/users" element={<UserListPage />} />
    <Route path="/admin/users/:id" element={<UserDetailPage />} />
  </Routes></MemoryRouter>)
}
beforeEach(() => { vi.clearAllMocks(); mocks.listUsers.mockResolvedValue(page()); mocks.getUser.mockResolvedValue(user) })

it('회원 목록에 핵심 네 항목과 누락 이메일을 표시하고 저장된 가입일 시각을 보존한다', async () => {
  mocks.listUsers.mockResolvedValue({ ...page(), items: [user, { ...user, id: 8, email: null, provider: 'UNKNOWN' }] })
  mount()
  const table = await screen.findByRole('table', { name: '회원 목록' })
  expect(table.querySelectorAll('thead th')).toHaveLength(4)
  expect(screen.getByRole('columnheader', { name: '로그인 이메일' })).toBeVisible()
  expect(screen.getByRole('cell', { name: 'member@example.test' })).toBeVisible()
  expect(screen.getByRole('cell', { name: 'Google' })).toBeVisible()
  expect(screen.getByRole('cell', { name: '미제공' })).toBeVisible()
  expect(screen.getByRole('cell', { name: '미확인' })).toBeVisible()
  expect(screen.getAllByRole('cell', { name: '2026-10-04 09:12:34' })).toHaveLength(2)
  expect(screen.getByText('가입일 최신순 · 총 41명')).toBeVisible()
  expect(mocks.listUsers.mock.lastCall?.[0].get('size')).toBe('20')
})

it('검색 조건을 URL에 남기고 페이지를 초기화하며 상세에서 같은 목록으로 복귀한다', async () => {
  mount('/admin/users?page=2&provider=KAKAO')
  await screen.findByRole('table')
  expect(screen.getByLabelText('로그인 방식')).toHaveValue('KAKAO')
  fireEvent.change(screen.getByLabelText('로그인 이메일·회원 ID'), { target: { value: ' member@ ' } })
  fireEvent.change(screen.getByLabelText('로그인 방식'), { target: { value: 'GOOGLE' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() => expect(mocks.listUsers.mock.lastCall?.[0].get('keyword')).toBe('member@'))
  expect(mocks.listUsers.mock.lastCall?.[0].get('page')).toBe('0')
  expect(screen.getByLabelText('현재 경로')).toHaveTextContent('/admin/users?keyword=member%40&provider=GOOGLE')
  fireEvent.click(screen.getByRole('link', { name: '회원 7 상세 보기' }))
  expect(await screen.findByRole('heading', { name: '회원 7', level: 1 })).toBeVisible()
  expect(screen.getByRole('link', { name: '목록으로' })).toHaveAttribute('href', '/admin/users?keyword=member%40&provider=GOOGLE')
  expect(screen.queryByRole('button', { name: /정지|삭제|수정/ })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('link', { name: '목록으로' }))
  await screen.findByRole('table')
  expect(screen.getByLabelText('로그인 이메일·회원 ID')).toHaveValue('member@')
  fireEvent.click(screen.getByRole('button', { name: '초기화' }))
  await waitFor(() => expect(mocks.listUsers.mock.lastCall?.[0].get('keyword')).toBeNull())
  expect(screen.getByLabelText('현재 경로')).toHaveTextContent('/admin/users')
})

it('같은 검색의 페이지 이동과 재시도에서 표 및 내부 스크롤을 유지한다', async () => {
  const next = deferred<UserPage>(), retry = deferred<UserPage>()
  mocks.listUsers.mockResolvedValueOnce(page()).mockReturnValueOnce(next.promise)
    .mockRejectedValueOnce(new Error('회원 목록 연결 실패')).mockReturnValueOnce(retry.promise)
  mount()
  const region = await screen.findByRole('region', { name: '회원 목록 스크롤' })
  const table = screen.getByRole('table')
  region.scrollTop = 120; region.scrollLeft = 90
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  expect(screen.getByRole('region')).toBe(region)
  expect(screen.getByRole('table')).toBe(table)
  expect(region.closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true')
  expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
  await act(() => next.resolve(page({ ...user, id: 9 }, 1)))
  expect(screen.getByRole('table')).toBe(table)
  expect(region.scrollTop).toBe(120); expect(region.scrollLeft).toBe(90)
  fireEvent.click(screen.getByRole('button', { name: '다음' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('회원 목록 연결 실패')
  expect(screen.queryByText('표시할 회원이 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(screen.getByRole('table')).toBe(table)
  await act(() => retry.resolve(page({ ...user, id: 10 }, 2)))
  expect(screen.getByRole('link', { name: '회원 10 상세 보기' })).toBeVisible()
})

it('조건을 바꾸면 이전 행을 숨기고 취소된 늦은 응답을 무시하며 뒤로가기 조건을 복구한다', async () => {
  const old = deferred<UserPage>(), latest = deferred<UserPage>()
  mocks.listUsers.mockResolvedValueOnce(page()).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise).mockResolvedValue(page())
  mount()
  await screen.findByRole('table')
  fireEvent.change(screen.getByLabelText('로그인 이메일·회원 ID'), { target: { value: 'old' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  const oldSignal = mocks.listUsers.mock.lastCall?.[1] as AbortSignal
  fireEvent.change(screen.getByLabelText('로그인 이메일·회원 ID'), { target: { value: 'new' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  expect(oldSignal.aborted).toBe(true)
  await act(() => latest.resolve(page({ ...user, email: 'new@example.test' })))
  await act(() => old.resolve(page({ ...user, email: 'old@example.test' })))
  expect(screen.getByText('new@example.test')).toBeVisible()
  expect(screen.queryByText('old@example.test')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '뒤로 가기' }))
  await screen.findByText('member@example.test')
  expect(screen.getByLabelText('로그인 이메일·회원 ID')).toHaveValue('old')
})

it('빈 결과와 첫 조회 실패를 구분하며 실패 시 재시도할 수 있다', async () => {
  mocks.listUsers.mockRejectedValueOnce(new Error('회원 조회 실패')).mockResolvedValueOnce({ items: [], page: 0, hasNext: false, totalElements: 0, totalPages: 0 })
  mount()
  expect(await screen.findByRole('alert')).toHaveTextContent('회원 조회 실패')
  expect(screen.queryByText('표시할 회원이 없습니다.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByText('표시할 회원이 없습니다.')).toBeVisible()
  expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
})

it('전체 페이지 수를 넘어선 주소는 마지막 페이지로 옮기고 바로가기는 검색 조건을 유지한다', async () => {
  mocks.listUsers.mockResolvedValueOnce({ ...page(user, 10), items: [], hasNext: false }).mockResolvedValueOnce(page(user, 2)).mockResolvedValue(page())
  mount('/admin/users?provider=KAKAO&page=10')
  expect(await screen.findByRole('table')).toBeVisible()
  expect(screen.getByLabelText('현재 경로')).toHaveTextContent('/admin/users?provider=KAKAO&page=2')
  fireEvent.change(screen.getByRole('spinbutton', { name: '페이지 바로가기' }), { target: { value: '1' } })
  fireEvent.click(screen.getByRole('button', { name: '이동' }))
  await waitFor(() => expect(mocks.listUsers.mock.lastCall?.[0].get('page')).toBe('0'))
  expect(mocks.listUsers.mock.lastCall?.[0].get('provider')).toBe('KAKAO')
})

it('잘못된 필터나 페이지 주소에서는 조회하지 않고 조건 초기화를 제공한다', async () => {
  mount('/admin/users?provider=FACEBOOK&page=-1')
  expect(await screen.findByRole('alert')).toHaveTextContent('검색 조건을 확인해 주세요.')
  expect(mocks.listUsers).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '초기화' }))
  expect(await screen.findByRole('table')).toBeVisible()
})

it('직접 접근한 회원 상세는 기본 항목만 보여 주며 외부 복귀 주소를 따르지 않는다', async () => {
  mount('/admin/users/7?returnTo=https%3A%2F%2Fexample.test')
  expect(await screen.findByRole('heading', { name: '회원 7' })).toBeVisible()
  expect(screen.getByRole('table', { name: '회원 기본 정보' })).toBeVisible()
  expect(screen.getByRole('link', { name: '목록으로' })).toHaveAttribute('href', '/admin/users')
  expect(mocks.getUser).toHaveBeenCalledWith('7', expect.any(AbortSignal))
  expect(screen.getByRole('cell', { name: '2026-10-04 09:12:34' })).toBeVisible()
})

it('회원 상세 조회 실패와 잘못된 ID를 명확하게 표시한다', async () => {
  mocks.getUser.mockRejectedValueOnce(new Error('회원을 찾을 수 없습니다.'))
  const view = mount('/admin/users/7')
  expect(await screen.findByRole('alert')).toHaveTextContent('회원을 찾을 수 없습니다.')
  fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByRole('table')).toBeVisible()
  view.unmount()
  mount('/admin/users/invalid')
  expect(await screen.findByRole('alert')).toHaveTextContent('올바른 회원 페이지 주소가 아닙니다.')
  expect(mocks.getUser).toHaveBeenCalledTimes(2)
})
