import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { ReviewCells } from './ReviewCells'
import { scenario } from './fixtures/workspace'
import type { Cell } from './review'

function renderCell(initial: Cell) {
  const changed = vi.fn()
  function Test() {
    const [cells, setCells] = useState([initial])
    return <ReviewCells cells={cells} scope="공급정보" onChange={next => { setCells(next); changed(next[0]) }} onEvidence={() => {}} />
  }
  render(<Test />)
  return changed
}
it('기본은 값을 비교하고 직접 수정을 선택할 때만 입력칸을 연다', () => {
  const cell = scenario('hansville').review.rows[0].cells.find(item => item.field === 'sourceHousingTypeName')!
  const changed = renderCell(cell)
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '보정된 값 사용' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: '기존 값 사용' }))
  expect(changed.mock.lastCall?.[0]).toMatchObject({ selected: false, mode: 'keep' })
  fireEvent.click(screen.getByRole('button', { name: '직접 수정' }))
  const field = screen.getByRole('textbox', { name: '공급정보 이 공고의 주택형 표기 수정' })
  fireEvent.change(field, { target: { value: '26A 관리자 보정' } })
  fireEvent.click(screen.getByRole('button', { name: '보정된 값 사용' }))
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(changed.mock.lastCall?.[0]).toMatchObject({ selected: true, value: '26A', mode: 'extracted' })
  fireEvent.click(screen.getByRole('button', { name: '직접 수정' }))
  expect(screen.getByRole('textbox')).toHaveValue('26A 관리자 보정')
})
it('PDF에서 미확인한 면적도 관리자가 입력하고 잘못된 값은 계속 수정할 수 있다', () => {
  const cell = scenario('gongju').review.rows[0].cells.find(item => item.field === 'supplyArea')!
  const changed = renderCell(cell)
  expect(screen.getByRole('button', { name: '보정된 값 사용' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '직접 수정' }))
  const field = screen.getByRole('textbox', { name: '공급정보 공급면적 (㎡) 수정' })
  fireEvent.change(field, { target: { value: '잘못 입력' } })
  expect(field).toHaveAttribute('aria-invalid', 'true')
  expect(field).toHaveValue('잘못 입력')
  fireEvent.change(field, { target: { value: '39.84' } })
  expect(changed.mock.lastCall?.[0]).toMatchObject({ selected: true, value: 39.84, mode: 'manual', extracted: { value: null } })
})
it('묶음에서 관리하는 모집수에는 불필요한 입력칸이나 값 선택을 만들지 않는다', () => {
  const cell = scenario('hansville').review.rows[0].cells.find(item => item.field === 'totalSupplyHouseholdCount')!
  renderCell(cell)
  expect(screen.getByText('공통 모집수는 묶음에 한 번만 기록합니다.')).toBeVisible()
  expect(screen.queryByRole('group')).not.toBeInTheDocument()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
})
