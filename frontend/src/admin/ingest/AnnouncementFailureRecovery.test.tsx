import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { AnnouncementFailureRecovery } from './AnnouncementFailureRecovery'
import { getFailureReviews, type FailureReview, type FailureReviewPageData } from './failureReviewApi'

vi.mock('./failureReviewApi', () => ({ getFailureReviews: vi.fn() }))
vi.mock('./AnnouncementSupplyMatchingForm', () => ({
  AnnouncementSupplyMatchingForm: ({ onApplied }: { onApplied: () => void }) => <button type="button" onClick={onApplied}>정제 실행</button>,
}))

const failure = (id: number, identifier: string) => ({ id, raw: { sourceAnnouncementIdentifier: identifier } }) as unknown as FailureReview
const page = (items: FailureReview[], hasNext = false, number = 0): FailureReviewPageData =>
  ({ items, page: number, totalElements: items.length, totalPages: 1, hasNext })
beforeEach(() => vi.mocked(getFailureReviews).mockReset())

it('정제 전후 같은 공고의 미해결 오류 수를 비교해 모두 해결됐다고 알린다', async () => {
  // 검색은 부분 일치라 213912 같은 다른 공고 오류가 섞여도 세지 않는다.
  vi.mocked(getFailureReviews)
    .mockResolvedValueOnce(page([failure(1, '21391'), failure(2, '21391'), failure(3, '213912')], true))
    .mockResolvedValueOnce(page([failure(4, '21391')], false, 1))
    .mockResolvedValueOnce(page([failure(3, '213912')]))
  const onApplied = vi.fn()
  render(<AnnouncementFailureRecovery identifier="21391" disabled={false} onApplied={onApplied} onBusy={vi.fn()} />)

  expect(await screen.findByText(/이 공고에 미해결 오류 3건이 있습니다/)).toBeVisible()
  expect(getFailureReviews).toHaveBeenCalledWith('announcement', 'all', 'PENDING', 1, expect.anything(), { keyword: '21391' })
  fireEvent.click(screen.getByRole('button', { name: '정제 실행' }))
  expect(await screen.findByRole('status')).toHaveTextContent('이 공고의 미해결 오류가 모두 해결됐습니다 (3건 → 0건).')
  expect(onApplied).toHaveBeenCalledOnce()
})

it('남은 오류가 있으면 건수와 다음 행동을 알린다', async () => {
  vi.mocked(getFailureReviews)
    .mockResolvedValueOnce(page([failure(1, '21391'), failure(2, '21391')]))
    .mockResolvedValueOnce(page([failure(2, '21391')]))
  render(<AnnouncementFailureRecovery identifier="21391" disabled={false} onApplied={vi.fn()} onBusy={vi.fn()} />)

  await screen.findByText(/미해결 오류 2건이 있습니다/)
  fireEvent.click(screen.getByRole('button', { name: '정제 실행' }))
  expect(await screen.findByRole('status')).toHaveTextContent('이 공고에 미해결 오류 1건이 남았습니다 (2건 → 1건). 목록에서 남은 오류를 열어 이어서 처리하세요.')
})
