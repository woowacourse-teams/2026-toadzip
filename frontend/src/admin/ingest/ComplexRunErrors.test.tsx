import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { ComplexRunErrors } from './ComplexRunErrors'
import { getFailureReviews, type FailureReview, type FailureReviewPageData } from './failureReviewApi'

vi.mock('./failureReviewApi', () => ({ getFailureReviews: vi.fn() }))
const getReviews = vi.mocked(getFailureReviews)
beforeEach(() => vi.resetAllMocks())
function row(extra: Partial<FailureReview> = {}): FailureReview {
  return { id: 1, category: 'complex', target: '검증 단지', sourceKey: 'fixture-source', reason: 'GEOCODING_ERROR',
    detail: '도로명주소에 맞는 좌표가 없습니다.', status: 'PENDING', occurredAt: '2026-10-22T00:00:00Z', occurrenceCount: 1,
    recurrenceCount: 0, lastResolvedAt: null, executionId: 'refine-1', source: null, raw: {}, productLinkStatus: 'NOT_FOUND', product: null, ...extra }
}
function page(items: FailureReview[], extra: Partial<FailureReviewPageData> = {}): FailureReviewPageData {
  return { items, page: 0, totalElements: items.length, totalPages: 1, hasNext: false, ...extra }
}
it('조회 실패를 오류 없음으로 표시하지 않고 재시도 후 대상·원인·다음 행동을 보여 준다', async () => {
  getReviews.mockRejectedValueOnce(new Error('조회 연결 실패')).mockResolvedValue(page([row()]))
  render(<ComplexRunErrors executionId="refine-1" phase="refinement" />)
  expect(await screen.findByRole('alert')).toHaveTextContent('오류 조회 실패: 조회 연결 실패')
  expect(screen.queryByText(/오류가 없습니다/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '다시 조회' }))
  await screen.findByText('검증 단지')
  expect(screen.getByText('도로명주소에 맞는 좌표가 없습니다.')).toBeVisible()
  expect(screen.getByText(/다음 행동: 원천의 도로명주소/)).toBeVisible()
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /수정|보정|실행/ })).not.toBeInTheDocument()
  expect(getReviews).toHaveBeenLastCalledWith('complex', 'complex', 'ALL', 0, expect.any(AbortSignal), { executionId: 'refine-1', keyword: '' })
})
it('좌표 문제는 뒷 페이지까지 확인하며 다른 원인의 실패를 섞지 않는다', async () => {
  getReviews.mockResolvedValueOnce(page([]))
    .mockResolvedValueOnce(page([row({ reason: 'INVALID_VALUE', target: '다른 오류' })], { hasNext: true, totalPages: 2, totalElements: 2 }))
    .mockResolvedValueOnce(page([row()], { page: 1, totalPages: 2, totalElements: 2 }))
  render(<ComplexRunErrors executionId="refine-1" phase="refinement" />)
  await screen.findByText('이 실행에 기록된 단지 정제 오류가 없습니다.')
  fireEvent.change(screen.getByLabelText('오류 종류'), { target: { value: 'coordinates' } })
  await screen.findByText('검증 단지')
  expect(screen.getByText('좌표 문제 1건')).toBeVisible()
  expect(screen.queryByText('다른 오류')).not.toBeInTheDocument()
  expect(getReviews).toHaveBeenLastCalledWith('complex', 'complex', 'ALL', 1, expect.any(AbortSignal), { executionId: 'refine-1', keyword: '' })
})
it('좌표 문제 조회 중 어느 페이지가 실패해도 부분 결과를 오류 없음으로 표시하지 않는다', async () => {
  getReviews.mockResolvedValueOnce(page([])).mockResolvedValueOnce(page([], { hasNext: true, totalPages: 2 }))
    .mockRejectedValueOnce(new Error('다음 페이지 실패'))
  render(<ComplexRunErrors executionId="refine-1" phase="refinement" />)
  await screen.findByText('이 실행에 기록된 단지 정제 오류가 없습니다.')
  fireEvent.change(screen.getByLabelText('오류 종류'), { target: { value: 'coordinates' } })
  expect(await screen.findByRole('alert')).toHaveTextContent('다음 페이지 실패')
  expect(screen.queryByText('이 실행에 기록된 좌표 문제가 없습니다.')).not.toBeInTheDocument()
})
it('수집 오류는 실행 필터와 수집 분류로 조회하고 외부 호출 제한의 다음 행동을 표시한다', async () => {
  getReviews.mockResolvedValue(page([row({ category: 'collection', source: 'LH_LEASE_CATALOG', detail: 'resultCode=22', status: 'RESOLVED' })]))
  render(<ComplexRunErrors executionId="collect-1" phase="collection" />)
  await screen.findByText('일일 호출 한도 초과')
  expect(screen.getByText('해결됨')).toBeVisible()
  expect(screen.getByText(/실행 완료가 개별 오류의 해결/)).toBeVisible()
  expect(getReviews).toHaveBeenCalledWith('complex', 'collection', 'ALL', 0, expect.any(AbortSignal), { executionId: 'collect-1', keyword: '' })
})
it('대상 검색은 서버 검색을 사용하고 페이지를 처음으로 돌린다', async () => {
  getReviews.mockResolvedValue(page([]))
  render(<ComplexRunErrors executionId="refine-1" phase="refinement" />)
  await screen.findByText('이 실행에 기록된 단지 정제 오류가 없습니다.')
  fireEvent.change(screen.getByLabelText('대상 검색'), { target: { value: ' 검증 단지 ' } })
  fireEvent.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() => expect(getReviews).toHaveBeenLastCalledWith('complex', 'complex', 'ALL', 0, expect.any(AbortSignal), { executionId: 'refine-1', keyword: '검증 단지' }))
})
