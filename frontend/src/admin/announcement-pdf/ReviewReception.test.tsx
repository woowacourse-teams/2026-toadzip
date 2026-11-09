import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { scenario } from './fixtures/workspace'
import { ReviewReception } from './ReviewReception'
import { createReview, type Review } from './review'

it('PDF에 없는 기존 접수처는 값만 보이고 빼기를 누르면 저장에서 빼며, PDF 접수처는 칸을 고르게 한다', () => {
  const { review: base, workspace, housing } = scenario('gongju')
  workspace.detail.data.receptionPlaces = [{ name: '우편 접수', method: 'MAIL', address: '사서함 1', contact: null, url: null, guidance: null }] as never
  const review = createReview(base.extraction, workspace, housing)
  const onChange = vi.fn<(next: Review) => void>()
  render(<ReviewReception review={review} workspace={workspace} needs={new Map()} filter="all" onChange={onChange} onEvidence={vi.fn()} />)

  const kept = screen.getByText('접수처 1 · 우편').closest('li')!
  expect(within(kept).getByText('PDF에 없음 · 유지')).toBeVisible()
  expect(within(kept).getByText('우편 접수 · 우편 · 사서함 1')).toBeVisible()
  expect(within(kept).queryByText('접수처명')).toBeNull()
  fireEvent.click(within(kept).getByRole('button', { name: '접수처 1 · 우편 빼기' }))
  expect(onChange.mock.lastCall?.[0].reception.map(entry => entry.include)).toEqual([false, review.reception[1].include])

  const extracted = screen.getByText('접수처 2 · 방문').closest('li')!
  expect(within(extracted).getByText('접수처명')).toBeInTheDocument()
})
