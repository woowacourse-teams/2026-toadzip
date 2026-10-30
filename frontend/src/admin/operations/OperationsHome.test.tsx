import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, it, vi } from 'vitest'
import { OperationsHome } from './OperationsHome'
vi.mock('../ingest/failureReviewApi', () => ({ getFailureReviews: vi.fn().mockResolvedValue({ totalElements: 3 }) }))
vi.mock('../feedback/api', () => ({ listFeedback: vi.fn().mockRejectedValue(new Error('조회 연결 실패')) }))
vi.mock('../notifications/deliveryApi', () => ({ listDeliveries: vi.fn().mockResolvedValue({ totalElements: 1 }) }))
it('한 집계 실패가 다른 업무를 가리지 않고 미확인 건수를 0으로 표시하지 않는다', async () => {
  render(<MemoryRouter><OperationsHome /></MemoryRouter>)
  expect(await screen.findByRole('alert')).toHaveTextContent('조회 연결 실패')
  expect(screen.getByLabelText('새 사용자 의견 건수')).toHaveTextContent('—')
  expect(screen.queryByLabelText('검토할 데이터 건수')).not.toBeInTheDocument()
  expect(screen.getByLabelText('단지 수집·정제 실패 건수')).toHaveTextContent('3')
  expect(within(screen.getByLabelText('운영 도구')).getByRole('link', { name: '단지 관리' })).toHaveAttribute('href', '/admin/complexes')
})
