import { useState } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ComplexDecisions } from './ComplexDecision'
import { scenario } from './fixtures/workspace'
import { editCell } from './review'

const api = vi.hoisted(() => ({ types: vi.fn() }))
vi.mock('../ingest/supplyMatchingApi', () => ({ getMatchingHousingTypes: api.types }))
vi.mock('../supply/supplyCorrectionApi', () => ({ findSameAddressComplexes: async () => [] }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(),
  getManagementDetail: async () => ({ data: { address: { pnu: '1114010100100010000' } } }) }))
vi.mock('../management/ComplexPicker', () => ({ ComplexPicker: ({ onSelect }: { onSelect: (item: { id: number; name: string }) => void }) =>
  <><button onClick={() => onSelect({ id: 7, name: '선택 단지' })}>단지 선택</button><button onClick={() => onSelect({ id: 8, name: '다른 단지' })}>다른 단지 선택</button></> }))

beforeEach(() => vi.clearAllMocks())

it('단지 조회 응답을 기다리는 동안 고친 다른 값은 응답 완료 후에도 보존한다', async () => {
  let finish!: (items: []) => void
  api.types.mockReturnValue(new Promise<[]>(resolve => { finish = resolve }))
  // 한스빌 PDF 주택형(26A·26B)은 지금 공급표의 '아파트'와 짝이 없어 단지를 정해야 한다.
  const { review: initial, workspace } = scenario('hansville')
  function Harness() {
    const [current, setCurrent] = useState(initial)
    return <>
      <button onClick={() => setCurrent(value => ({ ...value, metadata: value.metadata.map(cell =>
        cell.field === 'postedDate' ? editCell(cell, '2026-10-01') : cell) }))}>다른 값 수정</button>
      <output aria-label="입력한 공고일">{String(current.metadata.find(cell => cell.field === 'postedDate')?.value)}</output>
      <ComplexDecisions review={current} workspace={workspace} onChange={setCurrent} />
    </>
  }
  render(<MemoryRouter><Harness /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '단지 선택' }))
  await waitFor(() => expect(api.types).toHaveBeenCalled())
  fireEvent.click(screen.getByRole('button', { name: '다른 값 수정' }))
  await act(async () => finish([]))
  expect(await screen.findByText(/연결 단지: 선택 단지/)).toBeVisible()
  expect(screen.getByLabelText('입력한 공고일')).toHaveTextContent('2026-10-01')
})


it.each(['동일 단지', '다른 단지', '후보 갱신'])('%s 선택 후에도 주택형에 직접 입력한 값을 보존한다', async mode => {
  api.types.mockResolvedValue([])
  const { review: initial, workspace } = scenario('hansville')
  const area = (review: typeof initial) => review.rows[0].cells.find(cell => cell.field === 'exclusiveArea')!
  function Harness() {
    const [current, setCurrent] = useState(initial)
    return <>
      <button onClick={() => setCurrent(value => ({ ...value, rows: value.rows.map((row, index) => index === 0
        ? { ...row, cells: row.cells.map(cell => cell.field === 'exclusiveArea' ? editCell(cell, '26.6') : cell) } : row) }))}>면적 직접 입력</button>
      <output aria-label="입력한 면적">{String(area(current).value)}</output>
      <ComplexDecisions review={current} workspace={workspace} onChange={setCurrent} />
    </>
  }
  render(<MemoryRouter><Harness /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '단지 선택' }))
  await screen.findByText(/연결 단지: 선택 단지/)
  fireEvent.click(screen.getByRole('button', { name: '면적 직접 입력' }))
  if (mode === '후보 갱신') api.types.mockResolvedValue([{ id: 71, name: '26A', exclusiveArea: 26, supplyArea: null }])
  // 정한 단지는 '다른 단지로 바꾸기'로만 다시 고른다.
  fireEvent.click(screen.getByRole('button', { name: '다른 단지로 바꾸기' }))
  fireEvent.click(screen.getByRole('button', { name: mode === '다른 단지' ? '다른 단지 선택' : '단지 선택' }))
  await waitFor(() => expect(api.types).toHaveBeenCalledTimes(2))
  await act(async () => {})
  expect(screen.getByLabelText('입력한 면적')).toHaveTextContent('26.6')
  expect(await screen.findByText(mode === '다른 단지' ? /연결 단지: 다른 단지/ : /연결 단지: 선택 단지/)).toBeVisible()
})
