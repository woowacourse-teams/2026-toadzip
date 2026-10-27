import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { SupplySplitEditor } from './SupplySplitEditor'
import { getMatchingHousingTypes, splitSupplyRow, type SupplyMatch } from './supplyMatchingApi'

vi.mock('./supplyMatchingApi', () => ({ getMatchingHousingTypes: vi.fn(), splitSupplyRow: vi.fn() }))
const row: SupplyMatch = { rowIdentifier: '5:213951:1', token: 'source', sourceComplexName: '익산한스빌',
  sourceHousingTypeName: '아파트', pnu: '5214011700113760001', exclusiveArea: null, supplyArea: null,
  complexId: 1, housingTypeId: null, totalSupplyHouseholdCount: 170, failure: null }

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getMatchingHousingTypes).mockResolvedValue([
    { id: 10, name: '26', exclusiveArea: 26.4673, supplyArea: null, token: 'type-a' },
    { id: 11, name: '26', exclusiveArea: 26.5195, supplyArea: null, token: 'type-b' },
  ])
  vi.mocked(splitSupplyRow).mockResolvedValue([])
})

function setup() {
  const onSaved = vi.fn(), onBusy = vi.fn()
  render(<MemoryRouter><SupplySplitEditor identifier="21395" row={row} complexId={1} disabled={false}
    onSaved={onSaved} onBusy={onBusy} onCancel={vi.fn()} /></MemoryRouter>)
  return { onSaved, onBusy }
}
async function fill() {
  await screen.findAllByRole('option', { name: /26.4673/ })
  fireEvent.change(screen.getByLabelText('이 공고의 주택형 표기 1'), { target: { value: '26A' } })
  fireEvent.change(screen.getByLabelText('이 공고의 주택형 표기 2'), { target: { value: '26B' } })
  fireEvent.change(screen.getByLabelText('연결 주택형 1'), { target: { value: '10' } })
  fireEvent.change(screen.getByLabelText('연결 주택형 2'), { target: { value: '11' } })
  fireEvent.change(screen.getByLabelText('보정 공식 출처 URL'), { target: { value: 'https://www.myhome.go.kr/official' } })
  fireEvent.change(screen.getByLabelText('보정 확인 근거'), { target: { value: '공고의 A/B와 면적 확인' } })
}

it('한스빌 두 주택형을 명시적으로 연결하고 공통170을 각 행에 복제하지 않는다', async () => {
  const { onSaved, onBusy } = setup()
  await fill()
  expect(screen.getByText('공통 모집수: 170명')).toBeVisible()
  expect(screen.getAllByText('개별 모집수 미확정')).toHaveLength(2)
  fireEvent.click(screen.getByRole('button', { name: '공급행 보정·저장' }))
  await waitFor(() => expect(splitSupplyRow).toHaveBeenCalledExactlyOnceWith('21395', row, [
    { name: '26A', complexId: 1, housingTypeId: 10, targetToken: 'type-a' },
    { name: '26B', complexId: 1, housingTypeId: 11, targetToken: 'type-b' },
  ], 'https://www.myhome.go.kr/official', '공고의 A/B와 면적 확인'))
  expect(onSaved).toHaveBeenCalledOnce()
  expect(onBusy.mock.calls).toEqual([[true], [false]])
})

it('같은 주택형을 두 번 선택하면 저장을 막는다', async () => {
  setup(); await fill()
  fireEvent.change(screen.getByLabelText('연결 주택형 2'), { target: { value: '10' } })
  fireEvent.click(screen.getByRole('button', { name: '공급행 보정·저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('서로 다른')
  expect(splitSupplyRow).not.toHaveBeenCalled()
})

it('원천 충돌이면 형명·선택·근거를 유지한다', async () => {
  vi.mocked(splitSupplyRow).mockRejectedValue(new Error('원천이 변경되었습니다.'))
  setup(); await fill()
  fireEvent.click(screen.getByRole('button', { name: '공급행 보정·저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('원천이 변경')
  expect(screen.getByLabelText('이 공고의 주택형 표기 1')).toHaveValue('26A')
  expect(screen.getByLabelText('연결 주택형 2')).toHaveValue('11')
  expect(screen.getByLabelText('보정 확인 근거')).toHaveValue('공고의 A/B와 면적 확인')
})
