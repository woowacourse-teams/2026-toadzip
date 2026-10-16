import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ReviewTaskBoard } from './ReviewTaskBoard'
const mocks = vi.hoisted(() => ({ reviewTaskPage: vi.fn() }))
vi.mock('./reviewTaskApi', async importOriginal => ({ ...await importOriginal<typeof import('./reviewTaskApi')>(), ...mocks }))
const page = { items: [{ resource: 'COMPLEX', id: 7, name: '담당 단지', assignee: 'first', status: 'OPEN',
  note: '원천 재확인', dueDate: '2026-10-15', overdue: true, sourceChanged: true }], page: 0, totalElements: 1, totalPages: 1, hasNext: false }
beforeEach(() => { vi.clearAllMocks(); mocks.reviewTaskPage.mockResolvedValue(page) })
it('내 알림 수를 표시하고 기한·원천 변경 업무의 비교 화면으로 연결한다', async () => {
  render(<MemoryRouter><ReviewTaskBoard /></MemoryRouter>)
  fireEvent.click(await screen.findByText(/내 담당 알림 1건/))
  const link = await screen.findByRole('link', { name: '담당 단지 업무·검토' })
  expect(link).toHaveAttribute('href', '/admin/complexes/7?section=verification')
  expect(screen.getByText('기한 초과')).toBeVisible()
  expect(screen.getByText('원천 변경·소실')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '내 담당 알림 보기' }))
  await waitFor(() => expect(mocks.reviewTaskPage).toHaveBeenLastCalledWith(expect.any(URLSearchParams), true, expect.any(AbortSignal)))
})
it('목록 조회 실패를 업무 없음으로 표시하지 않는다', async () => {
  mocks.reviewTaskPage.mockImplementation((_params, alerts) => alerts ? Promise.resolve(page) : Promise.reject(new Error('업무 조회 실패')))
  render(<MemoryRouter><ReviewTaskBoard /></MemoryRouter>)
  fireEvent.click(await screen.findByText(/내 담당 알림 1건/))
  expect(await screen.findByRole('alert')).toHaveTextContent('업무 조회 실패')
  expect(screen.queryByText('현재 조건의 담당 업무가 없습니다.')).not.toBeInTheDocument()
})
