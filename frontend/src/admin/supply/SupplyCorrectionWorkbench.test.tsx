import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { rememberApply } from './supplyApplyRecovery'
import { ManagementError } from '../management/api'
import { SupplyCorrectionWorkbench } from './SupplyCorrectionWorkbench'
import { applySupply, loadSupplyWorkspace, previewSupply, type SupplyPreview, type SupplyWorkspace } from './supplyCorrectionApi'
import { sampleSheet } from './fixtures/sheet'
import { addHousingType, removeHousingType, updateHousingType } from './sheetEdits'
import { toSheetInput, type SupplySheet } from './supplySheet'

vi.mock('./SupplyReviewPanel', () => ({ SupplyReviewPanel: ({ evidence, pending }: { evidence: { note: string } | null; pending: string[] }) =>
  <div data-testid="review-panel">{evidence?.note}<output aria-label="검토할 바뀐 항목">{pending.join(',')}</output></div> }))
// PDF 보정 화면은 공급표를 돌려주는 쪽만 흉내 낸다.
const pdf = vi.hoisted(() => ({ sheet: null as SupplySheet | null }))
vi.mock('../announcement-pdf/PdfCorrectionPage', () => ({ PdfCorrectionWorkspace: ({ supplyDraft }: { supplyDraft: (sheet: SupplySheet) => void }) =>
  <button type="button" onClick={() => supplyDraft(pdf.sheet!)}>모의 PDF 공급표 넘기기</button> }))
vi.mock('./supplyCorrectionApi', async original => ({ ...await original<typeof import('./supplyCorrectionApi')>(), loadSupplyWorkspace: vi.fn(), previewSupply: vi.fn(), applySupply: vi.fn() }))

const workspace: SupplyWorkspace = { snapshotToken: 'before', version: 1, sheet: sampleSheet(), announcementComplexes: [], applicationTypeNames: [],
  sourceReference: [{ complexLabel: '두꺼비', typeName: '46', exclusiveArea: 46.5, householdCount: 10 }], warnings: [] }
const renamed = updateHousingType(sampleSheet(), 'row:1', { name: '46A(신혼)' })
const preview: SupplyPreview = { confirmationToken: 'confirmed', before: sampleSheet(), after: renamed, warnings: ['연결 상태 확인 필요'] }
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  vi.mocked(loadSupplyWorkspace).mockResolvedValue(structuredClone(workspace))
  vi.mocked(previewSupply).mockResolvedValue(structuredClone(preview))
  vi.mocked(applySupply).mockResolvedValue({ changeId: 3, alreadyApplied: false, workspace: { ...workspace, snapshotToken: 'after', version: 2, sheet: renamed } })
})
afterEach(() => vi.restoreAllMocks())
async function setup(edit = true) {
  const onChanged = vi.fn()
  render(<MemoryRouter><SupplyCorrectionWorkbench id="21" sourceUrl="https://example.com/notice.pdf" onChanged={onChanged} onBusyChange={vi.fn()} onDirtyChange={vi.fn()} /></MemoryRouter>)
  await screen.findByRole('table', { name: '공급정보 표' })
  if (edit) fireEvent.click(screen.getByRole('button', { name: '두꺼비 단지 46A 편집' }))
  return onChanged
}
function save(reason = '공식 공고의 신혼 표기 확인') {
  fireEvent.change(screen.getByLabelText('보정 이유'), { target: { value: reason } })
  fireEvent.click(screen.getByRole('button', { name: '공급정보 저장' }))
}
function editAndSave() {
  fireEvent.change(screen.getByLabelText('이 공고의 주택형 표기'), { target: { value: '46A(신혼)' } })
  save()
}

it('주택형별 표를 먼저 보여 주고 필요한 주택형만 펼치며, LH·GH 원천은 접힌 참고로만 둔다', async () => {
  await setup(false)
  const table = screen.getByRole('table', { name: '공급정보 표' })
  expect(within(table).getAllByRole('row').slice(1).map(row => row.textContent)).toEqual([
    expect.stringContaining('두꺼비 단지46A10'), expect.stringContaining('두꺼비 단지59B7')])
  expect(screen.queryByLabelText('이 공고의 주택형 표기')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'PDF로 채우기' })).toBeEnabled()
  const reference = screen.getByText('LH·GH 원천 참고 1개').closest('details')!
  expect(reference).not.toHaveAttribute('open')
  expect(within(reference).getByRole('table', { name: 'LH·GH 원천 참고 표' })).toHaveTextContent('두꺼비4646.510')
  expect(screen.getByText('아직 변경한 공급정보가 없습니다.')).toBeVisible()
})

it('고친 공급표를 서버가 확인한 뒤 같은 입력과 확인 토큰으로 한 번 저장하고, 바뀐 주택형을 검토 대상으로 넘긴다', async () => {
  const changed = await setup()
  editAndSave()
  await screen.findByText('공급정보를 저장했습니다.')
  expect(changed).toHaveBeenCalledOnce()
  const input = vi.mocked(previewSupply).mock.calls[0][1]
  expect(input).toMatchObject({ snapshotToken: 'before', reason: '공식 공고의 신혼 표기 확인', sourceUrl: 'https://example.com/notice.pdf', sourcePage: null })
  // 손대지 않은 공급대상·수량·임대조건도 함께 보낸다. 빠진 것은 서버가 지우기 때문이다.
  expect(input.sheet).toEqual(toSheetInput(renamed))
  expect(applySupply).toHaveBeenCalledExactlyOnceWith('21', input, 'confirmed')
  expect(screen.getByLabelText('검토할 바뀐 항목')).toHaveTextContent('row:1')
  expect(screen.getByText('저장 후 확인할 항목 1개')).toBeInTheDocument()
})

it('저장된 주택형을 지우면 확인을 받고, 그 칸이 든 수량·임대조건을 정리한 공급표를 보낸다', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
  await setup(false)
  fireEvent.click(screen.getByRole('button', { name: '두꺼비 단지 59B 지우기' }))
  expect(screen.getByRole('table', { name: '공급정보 표' })).toHaveTextContent('59B')
  fireEvent.click(screen.getByRole('button', { name: '두꺼비 단지 59B 지우기' }))
  expect(confirm.mock.calls[1][0]).toContain('저장하면 되돌릴 수 없고')
  expect(screen.getByRole('table', { name: '공급정보 표' })).not.toHaveTextContent('59B')
  save('중복 주택형 정리')
  await waitFor(() => expect(previewSupply).toHaveBeenCalled())
  const sheet = vi.mocked(previewSupply).mock.calls[0][1].sheet
  expect(sheet.housingTypes.map(type => type.key)).toEqual(['row:1'])
  expect(sheet.cells.map(cell => cell.key)).toEqual(['cell:1', 'cell:2'])
  expect(sheet.quotas).toEqual([{ kind: 'WINNER', count: 10, members: ['cell:1', 'cell:2'] }, { kind: 'RESERVE', count: 5, members: ['cell:1'] },
    { kind: 'RESERVE', count: 8, members: ['cell:2'] }])
  expect(sheet.leaseTerms.map(lease => lease.members)).toEqual([['cell:1', 'cell:2']])
})

it('공급정보가 없는 공고에 첫 주택형과 공급대상을 직접 만들어 새 키로 보낸다', async () => {
  vi.mocked(loadSupplyWorkspace).mockResolvedValue({ ...workspace, sheet: { housingTypes: [], targets: [], cells: [], quotas: [], leaseTerms: [] } })
  await setup(false)
  expect(screen.getByText('등록된 공급정보가 없습니다.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '직접 추가' }))
  fireEvent.change(screen.getByLabelText('공고의 단지명'), { target: { value: '첫 단지' } })
  fireEvent.change(screen.getByLabelText('이 공고의 주택형 표기'), { target: { value: '46A' } })
  fireEvent.click(screen.getByRole('button', { name: '공급대상 추가' }))
  // 이름 없는 공급대상은 저장하지 않는다.
  expect(screen.getByRole('button', { name: '공급정보 저장' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('공급대상 1 이름'), { target: { value: '일반' } })
  fireEvent.change(screen.getByLabelText('공급대상 1 모집 수'), { target: { value: '12' } })
  fireEvent.change(screen.getByLabelText('공급대상 1 보증금 (원)'), { target: { value: '9007199254740993' } })
  save('공식 공고의 첫 주택형')
  await waitFor(() => expect(applySupply).toHaveBeenCalledOnce())
  const sheet = vi.mocked(applySupply).mock.calls[0][1].sheet
  expect(sheet.housingTypes).toEqual([expect.objectContaining({ key: expect.stringMatching(/^new:/), id: null, complexName: '첫 단지', name: '46A' })])
  expect(sheet.targets).toEqual([expect.objectContaining({ key: expect.stringMatching(/^new:/), label: '일반' })])
  expect(sheet.quotas).toEqual([{ kind: 'WINNER', count: 12, members: [sheet.cells[0].key] }])
  expect(sheet.leaseTerms).toEqual([{ rentalDeposit: '9007199254740993', monthlyRent: null, convertedDeposit: null, members: [sheet.cells[0].key] }])
})

it('PDF로 채우면 받은 공급표로 편집표를 바꾸고, 저장하면 지워질 기존 주택형 수를 알린다', async () => {
  pdf.sheet = addHousingType(removeHousingType(sampleSheet(), 'row:2'), { name: '84C', complexName: '두꺼비' }).sheet
  await setup(false)
  fireEvent.click(screen.getByRole('button', { name: 'PDF로 채우기' }))
  fireEvent.click(screen.getByRole('button', { name: '모의 PDF 공급표 넘기기' }))
  const table = await screen.findByRole('table', { name: '공급정보 표' })
  expect(within(table).getAllByRole('row').slice(1).map(row => (row as HTMLTableRowElement).cells[1].textContent)).toEqual(['46A', '84C'])
  expect(screen.getByText(/PDF에 없는 저장된 주택형 1개는 저장하면 지워집니다\./)).toBeVisible()
  save('PDF 공급표 반영')
  await waitFor(() => expect(previewSupply).toHaveBeenCalled())
  expect(vi.mocked(previewSupply).mock.calls[0][1].sheet).toEqual(toSheetInput(pdf.sheet))
})

it('검증 오류에서는 저장하지 않고 입력을 보존한다', async () => {
  vi.mocked(previewSupply).mockRejectedValue(new Error('잘못된 주택형 소속'))
  await setup(); editAndSave()
  expect(await screen.findByRole('alert')).toHaveTextContent('잘못된 주택형 소속')
  expect(applySupply).not.toHaveBeenCalled()
  expect(screen.getByLabelText('이 공고의 주택형 표기')).toHaveValue('46A(신혼)')
})

it('동시 수정은 최신값을 가져와 겹친 항목만 고르게 하고 나머지는 최신값으로 맞춘다', async () => {
  vi.mocked(applySupply).mockRejectedValue(new ManagementError('원천이 변경됨', 409, {}, 'ADMIN_DATA_STALE'))
  await setup(); editAndSave()
  expect(await screen.findByRole('alert')).toHaveTextContent('저장하는 사이 등록값이나 원천이 바뀌어 저장하지 않았습니다.')
  expect(screen.getAllByRole('alert')).toHaveLength(1)
  expect(screen.queryByText('원천이 변경됨')).not.toBeInTheDocument()
  const latest = updateHousingType(sampleSheet(), 'row:1', { name: '다른 관리자 형명', supplyPnu: '1111' })
  vi.mocked(loadSupplyWorkspace).mockResolvedValue({ ...workspace, snapshotToken: 'latest', sheet: latest })
  fireEvent.click(screen.getByRole('button', { name: '입력을 유지하고 최신값 비교' }))
  await screen.findByText('겹치는 변경을 선택하세요')
  expect(screen.getByText('현재 저장값: 다른 관리자 형명 / 내 입력: 46A(신혼)')).toBeVisible()
  expect(screen.getByRole('button', { name: '공급정보 저장' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '내 입력 사용' }))
  expect(screen.getByLabelText('이 공고의 주택형 표기')).toHaveValue('46A(신혼)')
  expect(screen.getByLabelText('공급 PNU')).toHaveValue('1111')
  save()
  await waitFor(() => expect(previewSupply).toHaveBeenCalledTimes(2))
  expect(vi.mocked(previewSupply).mock.calls[1][1].snapshotToken).toBe('latest')
})

it('다시 조회해도 풀리지 않는 409는 서버가 알려 준 이유를 그대로 보여 주고 최신값 비교를 권하지 않는다', async () => {
  vi.mocked(previewSupply).mockRejectedValue(new ManagementError('연결 변경할 단지에 접수 일정이 있습니다. 일정 적용 단지를 먼저 수정하세요.', 409, {}, 'ADMIN_DATA_CONFLICT'))
  await setup(); editAndSave()
  expect(await screen.findByRole('alert')).toHaveTextContent('연결 변경할 단지에 접수 일정이 있습니다.')
  expect(screen.queryByRole('button', { name: '입력을 유지하고 최신값 비교' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '공급정보 저장' })).toBeEnabled()
  expect(applySupply).not.toHaveBeenCalled()
})

it('응답 유실은 입력을 잠그고 동일한 작업 ID로 재확인한다', async () => {
  vi.mocked(applySupply).mockRejectedValueOnce(new TypeError('network'))
  await setup(); editAndSave()
  fireEvent.click(await screen.findByRole('button', { name: '같은 작업으로 적용 결과 확인' }))
  await screen.findByText('공급정보를 저장했습니다.')
  expect(vi.mocked(applySupply).mock.calls[0]).toEqual(vi.mocked(applySupply).mock.calls[1])
})

it('새로고침해도 응답 유실 작업을 복원하고 새 작업을 만들지 않는다', async () => {
  const input = { operationId: 'pending-id', snapshotToken: 'before', reason: '근거 확인', sourceUrl: 'https://example.com/notice.pdf', sourcePage: null, sheet: toSheetInput(renamed) }
  rememberApply('21', { input, result: preview })
  await setup(false)
  fireEvent.click(screen.getByRole('button', { name: '같은 작업으로 적용 결과 확인' }))
  await screen.findByText('공급정보를 저장했습니다.')
  expect(previewSupply).not.toHaveBeenCalled()
  expect(applySupply).toHaveBeenCalledWith('21', input, 'confirmed')
  expect(sessionStorage.getItem('admin:pending-supply-apply:21')).toBeNull()
})

it('이전 공급행 형식으로 남은 적용 대기 기록은 버리고 편집을 잠그지 않는다', async () => {
  sessionStorage.setItem('admin:pending-supply-apply:21', JSON.stringify({ input: { operationId: 'old', snapshotToken: 'before', reason: '근거', sourceUrl: 'https://example.com',
    sourcePage: null, rows: [{ key: 'row:1' }] }, result: { confirmationToken: 'old', before: [], after: [], warnings: [] } }))
  await setup(false)
  expect(screen.queryByRole('button', { name: '같은 작업으로 적용 결과 확인' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '직접 추가' })).toBeEnabled()
  expect(sessionStorage.getItem('admin:pending-supply-apply:21')).toBeNull()
})

it('공고 표기가 달라도 연결한 단지 이름으로 보여 같은 단지가 다른 단지처럼 보이지 않는다', async () => {
  vi.mocked(loadSupplyWorkspace).mockResolvedValue({ ...workspace, sheet: updateHousingType(sampleSheet(), 'row:2', { complexName: '두꺼비 국민임대' }) })
  await setup(false)
  const table = screen.getByRole('table', { name: '공급정보 표' })
  expect(within(table).getAllByRole('rowheader').map(cell => cell.textContent)).toEqual(['두꺼비 단지', '두꺼비 단지'])
  expect(within(table).getAllByRole('rowheader')[1]).toHaveAttribute('title', '공고 표기: 두꺼비 국민임대')
})
