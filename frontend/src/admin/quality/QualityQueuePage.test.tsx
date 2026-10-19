import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useNavigate } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { QualityQueuePage } from './QualityQueuePage'

const mocks = vi.hoisted(() => ({ requestManagementApi: vi.fn() }))
vi.mock('../management/api', () => mocks)
vi.mock('./ReviewTaskBoard', () => ({ ReviewTaskBoard: () => null }))
beforeEach(() => vi.clearAllMocks())

function BackButton() {
  const navigate = useNavigate()
  return <button onClick={() => navigate(-1)}>뒤로가기</button>
}

it('뒤로가기로 조회 조건이 바뀌면 검색어 입력과 결과를 함께 복원한다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ items: [], page: 0, totalElements: 0, totalPages: 0, hasNext: false })
  render(<MemoryRouter initialEntries={['/admin/quality?keyword=처음 단지']}><BackButton /><QualityQueuePage /></MemoryRouter>)
  expect(screen.getByLabelText('이름 검색')).toHaveValue('처음 단지')
  fireEvent.change(screen.getByLabelText('이름 검색'), { target: { value: '다음 단지' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  expect(mocks.requestManagementApi).toHaveBeenLastCalledWith(expect.stringContaining('keyword=%EB%8B%A4%EC%9D%8C+%EB%8B%A8%EC%A7%80'), 'GET', undefined, expect.any(AbortSignal))
  fireEvent.click(screen.getByRole('button', { name: '뒤로가기' }))
  expect(screen.getByLabelText('이름 검색')).toHaveValue('처음 단지')
  expect(mocks.requestManagementApi).toHaveBeenLastCalledWith(expect.stringContaining('keyword=%EC%B2%98%EC%9D%8C+%EB%8B%A8%EC%A7%80'), 'GET', undefined, expect.any(AbortSignal))
})

it('담당 업무를 별도 작업 화면에서 열고 검토 목록과 입력 도구를 겹쳐 놓지 않는다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ items: [], page: 0, totalElements: 0, totalPages: 0, hasNext: false })
  render(<MemoryRouter><QualityQueuePage /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: '내 담당 업무' }))
  expect(screen.queryByRole('form', { name: '검토 대상 검색' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '검토 대상' }))
  expect(screen.getByRole('form', { name: '검토 대상 검색' })).toBeVisible()
})

it('검토 사유와 빠진 항목을 보여주고 비교 화면으로 바로 연결한다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ items: [{ resource: 'COMPLEX', id: 7, name: '검토 단지',
    reasons: ['MISSING', 'STALE'], missingFields: ['준공일'], updatedAt: null }], page: 0,
    totalElements: 1, totalPages: 1, hasNext: false })
  render(<MemoryRouter><QualityQueuePage /></MemoryRouter>)
  const reviewLink = await screen.findByRole('link', { name: '검토 단지 비교·검토' })
  expect(within(reviewLink.closest('li')!).getByText('확인값 변경')).toBeVisible()
  expect(screen.getByText('준공일')).toBeVisible()
  expect(screen.getByRole('link', { name: '검토 단지 비교·검토' })).toHaveAttribute('href', '/admin/complexes/7?section=verification')
  fireEvent.change(screen.getByLabelText('검토 사유'), { target: { value: 'MISSING' } })
  expect(mocks.requestManagementApi).toHaveBeenLastCalledWith(expect.stringContaining('reason=MISSING'), 'GET', undefined, expect.any(AbortSignal))
})

it('조회 실패를 빈 결과로 표시하지 않고 재시도를 제공한다', async () => {
  mocks.requestManagementApi.mockRejectedValue(new Error('DB 조회 실패'))
  render(<MemoryRouter><QualityQueuePage /></MemoryRouter>)
  expect(await screen.findByRole('alert')).toHaveTextContent('DB 조회 실패')
  expect(screen.queryByText('현재 조건에 검토할 대상이 없습니다.')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다시 조회' })).toBeEnabled()
})
