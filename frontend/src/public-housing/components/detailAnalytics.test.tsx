import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { captureProductEvent } from '../../analytics/productAnalytics'
import { useDetailScrollAnalytics } from './detailAnalytics'

vi.mock('../../analytics/productAnalytics', () => ({ captureProductEvent: vi.fn(), createAnalyticsId: () => 'fallback' }))
beforeEach(() => vi.clearAllMocks())
function Detail() {
  const events = useDetailScrollAnalytics('COMPLEX', '42')
  return <dialog open className="housing-detail-layer" data-detail-visit-id="visit-42"><div aria-label="detail" {...events} /></dialog>
}

it('프로그램 스크롤은 제외하고 사용자 스크롤을 상세 방문 단위로 중복 제거하도록 전달한다', () => {
  render(<Detail />)
  const detail = screen.getByLabelText('detail')
  fireEvent.scroll(detail)
  expect(captureProductEvent).not.toHaveBeenCalled()
  fireEvent.wheel(detail)
  fireEvent.scroll(detail)
  expect(captureProductEvent).toHaveBeenCalledWith('detail_scrolled',
    { target_type: 'COMPLEX', target_id: '42', detail_visit_id: 'visit-42' }, { dedupeKey: 'detail-scrolled:visit-42' })
})

it('다른 모달 뒤의 상세 스크롤을 수집하지 않는다', () => {
  render(<><Detail /><dialog open aria-label="email" /></>)
  const detail = screen.getByLabelText('detail')
  fireEvent.wheel(detail)
  fireEvent.scroll(detail)
  expect(captureProductEvent).not.toHaveBeenCalled()
})
