import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'
import { SupplyRowDraft } from './SupplyRowDraft'
import { sampleSheet } from './fixtures/sheet'
import { cellsOf, housingTypeCount, leaseOf, ownCount, type SupplySheet } from './supplySheet'
import { getManagementDetail, getManagementPage } from '../management/api'
import type { ManagementDetailData } from '../management/managementContract'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
vi.mock('../ingest/supplyMatchingApi', () => ({ getMatchingHousingTypes: vi.fn() }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(), getManagementDetail: vi.fn(), getManagementPage: vi.fn() }))
const summary = { id: 7, name: '새 단지', subtitle: '서울', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null }
const detail: ManagementDetailData = { summary, sourceIdentifier: 'source', data: { address: { pnu: '1114010100100010000' } }, housingTypes: [{ id: 9, name: '26', exclusiveArea: 26.5195, householdCount: null }],
  announcements: [], supplySheet: { housingTypes: [], targets: [], cells: [], quotas: [], leaseTerms: [] }, schedules: [], scheduleReviewed: false }
let latest: SupplySheet
function Editor({ typeKey = 'row:1', initial = sampleSheet() }: { typeKey?: string; initial?: SupplySheet }) {
  const [sheet, setSheet] = useState(initial)
  return <SupplyRowDraft sheet={sheet} typeKey={typeKey} complexes={[]} onChange={next => { latest = next; setSheet(next) }} onRemove={vi.fn()} onMove={vi.fn()} first last />
}
beforeEach(() => {
  vi.resetAllMocks(); latest = sampleSheet(); vi.mocked(getManagementDetail).mockResolvedValue(detail)
  vi.mocked(getMatchingHousingTypes).mockResolvedValue([{ id: 9, name: '26', exclusiveArea: 26.5195, supplyArea: 40, householdCount: 200 }])
  vi.mocked(getManagementPage).mockResolvedValue({ page: 0, hasNext: false, totalElements: 1, totalPages: 1, items: [summary] })
})

it('다른 단지를 선택하면 이전 주택형을 해제하고 확인된 단지 지번을 사용한다', async () => {
  render(<Editor />)
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택: 새 단지' }))
  await screen.findByRole('option', { name: '26 · 전용 26.5195㎡ · #9' })
  expect(screen.getByLabelText('단지 주택형')).toHaveValue('')
  expect(screen.getByLabelText('공급 PNU')).toHaveValue('1114010100100010000')
  expect(latest.housingTypes[0]).toMatchObject({ housingComplexId: 7, housingComplexName: '새 단지', housingTypeId: null })
  fireEvent.change(screen.getByLabelText('단지 주택형'), { target: { value: '9' } })
  expect(screen.getByRole('region', { name: '연결 후보 비교' })).toHaveTextContent('26.5195')
  expect(screen.getByRole('table', { name: '공고 입력값과 선택한 단지 주택형' })).toHaveTextContent('46A')
  fireEvent.change(screen.getByLabelText('단지 주택형'), { target: { value: 'new' } })
  expect(screen.getByLabelText('새 주택형 이름')).toHaveValue('46A')
  expect(latest.housingTypes[0]).toMatchObject({ housingTypeId: null, newHousingType: { name: '46A', exclusiveArea: 46.5, supplyArea: null } })
})

it('새 단지 조회가 실패하면 기존 연결과 작성한 값을 유지한다', async () => {
  vi.mocked(getManagementDetail).mockRejectedValue(new Error('단지 조회 실패'))
  render(<Editor />)
  fireEvent.change(screen.getByLabelText('이 공고의 주택형 표기'), { target: { value: '작성한 46B' } })
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택: 새 단지' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('단지 조회 실패')
  await waitFor(() => expect(screen.getByLabelText('이 공고의 주택형 표기')).toBeEnabled())
  expect(screen.getByLabelText('이 공고의 주택형 표기')).toHaveValue('작성한 46B')
  expect(screen.getByText(/현재 선택: 두꺼비 단지/)).toBeVisible()
})

it('공급대상 칸마다 이름·모집·예비 수와 임대조건을 고치고, 칸을 지우면 함께 쓰던 수에서도 뺀다', () => {
  render(<Editor />)
  const table = screen.getByRole('table', { name: '공급대상 표' })
  expect(within(table).getByLabelText('공급대상 1 예비 수')).toHaveValue(5)
  expect(within(table).getByLabelText('공급대상 2 보증금 (원)')).toHaveValue('1000000')
  // 임대조건을 함께 쓰는 칸은 한 번 고치면 함께 바뀐다.
  expect(screen.getAllByText(/임대조건을 함께 쓰는 칸: 46A 일반\./)[0]).toBeVisible()
  fireEvent.change(within(table).getByLabelText('공급대상 2 월 임대료 (원)'), { target: { value: '60,000' } })
  expect(leaseOf(latest, 'cell:1')?.monthlyRent).toBe('60000')
  fireEvent.change(within(table).getByLabelText('공급대상 2 모집 수'), { target: { value: '4' } })
  expect(ownCount(latest, 'cell:2', 'WINNER')).toBe(4)
  fireEvent.change(within(table).getByLabelText('공급대상 1 예비 수'), { target: { value: '' } })
  expect(ownCount(latest, 'cell:1', 'RESERVE')).toBeNull()

  fireEvent.click(within(table).getByRole('button', { name: '공급대상 2 지우기' }))
  expect(cellsOf(latest, 'row:1').map(cell => cell.key)).toEqual(['cell:1'])
  expect(latest.quotas.flatMap(quota => quota.members)).not.toContain('cell:2')
  expect(latest.quotas.find(quota => quota.key === 'quota:4')?.members).toEqual(['cell:3'])
  expect(latest.targets.map(target => target.key)).toEqual(['target:1'])
})

it('공급대상 분류는 여러 개를 고르고, 같은 대상을 쓰는 다른 주택형 칸에도 함께 적용된다고 알린다', () => {
  render(<Editor />)
  const table = screen.getByRole('table', { name: '공급대상 표' })
  // 46A 일반(공급대상 1)은 59B 일반과 같은 공급대상을 쓴다.
  const general = within(table).getByText('일반', { selector: 'summary' })
  expect(general).toHaveAccessibleName('공급대상 1 분류: 일반')
  expect(within(general).getByText('같은 공급대상을 쓰는 다른 주택형에도 함께 적용')).toBeInTheDocument()
  fireEvent.click(general)
  const options = within(table).getByRole('group', { name: '공급대상 1 분류' })
  fireEvent.click(within(options).getByRole('checkbox', { name: '주거약자용' }))
  fireEvent.click(within(options).getByRole('checkbox', { name: '청년' }))
  expect(latest.targets.find(target => target.key === 'target:1')?.groups).toEqual(['GENERAL', 'YOUTH', 'HOUSING_VULNERABLE'])
  expect(within(table).getByText('일반·청년·주거약자용', { selector: 'summary', exact: false })).toBeInTheDocument()
  fireEvent.click(within(options).getByRole('checkbox', { name: '일반' }))
  expect(latest.targets.find(target => target.key === 'target:1')?.groups).toEqual(['YOUTH', 'HOUSING_VULNERABLE'])
  // 고령자(공급대상 2)는 이 칸만 쓰는 대상이라 안내가 없다.
  expect(within(table).getByLabelText('공급대상 2 분류: 고령자')).not.toHaveTextContent('함께 적용')
})

it('주택형 모집수는 주택형의 모든 칸이 함께 쓰는 모집 수를 고친다', () => {
  render(<Editor />)
  expect(screen.getByLabelText('주택형 모집수 (세대)')).toHaveValue(10)
  fireEvent.change(screen.getByLabelText('주택형 모집수 (세대)'), { target: { value: '12' } })
  expect(housingTypeCount(latest, 'row:1')).toBe(12)
  fireEvent.click(screen.getByRole('button', { name: '공급대상 추가' }))
  expect(latest.quotas.find(quota => quota.key === 'quota:1')?.members).toHaveLength(3)
  fireEvent.change(screen.getByLabelText('주택형 모집수 (세대)'), { target: { value: '' } })
  expect(latest.quotas.some(quota => quota.key === 'quota:1')).toBe(false)
})

it('함께 쓰는 수는 다른 주택형의 칸까지 골라 만들고, 수를 고치거나 뺄 수 있다', () => {
  render(<Editor />)
  const list = screen.getByRole('list', { name: '함께 쓰는 수 목록' })
  expect(within(list).getByText('46A 고령자·59B 일반 함께 예비')).toBeVisible()
  fireEvent.change(within(list).getByLabelText('46A 고령자·59B 일반 함께 예비 수'), { target: { value: '9' } })
  expect(latest.quotas.find(quota => quota.key === 'quota:4')?.count).toBe(9)

  fireEvent.click(screen.getByRole('button', { name: '함께 쓰는 수 추가' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '46A 일반' }))
  fireEvent.click(screen.getByRole('button', { name: '함께 쓰는 수 더하기' }))
  expect(screen.getByRole('alert')).toHaveTextContent('함께 쓰는 수를 적으세요.')
  fireEvent.change(screen.getByLabelText('함께 쓸 수'), { target: { value: '70' } })
  fireEvent.click(screen.getByRole('button', { name: '함께 쓰는 수 더하기' }))
  expect(screen.getByRole('alert')).toHaveTextContent('둘 이상 골라야 합니다')
  fireEvent.click(screen.getByRole('checkbox', { name: '59B 일반' }))
  fireEvent.click(screen.getByRole('button', { name: '함께 쓰는 수 더하기' }))
  expect(latest.quotas.at(-1)).toMatchObject({ kind: 'WINNER', count: 70, members: ['cell:1', 'cell:3'] })
  expect(within(screen.getByRole('list', { name: '함께 쓰는 수 목록' })).getByText('46A 일반·59B 일반 함께 모집')).toBeVisible()

  fireEvent.click(screen.getByRole('button', { name: '46A 고령자·59B 일반 함께 예비 빼기' }))
  expect(latest.quotas.some(quota => quota.key === 'quota:4')).toBe(false)
})

it('저장된 주택형은 수집 원문 단지명을 수정 칸 없이 보여 주고 단지 주택형 수정은 단지 상세로 안내한다', () => {
  render(<Editor />)
  expect(screen.queryByLabelText('공고의 단지명')).not.toBeInTheDocument()
  expect(screen.getByText('수집 원문 단지명: 두꺼비')).toBeVisible()
  expect(screen.getByRole('link', { name: '단지 주택형 이름 고치기 →' })).toHaveAttribute('href', '/admin/complexes/11?section=relations')
})
