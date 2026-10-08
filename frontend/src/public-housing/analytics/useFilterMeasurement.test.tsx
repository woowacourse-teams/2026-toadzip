import { useRef, useState } from 'react'
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { captureProductEvent } from '../../analytics/productAnalytics'
import type { ComplexSearchFilters } from '../api/publicHousingRepository'
import { useFilterMeasurement } from './useFilterMeasurement'
vi.mock('../../analytics/productAnalytics', () => ({ captureProductEvent: vi.fn(() => true), createAnalyticsId: () => crypto.randomUUID() }))
const capture = vi.mocked(captureProductEvent)
function Probe({ immediate = false }: { immediate?: boolean }) {
  const root = useRef<HTMLElement>(null)
  const [open, setOpen] = useState(true)
  const [filters, setFilters] = useState<ComplexSearchFilters>({})
  const [selected, setSelected] = useState(false)
  const measurement = useFilterMeasurement('complex', open ? 'rentalType' : null, 'desktop', root, filters)
  return <section ref={root}>{open && <form>
    <input type="checkbox" name="rentalTypes" value="PERMANENT_RENTAL" aria-label="선택" checked={selected} onChange={event => { const checked = event.target.checked; setSelected(checked); if (immediate) { const next: ComplexSearchFilters = checked ? { rentalTypes: ['PERMANENT_RENTAL'] } : {}; measurement.apply(next, 'immediate'); setFilters(next) } }} />
    <button type="button" onClick={() => { const next: ComplexSearchFilters = selected ? { rentalTypes: ['PERMANENT_RENTAL'] } : {}; measurement.apply(next, 'immediate'); setFilters(next) }}>적용</button>
    <button type="button" onClick={() => { measurement.reason('close_button'); setOpen(false) }}>닫기</button>
  </form>}</section>
}
beforeEach(() => capture.mockClear())
afterEach(cleanup)
function closed() { return capture.mock.calls.find(([name]) => name === 'filter_closed')?.[1] }
it('변경 뒤 원래 값으로 되돌린 초안은 미적용 변경으로 표시하지 않는다', async () => {
  const view = render(<Probe />)
  fireEvent.click(view.getByRole('checkbox'))
  fireEvent.click(view.getByRole('checkbox'))
  fireEvent.click(view.getByText('닫기'))
  await waitFor(() => expect(closed()).toMatchObject({ had_changes: true, unapplied_changes: false, reason: 'close_button' }))
})
it('적용한 변경과 아직 적용하지 않은 변경을 구분한다', async () => {
  const view = render(<Probe />)
  fireEvent.click(view.getByRole('checkbox'))
  fireEvent.click(view.getByText('적용'))
  fireEvent.click(view.getByRole('checkbox'))
  fireEvent.click(view.getByText('닫기'))
  await waitFor(() => expect(closed()).toMatchObject({ had_changes: true, unapplied_changes: true }))
})

it('즉시 적용 체크박스는 React 적용 전에 입력 방법을 기록한다', () => {
  const view = render(<Probe immediate />)
  fireEvent.click(view.getByRole('checkbox'))
  expect(capture).toHaveBeenCalledWith('apply_filter', expect.objectContaining({ methods_used: ['choice'], apply_mode: 'immediate' }))
})
