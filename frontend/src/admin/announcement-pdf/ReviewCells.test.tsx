import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { ReviewCells } from './ReviewCells'
import { daegu, extractionOf, savedSheet, scenario, sheetResponse } from './fixtures/workspace'
import { createReview, type Cell } from './review'

/** 목록 모집수와 달라 워커가 미확인으로 남긴 대구 051.9800A 모집수 칸 */
function unresolvedCount() {
  const { workspace, housing } = daegu()
  const response = sheetResponse('대구테크노폴리스4', [{ name: '051.9800A', area: 51.98, count: 200 }])
  ;(response.data.supplySheet.unresolvedFields as { path: string; reason: string }[]).push({ path: 'quotas[0].count', reason: '목록 모집수 240과 다름' })
  return createReview(extractionOf(response), workspace, housing).rows[0].cells.find(item => item.field === 'count')!
}
function renderCell(initial: Cell, needs?: Map<string, string>) {
  const changed = vi.fn()
  function Test() {
    const [cells, setCells] = useState([initial])
    return <ReviewCells cells={cells} scope="공급정보" needs={needs} onChange={next => { setCells(next); changed(next[0]) }} onEvidence={() => {}} />
  }
  render(<Test />)
  return changed
}
it('기본은 값을 비교하고 직접 수정을 선택할 때만 입력칸을 연다', () => {
  // 지금 주택형 표기 '26형'을 공고문 표기 '26'으로 고치는 칸
  const cell = scenario('gongju', undefined, extracted => {
    const sheet = savedSheet(extracted, { id: 2467, name: '공주옥룡1' })
    sheet.housingTypes[0] = { ...sheet.housingTypes[0], name: '26형' }
    return sheet
  }).review.rows[0].cells.find(item => item.field === 'name')!
  const changed = renderCell(cell)
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'PDF 추출값 사용' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: '기존 값 사용' }))
  expect(changed.mock.lastCall?.[0]).toMatchObject({ selected: false, mode: 'keep' })
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  const field = screen.getByRole('textbox', { name: '공급정보 주택형 수정' })
  fireEvent.change(field, { target: { value: '26 관리자 보정' } })
  fireEvent.click(screen.getByRole('button', { name: 'PDF 추출값 사용' }))
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  expect(changed.mock.lastCall?.[0]).toMatchObject({ selected: true, value: '26', mode: 'extracted' })
  fireEvent.click(screen.getByRole('button', { name: '직접 입력' }))
  expect(screen.getByRole('textbox')).toHaveValue('26 관리자 보정')
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
it('다른 주택형과 함께 모집하는 수라 주택형만의 모집수가 없는 칸에는 입력칸이나 값 선택을 만들지 않고 까닭을 보인다', () => {
  const cell = scenario('hansville').review.rows[0].cells.find(item => item.field === 'count')!
  renderCell(cell)
  fireEvent.click(screen.getByRole('button', { name: '같음·해당 없음 1개 보기' }))
  expect(screen.getByText(/다른 주택형과 함께 모집하는 수라/)).toBeVisible()
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
})

it('현재 연결 주택타입이 비어 있으면 기존 값 선택을 저장할 때 전용면적으로 자동 연결하는 것으로 표시한다', () => {
  const row = scenario('gongju').review.rows[0]
  const type = { ...row.cells.find(item => item.field === 'housingTypeId')!, before: null, mode: 'keep' as const, selected: false }
  renderCell(type)
  // 비어 있는 연결 주택타입은 해당 없음 칸으로 접혀 있다.
  fireEvent.click(screen.getByRole('button', { name: '같음·해당 없음 1개 보기' }))
  // 보이는 이름과 접근 가능한 이름이 같아야 화면 읽기·음성 명령 사용자가 같은 버튼을 찾는다.
  expect(screen.getByRole('button', { name: '자동 연결' })).toBeVisible()
  expect(screen.getAllByText('전용면적으로 자동 연결').length).toBeGreaterThan(0)
  expect(screen.queryByRole('button', { name: '기존 값 사용' })).not.toBeInTheDocument()
  expect(screen.queryByText('추가 안 함')).not.toBeInTheDocument()
})

it('연결 주택타입이 정한 단지에 없으면 번호 대신 이름과 다른 단지 표시를 보인다', () => {
  const { review, housing } = daegu()
  const base = review.rows[0].cells.find(item => item.field === 'housingTypeId')!
  const type = { ...base, value: 501, state: 'new' as const, extracted: { ...base.extracted, value: 501, state: 'new' as const }, before: null, mode: 'extracted' as const, selected: true }
  render(<ReviewCells cells={[type]} scope="주택형 1" housingTypes={[]} otherHousingTypes={Object.values(housing).flat()} onChange={() => {}} onEvidence={() => {}} />)
  expect(type.extracted.value).toBe(501)
  expect(screen.getAllByText('51A · 전용 51.98㎡ · 공급 72.1㎡ · 468세대 · 다른 단지').length).toBeGreaterThan(0)
  expect(screen.queryByText('#501')).not.toBeInTheDocument()
})

it('근거 없는 추출값은 고를 수 없는 이유를 칸 옆에 두고, 추출값을 채운 직접 입력으로 바꾼 뒤 모드를 바꿔도 추출 원본과 직접 입력을 지킨다', () => {
  const counted = unresolvedCount()
  const cell: Cell = { ...counted, before: 240, evidence: null, state: 'unresolved', note: '목록 모집수 240과 다름', extracted: { ...counted.extracted, state: 'unresolved' }, mode: 'keep', selected: false }
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
  const cell = unresolvedCount()
  const changed = renderCell(cell)
  expect(screen.getByText('참고: 목록 모집수 240과 다름')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'PDF 추출값 사용' }))
  expect(changed.mock.lastCall?.[0]).toMatchObject({ mode: 'extracted', selected: true, value: 200 })
})

it('함께 맞춰야 하는 확인이 걸린 칸은 해당 없음이어도 접히지 않고 확인 필요와 까닭을 보인다', () => {
  const cell = scenario('gongju').review.reception[0].cells.find(item => item.field === 'name')!
  renderCell({ ...cell, state: 'notApplicable', extracted: { ...cell.extracted, state: 'notApplicable' } }, new Map([[cell.path, '접수처를 저장하려면 접수처명이 필요합니다.']]))
  expect(screen.getByText('접수처명')).toBeVisible()
  expect(screen.getByText('확인 필요')).toBeVisible()
  expect(screen.getByText('접수처를 저장하려면 접수처명이 필요합니다.')).toBeVisible()
  expect(screen.getByText('접수처명').closest('[data-review-id]')).toHaveAttribute('data-pending', 'true')
})
