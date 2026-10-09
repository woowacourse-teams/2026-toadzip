import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'
import { captureProductEvent } from '../../analytics/productAnalytics'
import type { ComplexSearchFilters } from '../api/publicHousingRepository'
import { ComplexFilterToolbar } from './ComplexFilterToolbar'

vi.mock('../../analytics/productAnalytics', () => ({
  captureProductEvent: vi.fn(() => true), createAnalyticsId: () => crypto.randomUUID(),
}))

beforeEach(() => vi.clearAllMocks())
const capture = vi.mocked(captureProductEvent)

function Toolbar() {
  const [filters, setFilters] = useState<ComplexSearchFilters>({})
  return <ComplexFilterToolbar filters={filters} onApply={setFilters}
    regionRepository={{ search: vi.fn().mockResolvedValue([]) }} />
}

it.each(['desktop', 'mobile'])('%s 전체 패널의 즉시 적용과 초기화를 같은 편집으로 수집하고 패널은 유지한다', async (layout) => {
  render(<Toolbar />)
  fireEvent.click(screen.getByRole('button', { name: layout === 'mobile' ? '모바일 전체 필터 열기' : '전체 필터 열기' }))
  expect(capture).toHaveBeenCalledWith('filter_opened', expect.objectContaining({ topic: 'all', layout }))
  const opened = capture.mock.calls.find(([event]) => event === 'filter_opened')?.[1]
  fireEvent.click(screen.getByRole('checkbox', { name: 'SH' }))
  expect(capture).toHaveBeenCalledWith('filter_value_changed', expect.objectContaining({
    filter_edit_id: opened?.filter_edit_id, topic: 'all', layout, field: 'agency', method: 'choice',
  }))
  expect(capture).toHaveBeenCalledWith('apply_filter', expect.objectContaining({
    filter_edit_id: opened?.filter_edit_id, apply_mode: 'immediate', changed_fields: ['agency'], methods_used: ['choice'],
  }))
  fireEvent.click(screen.getByRole('button', { name: '전체 필터 초기화' }))
  expect(capture).toHaveBeenCalledWith('filter_reset_clicked', expect.objectContaining({ reset_scope: 'all', state_target: 'applied' }))
  expect(capture).toHaveBeenCalledWith('apply_filter', expect.objectContaining({
    filter_edit_id: opened?.filter_edit_id, apply_mode: 'reset', operation: 'reset', changed_fields: ['agency'],
  }))
  expect(screen.getByRole(layout === 'mobile' ? 'dialog' : 'region', { name: '전체 필터' })).toBeVisible()
  expect(capture.mock.calls.filter(([event]) => event === 'filter_closed')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: layout === 'mobile' ? '단지 필터 닫기' : '전체 필터 패널 닫기' }))
  await waitFor(() => expect(capture).toHaveBeenCalledWith('filter_closed', expect.objectContaining({
    filter_edit_id: opened?.filter_edit_id, unapplied_changes: false, reason: 'close_button',
  })))
})

it('모바일 개별 필터의 포털 입력은 적용 전 취소와 적용을 구별한다', async () => {
  render(<Toolbar />)
  fireEvent.click(screen.getByRole('button', { name: '모바일 임대유형 필터 열기' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
  fireEvent.click(screen.getByRole('button', { name: '단지 필터 닫기' }))
  await waitFor(() => expect(capture).toHaveBeenCalledWith('filter_closed', expect.objectContaining({
    topic: 'rentalType', layout: 'mobile', had_changes: true, unapplied_changes: true, reason: 'close_button',
  })))
  expect(capture.mock.calls.filter(([event]) => event === 'apply_filter')).toHaveLength(0)

  fireEvent.click(screen.getByRole('button', { name: '모바일 임대유형 필터 열기' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '행복주택' }))
  fireEvent.click(screen.getByRole('button', { name: '임대유형 적용' }))
  expect(capture).toHaveBeenCalledWith('apply_filter', expect.objectContaining({
    topic: 'rentalType', layout: 'mobile', apply_mode: 'submit', methods_used: ['choice'],
  }))
  expect(capture).toHaveBeenLastCalledWith('filter_closed', expect.objectContaining({
    topic: 'rentalType', unapplied_changes: false, reason: 'apply',
  }))
})

it('전체 패널에서 유효하지 않아 적용하지 않은 준공년도는 닫을 때 미적용 변경으로 남는다', async () => {
  render(<Toolbar />)
  fireEvent.click(screen.getByRole('button', { name: '전체 필터 열기' }))
  fireEvent.change(screen.getByLabelText('최소 준공년도'), { target: { value: '2025' } })
  fireEvent.change(screen.getByLabelText('최대 준공년도'), { target: { value: '2020' } })
  expect(screen.getByRole('alert')).toHaveTextContent('최소 준공년도는 최대 준공년도보다 클 수 없습니다.')
  fireEvent.click(screen.getByRole('button', { name: '전체 필터 패널 닫기' }))
  await waitFor(() => expect(capture).toHaveBeenLastCalledWith('filter_closed', expect.objectContaining({
    topic: 'all', had_changes: true, unapplied_changes: true, reason: 'close_button',
  })))
})
