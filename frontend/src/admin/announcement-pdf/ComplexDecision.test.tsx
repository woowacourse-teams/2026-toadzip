import { useState } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ComplexDecisions } from './ComplexDecision'
import { scenario } from './fixtures/workspace'
import { createReview, editCell } from './review'

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

it('단지명 열이 없는 공급표는 PDF 단지 개요가 하나뿐이면 그 주소·세대수로 새 단지 양식을 채운다', async () => {
  api.types.mockResolvedValue([])
  const { workspace: base, housing, extraction } = scenario('hansville')
  // 학익 SK VIEW 공고처럼 공급표에 단지명 열이 없어 주택형의 단지명이 비고, 본문 건설위치에서 단지 개요 하나를 읽었다.
  const workspace = { ...base, supply: { ...base.supply, announcementComplexes: [] } }
  const review = createReview({ ...extraction,
    sheet: { ...extraction.sheet, housingTypes: extraction.sheet.housingTypes.map(type => ({ ...type, complexName: '' })) },
    complexes: [{ name: '학익 SK VIEW', address: '인천광역시 미추홀구 학익동 757', totalHouseholdCount: 1581, parkingCount: null }] }, workspace, housing)
  render(<MemoryRouter><ComplexDecisions review={review} workspace={workspace} onChange={vi.fn()} /></MemoryRouter>)

  expect(await screen.findByText(/인천광역시 미추홀구 학익동 757 · 주택형/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '찾는 단지가 없으면 새 단지 등록' }))
  expect(screen.getByLabelText('주소(도로명 또는 지번)')).toHaveValue('인천광역시 미추홀구 학익동 757')
  expect(screen.getByLabelText('전체 세대수')).toHaveValue(1581)
  expect(screen.getByLabelText('단지명')).toHaveValue('학익 SK VIEW')
})

it('단지명이 빈 주택형은 PDF 단지 개요가 여럿이면 어느 단지인지 몰라 개요를 쓰지 않는다', async () => {
  api.types.mockResolvedValue([])
  const { workspace: base, housing, extraction } = scenario('hansville')
  const workspace = { ...base, supply: { ...base.supply, announcementComplexes: [] } }
  const review = createReview({ ...extraction,
    sheet: { ...extraction.sheet, housingTypes: extraction.sheet.housingTypes.map(type => ({ ...type, complexName: '' })) },
    complexes: [{ name: '가 단지', address: '서울특별시 종로구 세종대로 1', totalHouseholdCount: 100, parkingCount: null },
      { name: '나 단지', address: '서울특별시 종로구 세종대로 2', totalHouseholdCount: 200, parkingCount: null }] }, workspace, housing)
  render(<MemoryRouter><ComplexDecisions review={review} workspace={workspace} onChange={vi.fn()} /></MemoryRouter>)

  expect(await screen.findByText(/주소 미확인 · 주택형/)).toBeVisible()
})
