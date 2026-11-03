import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { ReviewCells } from './ReviewCells'
import { daegu, scenario, separateHansville } from './fixtures/workspace'
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
  const cell = scenario('hansville', separateHansville()).review.rows[0].cells.find(item => item.field === 'sourceHousingTypeName')!
  const changed = renderCell(cell)
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'PDF 추출값 사용' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: '기존 값 사용' }))
  expect(changed.mock.lastCall?.[0]).toMatchObject({ selected: false, mode: 'keep' })
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  const field = screen.getByRole('textbox', { name: '공급정보 이 공고의 주택형 표기 수정' })
  fireEvent.change(field, { target: { value: '26A 관리자 보정' } })
  fireEvent.click(screen.getByRole('button', { name: 'PDF 추출값 사용' }))
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(changed.mock.lastCall?.[0]).toMatchObject({ selected: true, value: '26A', mode: 'extracted' })
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  expect(screen.getByRole('textbox')).toHaveValue('26A 관리자 보정')
})
it('PDF에서 미확인한 면적도 관리자가 입력하고 잘못된 값은 계속 수정할 수 있다', () => {
  const cell = scenario('gongju').review.rows[0].cells.find(item => item.field === 'supplyArea')!
  const changed = renderCell(cell)
  expect(screen.getByRole('button', { name: 'PDF 추출값 사용' })).toBeDisabled()
  expect(screen.getByText('PDF 추출값을 고를 수 없음: PDF에서 값을 읽지 못했습니다.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '값 직접 입력' }))
  const field = screen.getByRole('textbox', { name: '공급정보 공급면적 (㎡) 수정' })
  fireEvent.change(field, { target: { value: '잘못 입력' } })
  expect(field).toHaveAttribute('aria-invalid', 'true')
  expect(field).toHaveValue('잘못 입력')
  fireEvent.change(field, { target: { value: '39.84' } })
  expect(changed.mock.lastCall?.[0]).toMatchObject({ selected: true, value: 39.84, mode: 'manual', extracted: { value: null } })
})
it('묶음에서 관리하는 모집수에는 불필요한 입력칸이나 값 선택을 만들지 않는다', () => {
  const cell = scenario('hansville', separateHansville()).review.rows[0].cells.find(item => item.field === 'totalSupplyHouseholdCount')!
  renderCell(cell)
  expect(screen.getByText('공통 모집수는 묶음에 한 번만 기록합니다.')).toBeVisible()
  expect(screen.queryByRole('group')).not.toBeInTheDocument()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
})

it('현재값이 비어 있으면 기존 값 선택을 비워 두기나 미연결 유지로 표시한다', () => {
  const row = scenario('hansville', separateHansville()).review.rows[0]
  const type = { ...row.cells.find(item => item.field === 'housingTypeId')!, before: null, mode: 'keep' as const, selected: false }
  renderCell(type)
  expect(screen.getByRole('button', { name: '기존 값 사용' })).toHaveTextContent('미연결 유지')
  expect(screen.queryByText('추가 안 함')).not.toBeInTheDocument()
})

it('근거 없는 추출값은 고를 수 없는 이유를 칸 옆에 두고, 추출값을 채운 직접 입력으로 바꾼 뒤 모드를 바꿔도 추출 원본과 직접 입력을 지킨다', () => {
  const counted = daegu({ unresolvedCount: true }).review.rows[0].cells.find(item => item.field === 'totalSupplyHouseholdCount')!
  const cell: Cell = { ...counted, evidence: null, state: 'unresolved', note: '목록 모집수 240과 다름', extracted: { ...counted.extracted, state: 'unresolved' }, mode: 'keep', selected: false }
  const changed = renderCell(cell)
  expect(screen.getByRole('button', { name: 'PDF 추출값 사용' })).toBeDisabled()
  expect(screen.getByText('PDF 추출값을 고를 수 없음: 근거 인용이 없어 원문 위치를 확인할 수 없습니다.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '추출값으로 직접 입력' }))
  expect(screen.getByRole('textbox', { name: '공급정보 모집수 수정' })).toHaveValue('200')
  fireEvent.change(screen.getByRole('textbox', { name: '공급정보 모집수 수정' }), { target: { value: '201' } })
  fireEvent.click(screen.getByRole('button', { name: '기존 값 사용' }))
  expect(changed.mock.lastCall?.[0]).toMatchObject({ mode: 'keep', selected: false, before: 240, extracted: { value: 200 } })
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  expect(screen.getByRole('textbox', { name: '공급정보 모집수 수정' })).toHaveValue('201')
  expect(changed.mock.lastCall?.[0]).toMatchObject({ mode: 'manual', value: 201, selected: true })
})

it('목록 모집수와 다르다는 참고는 막지 않고 근거가 있는 PDF 추출값을 고를 수 있으며 참고를 칸 옆에 보인다', () => {
  const cell = daegu({ unresolvedCount: true }).review.rows[0].cells.find(item => item.field === 'totalSupplyHouseholdCount')!
  const changed = renderCell(cell)
  expect(screen.getByText('참고: 목록 모집수 240과 다름')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'PDF 추출값 사용' }))
  expect(changed.mock.lastCall?.[0]).toMatchObject({ mode: 'extracted', selected: true, value: 200 })
})
