import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { SupplyCorrectionWorkbench } from './SupplyCorrectionWorkbench'
import { SupplyCorrectionPreview } from './SupplyCorrectionPreview'
import { blankRow, loadSupplyWorkspace, parseSupplyWorkspace, previewSupply, type CorrectionRow } from './supplyCorrectionApi'
import { getManagementDetail } from '../management/api'
import type { ManagementDetailData } from '../management/managementContract'
import { getMatchingHousingTypes, type MatchingHousingType } from '../ingest/supplyMatchingApi'

vi.mock('./SupplyReviewPanel', () => ({ SupplyReviewPanel: () => null }))
vi.mock('./supplyCorrectionApi', async original => ({ ...await original<typeof import('./supplyCorrectionApi')>(), loadSupplyWorkspace: vi.fn(), previewSupply: vi.fn(), applySupply: vi.fn() }))
vi.mock('../ingest/supplyMatchingApi', () => ({ getMatchingHousingTypes: vi.fn() }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(), getManagementDetail: vi.fn() }))

const base = { ...blankRow(0), housingComplexId: 7, housingComplexName: '한스빌', complexName: '한스빌', areaOverride: false }
const linked: CorrectionRow = { ...base, id: 1, key: 'row:1', housingTypeId: 8, typeName: '26A/B/C', linkedHousingTypes: [{ housingTypeId: 10, newHousingType: null }] }
const untouched: CorrectionRow = { ...base, id: 2, key: 'row:2', displayOrder: 1, housingTypeId: 12, typeName: '36' }
const grouped: CorrectionRow = { ...base, id: 3, key: 'row:3', displayOrder: 2, housingTypeId: 13, typeName: '46', groupId: 5, sharedHouseholdCount: 20, linkedHousingTypes: [] }
const detail: ManagementDetailData = { summary: { id: 7, name: '한스빌', subtitle: '익산', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
  sourceIdentifier: 'source', data: {}, housingTypes: [], announcements: [], supplyRows: [], schedules: [], scheduleReviewed: false }
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  vi.mocked(loadSupplyWorkspace).mockResolvedValue(structuredClone({ snapshotToken: 'before', version: 1, rows: [linked, untouched, grouped], sources: [] }))
  vi.mocked(previewSupply).mockImplementation(async (_id, input) => ({ confirmationToken: 'confirmed', before: [linked, untouched, grouped], after: input.rows, warnings: [] }))
  vi.mocked(getManagementDetail).mockResolvedValue(detail)
  vi.mocked(getMatchingHousingTypes).mockResolvedValue(complexTypes)
})
const complexTypes: MatchingHousingType[] = [
  { id: 8, name: '26A', exclusiveArea: 26.1, supplyArea: 40 }, { id: 9, name: '26B', exclusiveArea: 26.3, supplyArea: 41 },
  { id: 10, name: '26C', exclusiveArea: 26.5, supplyArea: 42 }, { id: 11, name: '26A 다른 이름', exclusiveArea: 26.1, supplyArea: 40 },
]
async function open(name: string, onDirtyChange = vi.fn()) {
  render(<MemoryRouter><SupplyCorrectionWorkbench id="21" sourceUrl="https://example.com/notice.pdf" onChanged={vi.fn()} onBusyChange={vi.fn()} onDirtyChange={onDirtyChange} /></MemoryRouter>)
  await screen.findByRole('table', { name: '공급정보 표' })
  fireEvent.click(screen.getByRole('button', { name: `한스빌 ${name} 편집` }))
  return screen.getByRole('group', { name: '함께 모집하는 주택형' })
}
async function saved() {
  fireEvent.change(screen.getByLabelText('보정 이유'), { target: { value: '공고문 세부 타입 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '공급정보 저장' }))
  await waitFor(() => expect(previewSupply).toHaveBeenCalledOnce())
  return vi.mocked(previewSupply).mock.calls[0][1].rows
}

it('지금 연결을 이름으로 보여 주고, 대표·연결과 같은 면적을 뺀 같은 단지 주택형만 추가 후보로 준다', async () => {
  const section = await open('26A/B/C')
  expect(await within(section).findByText(/26C · 전용 26.5㎡ · #10/)).toBeVisible()
  const options = within(within(section).getByLabelText('기존 주택형 추가')).getAllByRole('option').map(option => option.textContent)
  expect(options).toEqual(['주택형 선택', '26B · 전용 26.3㎡ · #9'])
  fireEvent.change(within(section).getByLabelText('기존 주택형 추가'), { target: { value: '9' } })
  const rows = await saved()
  expect(rows.find(row => row.key === 'row:1')?.linkedHousingTypes).toEqual([{ housingTypeId: 9 }, { housingTypeId: 10, newHousingType: null }])
})
it('연결을 빼면 남은 목록을 명시해 보내고, 마지막 하나를 빼면 빈 목록으로 대표만 남긴다', async () => {
  const section = await open('26A/B/C')
  fireEvent.click(await within(section).findByRole('button', { name: /26C · 전용 26.5㎡ · #10 빼기/ }))
  expect(within(section).getByText('대표 주택형만 연결되어 있습니다.')).toBeVisible()
  const rows = await saved()
  expect(rows.find(row => row.key === 'row:1')?.linkedHousingTypes).toEqual([])
})
it('새 주택형을 이름·전용·공급면적으로 만들어 연결하고, 단지에 같은 면적이 있으면 막는다', async () => {
  const section = await open('26A/B/C')
  await within(section).findByText(/26C · 전용 26.5㎡/)
  fireEvent.click(within(section).getByRole('button', { name: '새 주택형 만들어 연결' }))
  fireEvent.change(within(section).getByLabelText('함께 모집할 새 주택형 이름'), { target: { value: ' 26D ' } })
  fireEvent.change(within(section).getByLabelText('새 주택형 전용면적 (㎡)'), { target: { value: '26.3' } })
  expect(within(section).getByRole('alert')).toHaveTextContent('이 단지에 같은 전용면적 주택형이 있습니다')
  expect(within(section).getByRole('button', { name: '새 주택형 연결' })).toBeDisabled()
  fireEvent.change(within(section).getByLabelText('새 주택형 전용면적 (㎡)'), { target: { value: '26.7' } })
  fireEvent.change(within(section).getByLabelText('새 주택형 공급면적 (㎡)'), { target: { value: '43.2' } })
  fireEvent.click(within(section).getByRole('button', { name: '새 주택형 연결' }))
  expect(within(section).getByText(/26D · 전용 26.7㎡ · 새 주택형 생성 예정/)).toBeVisible()
  const rows = await saved()
  expect(rows.find(row => row.key === 'row:1')?.linkedHousingTypes).toEqual([{ housingTypeId: 10, newHousingType: null },
    { newHousingType: { name: '26D', exclusiveArea: 26.7, supplyArea: 43.2 } }])
})
it('연결이 없던 행에서 새 주택형을 만들면 단지 주택형을 불러와 같은 면적을 막고, 불러오기 전에는 연결하지 않는다', async () => {
  let resolve!: (types: MatchingHousingType[]) => void
  vi.mocked(getMatchingHousingTypes).mockReturnValue(new Promise(done => { resolve = done }))
  const section = await open('36')
  fireEvent.click(within(section).getByRole('button', { name: '새 주택형 만들어 연결' }))
  expect(getMatchingHousingTypes).toHaveBeenCalledWith(7, expect.anything())
  expect(within(section).getByRole('button', { name: '새 주택형 연결' })).toBeDisabled()
  resolve(complexTypes)
  await waitFor(() => expect(within(section).getByLabelText('새 주택형 전용면적 (㎡)')).toBeEnabled())
  fireEvent.change(within(section).getByLabelText('함께 모집할 새 주택형 이름'), { target: { value: '26B' } })
  fireEvent.change(within(section).getByLabelText('새 주택형 전용면적 (㎡)'), { target: { value: '26.3' } })
  expect(within(section).getByRole('alert')).toHaveTextContent('이 단지에 같은 전용면적 주택형이 있습니다')
  expect(within(section).getByRole('button', { name: '새 주택형 연결' })).toBeDisabled()
  fireEvent.change(within(section).getByLabelText('새 주택형 전용면적 (㎡)'), { target: { value: '26.7' } })
  expect(within(section).getByRole('button', { name: '새 주택형 연결' })).toBeEnabled()
})
it('단지 주택형을 불러오지 못하면 같은 면적을 확인할 수 없으므로 새 주택형을 연결하지 않는다', async () => {
  vi.mocked(getMatchingHousingTypes).mockRejectedValue(new Error('주택형 조회 실패'))
  const section = await open('36')
  fireEvent.click(within(section).getByRole('button', { name: '새 주택형 만들어 연결' }))
  expect(await screen.findByText('주택형 조회 실패')).toBeVisible()
  fireEvent.change(within(section).getByLabelText('함께 모집할 새 주택형 이름'), { target: { value: '26D' } })
  fireEvent.change(within(section).getByLabelText('새 주택형 전용면적 (㎡)'), { target: { value: '26.7' } })
  expect(within(section).getByRole('button', { name: '새 주택형 연결' })).toBeDisabled()
})
it('대표 주택형을 바꿨다가 되돌리면 빈 목록을 보내 화면에 보인 대로 대표만 남긴다', async () => {
  const section = await open('26A/B/C')
  await within(section).findByText(/26C · 전용 26.5㎡ · #10/)
  fireEvent.change(screen.getByLabelText('공통 주택형'), { target: { value: '9' } })
  fireEvent.change(screen.getByLabelText('공통 주택형'), { target: { value: '8' } })
  expect(within(screen.getByRole('group', { name: '함께 모집하는 주택형' })).getByText('대표 주택형만 연결되어 있습니다.')).toBeVisible()
  const rows = await saved()
  expect(rows.find(row => row.key === 'row:1')?.linkedHousingTypes).toEqual([])
})
it('조회한 연결을 뺐다가 같은 주택형을 다시 넣으면 바뀌지 않은 것으로 본다', async () => {
  vi.mocked(loadSupplyWorkspace).mockResolvedValue(parseSupplyWorkspace(structuredClone({ snapshotToken: 'before', version: 1, rows: [linked, untouched, grouped], sources: [] })))
  const onDirtyChange = vi.fn()
  const section = await open('26A/B/C', onDirtyChange)
  fireEvent.click(await within(section).findByRole('button', { name: /26C · 전용 26.5㎡ · #10 빼기/ }))
  expect(onDirtyChange).toHaveBeenLastCalledWith(true)
  fireEvent.change(within(section).getByLabelText('기존 주택형 추가'), { target: { value: '10' } })
  expect(onDirtyChange).toHaveBeenLastCalledWith(false)
})
it('손대지 않은 행은 조회한 연결 값을 그대로 보내고, 필드가 없던 행은 계속 생략한다', async () => {
  await open('36')
  fireEvent.change(screen.getByLabelText('이 공고의 주택형 표기'), { target: { value: '36형' } })
  const rows = await saved()
  expect(rows.find(row => row.key === 'row:2')).not.toHaveProperty('linkedHousingTypes')
  expect(rows.find(row => row.key === 'row:1')?.linkedHousingTypes).toEqual([{ housingTypeId: 10, newHousingType: null }])
})
it('공통 모집 묶음 행은 함께 모집하는 주택형을 고칠 수 없다고 알린다', async () => {
  const section = await open('46')
  expect(section).toHaveTextContent('공통 모집 묶음 행은')
  expect(within(section).queryAllByRole('button')).toEqual([])
  expect(within(section).queryByRole('combobox')).toBeNull()
})
it('미리보기는 바뀐 연결 목록을 보여 주고, 생략과 빈 목록은 같은 상태로 본다', () => {
  render(<SupplyCorrectionPreview value={{ confirmationToken: 't', warnings: [], before: [linked, { ...untouched, linkedHousingTypes: [] }],
    after: [{ ...linked, linkedHousingTypes: [{ housingTypeId: 9 }, { newHousingType: { name: '26D', exclusiveArea: 26.7, supplyArea: null } }] }, untouched] }} />)
  const table = screen.getByRole('region', { name: '변경 전후 비교' })
  expect(table).toHaveTextContent('함께 모집하는 주택형주택형 #10주택형 #9, 새 주택형 26D · 전용 26.7㎡ · 공급 미확인㎡')
  expect(screen.getByRole('heading')).toHaveTextContent('적용할 변경 1개')
})
