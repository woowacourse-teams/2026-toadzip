import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { QualityQueuePage } from './QualityQueuePage'

const mocks = vi.hoisted(() => ({ requestManagementApi: vi.fn() }))
vi.mock('../management/api', () => mocks)
beforeEach(() => vi.clearAllMocks())

it('검토 사유와 빠진 항목을 보여주고 비교 화면으로 바로 연결한다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ items: [{ resource: 'COMPLEX', id: 7, name: '검토 단지',
    reasons: ['MISSING', 'STALE'], missingFields: ['준공일'], updatedAt: null }], page: 0,
    totalElements: 1, totalPages: 1, hasNext: false })
  render(<MemoryRouter><QualityQueuePage /></MemoryRouter>)
  expect(await screen.findByText('확인값 변경')).toBeVisible()
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
