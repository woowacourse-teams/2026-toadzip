import { fireEvent, render, screen, within } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { ReviewSchedules } from './ReviewSchedules'
import { scenario } from './fixtures/workspace'

it('일정마다 PDF 기간·근거·기존 일정·결정을 한 묶음으로 보이고, 날짜 없는 안내는 저장하지 않는다고 적는다', () => {
  const { review, workspace } = scenario('gongju')
  const evidence = vi.fn()
  render(<ReviewSchedules review={review} workspace={workspace} filter="all" empty="" onChange={() => {}} onEvidence={evidence} />)
  const rows = within(screen.getByRole('list', { name: '일정 대조표' })).getAllByRole('listitem')
  expect(rows[0]).toHaveTextContent('접수 · 현장 신청접수')
  expect(rows[0]).toHaveTextContent('2026-10-27 10:00 ~ 2026-10-29 17:00')
  expect(rows[0]).toHaveTextContent('2026-10-27 ~ 2026-10-29 · #14 공고 전체 · 접수')
  expect(rows[0]).toHaveTextContent('저장2026-10-27 10:00 ~ 2026-10-29 17:00 · 현장 신청접수')
  expect(rows[0]).toHaveTextContent('기존 1건 갱신')
  const contract = rows.find(row => row.textContent?.includes('계약안내(개별안내)'))!
  expect(contract).toHaveTextContent('날짜 없음')
  expect(contract).toHaveTextContent('저장 안 함')
  expect(contract).toHaveTextContent('날짜가 없는 안내라 일정으로 저장하지 않습니다.')
  expect(within(contract).queryByRole('button', { name: /적용/ })).not.toBeInTheDocument()
  fireEvent.click(within(rows[0]).getByRole('button', { name: /^PDF p\.\d+ 근거 보기/ }))
  expect(evidence).toHaveBeenCalledWith(expect.objectContaining({ path: 'schedules[0]' }))
})

it('반영할 값이 없으면 같음·변경 없음으로, 적용할 일정에 빈 값이 있으면 확인 필요로, 저장할 수 없는 일정은 적용을 막는다', () => {
  const { review, workspace } = scenario('gongju')
  const render0 = (cells = review.schedules[0].cells, include = true) => {
    const next = { ...review, schedules: review.schedules.map((item, at) => at === 0 ? { ...item, include, cells } : item) }
    return render(<ReviewSchedules review={next} workspace={workspace} filter="all" empty="" onChange={() => {}} onEvidence={() => {}} />)
  }
  const first = () => within(screen.getByRole('list', { name: '일정 대조표' })).getAllByRole('listitem')[0]
  // PDF와 다른 값을 고르지 않고 기존 값을 두면 '같음'이 아니라 다르다고 알린다.
  const kept = render0(review.schedules[0].cells.map(cell => ({ ...cell, selected: false })))
  expect(first()).toHaveTextContent('기존 유지 · PDF와 다름')
  kept.unmount()
  const same = render0(review.schedules[0].cells.map(cell => ({ ...cell, selected: false, state: 'unresolved' as const, extracted: { ...cell.extracted, state: 'same' as const } })))
  expect(first()).toHaveTextContent('같음 · 변경 없음')
  // 직접 입력을 비워 칸 상태가 미확인이어도, PDF 추출값과 기존이 같으면 '같음'이다.
  same.unmount()
  const blank = review.schedules[0].cells.map(cell => cell.field === 'endAt' ? { ...cell, selected: false, value: null, before: null } : cell)
  const included = render0(blank)
  expect(within(first()).getByText('확인 필요')).toHaveAttribute('data-tone', 'pending')
  expect(first()).toHaveTextContent('유형·이름·시작·종료 중 빈 값이 있습니다.')
  included.unmount()
  render0(blank, false)
  expect(within(first()).getByRole('button', { name: '일정 1 적용' })).toBeDisabled()
})
