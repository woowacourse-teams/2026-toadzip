import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { rememberApply } from './supplyApplyRecovery'
import { ManagementError } from '../management/api'
import { SupplyCorrectionWorkbench } from './SupplyCorrectionWorkbench'
import { applySupply, loadSupplyWorkspace, previewSupply, type CorrectionRow, type SupplyPreview } from './supplyCorrectionApi'

vi.mock('./SupplyReviewPanel', () => ({ SupplyReviewPanel: ({ evidence, pending, onReviewed }: { evidence: { note: string; sourceUrl: string; sourcePage: string | null } | null; pending: string[]; onReviewed: () => void }) =>
  <div data-testid="review-panel">{evidence ? `${evidence.note}|${evidence.sourceUrl}|${evidence.sourcePage ?? ''}` : ''}<output aria-label="검토할 바뀐 항목">{pending.join(',')}</output><button type="button" onClick={onReviewed}>모의 검토 기록</button></div> }))
vi.mock('./supplyCorrectionApi', async original => ({ ...await original<typeof import('./supplyCorrectionApi')>(), loadSupplyWorkspace: vi.fn(), previewSupply: vi.fn(), applySupply: vi.fn() }))
const row: CorrectionRow = {
  id: 1, key: 'row:1', housingComplexId: 7, housingComplexName: '한스빌', housingTypeId: 8, newHousingType: null,
  groupId: null, sharedHouseholdCount: null, protectedValues: false, excluded: false, displayOrder: 0,
  complexName: '한스빌', typeName: '26', pnu: '1114010100100010000', moveInMonth: null, category: 'NEW_SUPPLY',
  householdCount: 10, areaOverride: false, exclusiveArea: 26.4673, supplyArea: null, targetCountsComparable: false,
  targets: [{ id: 2, key: 'target:2', excluded: false, protectedValues: false, displayOrder: 0, target: '일반', supplyRank: null,
    householdCount: 10, reserveCount: null, deposit: '100000000', rent: '200000', convertedDeposit: null, condition: '공고문 확인' }],
}
const workspace = { snapshotToken: 'before', version: 1, rows: [row], sources: [] }
const preview: SupplyPreview = { confirmationToken: 'confirmed', before: [row], after: [{ ...row, typeName: '26A' }], warnings: ['연결 상태 확인 필요'] }
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  vi.mocked(loadSupplyWorkspace).mockResolvedValue(structuredClone(workspace))
  vi.mocked(previewSupply).mockResolvedValue(structuredClone(preview))
  vi.mocked(applySupply).mockResolvedValue({ changeId: 3, alreadyApplied: false, workspace: { ...workspace, snapshotToken: 'after', version: 2, rows: preview.after } })
})
async function setup(edit = true) {
  const onChanged = vi.fn()
  render(<MemoryRouter><SupplyCorrectionWorkbench id="21" sourceUrl="https://example.com/notice.pdf" onChanged={onChanged} onBusyChange={vi.fn()} onDirtyChange={vi.fn()} /></MemoryRouter>)
  await screen.findByRole('table', { name: '공급정보 표' })
  if (edit) fireEvent.click(screen.getByRole('button', { name: '한스빌 26 편집' }))
  return onChanged
}
function editAndSave() {
  fireEvent.change(screen.getByLabelText('이 공고의 주택형 표기'), { target: { value: '26A' } })
  fireEvent.change(screen.getByLabelText('보정 이유'), { target: { value: '공식 공고의 A형 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '공급정보 저장' }))
}
it('표를 먼저 보여주고 필요한 행만 펼친다. 별도 JSON 입력과 5단계는 없다', async () => {
  await setup(false)
  expect(screen.getByRole('table', { name: '공급정보 표' })).toHaveTextContent('한스빌')
  expect(screen.queryByLabelText('이 공고의 주택형 표기')).not.toBeInTheDocument()
  expect(screen.queryByText(/구조화한 공급정보|보정 진행 단계/)).not.toBeInTheDocument()
  expect(screen.queryByRole('textbox', { name: /JSON/ })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'PDF로 채우기' })).toBeEnabled()
  expect(screen.getByRole('button', { name: '직접 추가' })).toBeEnabled()
})
it('서버 검증 후 동일한 입력과 토큰으로 한 번 저장하고 하위 조건을 보존한다', async () => {
  const changed = await setup()
  editAndSave()
  await screen.findByText('공급정보를 저장했습니다.')
  expect(changed).toHaveBeenCalledOnce()
  expect(previewSupply).toHaveBeenCalledWith('21', expect.objectContaining({ snapshotToken: 'before', rows: [expect.objectContaining({ typeName: '26A', targets: row.targets })] }))
  expect(applySupply).toHaveBeenCalledExactlyOnceWith('21', vi.mocked(previewSupply).mock.calls[0][1], 'confirmed')
  expect(screen.getByLabelText('검토할 바뀐 항목')).toHaveTextContent('row:1')
})
it('제외·복구와 순서 변경은 하위 임대조건을 지우지 않는다', async () => {
  await setup()
  fireEvent.click(screen.getByRole('button', { name: '공급행 제외' }))
  expect(screen.getByLabelText('보증금 (원)')).toHaveValue('100000000')
  fireEvent.click(screen.getByRole('button', { name: '공급행 복구' }))
  fireEvent.click(screen.getByRole('button', { name: '직접 추가' }))
  fireEvent.change(screen.getByLabelText('공고의 단지명'), { target: { value: '새 단지' } })
  fireEvent.click(screen.getByRole('button', { name: '위로' }))
  fireEvent.click(screen.getByRole('button', { name: '한스빌 26 편집' }))
  expect(screen.getByLabelText('보증금 (원)')).toHaveValue('100000000')
})
it('관리자가 추가한 저장 행만 삭제할 수 있고, 삭제 예정 행은 요청 행에서 빼고 deletedRowKeys로 보낸다', async () => {
  const manual = { ...row, id: 5, key: 'row:5', complexName: '한스빌', typeName: '36', targets: [] }
  vi.mocked(loadSupplyWorkspace).mockResolvedValue({ ...workspace, rows: [row, manual], deletableRowKeys: ['row:5'] })
  vi.mocked(previewSupply).mockResolvedValue({ confirmationToken: 'confirmed', before: [row, manual], after: [row], warnings: [] })
  vi.mocked(applySupply).mockResolvedValue({ changeId: 3, alreadyApplied: false, workspace: { ...workspace, snapshotToken: 'after', rows: [row], deletableRowKeys: [] } })
  await setup()
  // 원천행에는 제외만 있다.
  expect(screen.getByRole('button', { name: '공급행 제외' })).toBeVisible()
  expect(screen.queryByRole('button', { name: '공급행 삭제' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '한스빌 36 편집' }))
  fireEvent.click(screen.getByRole('button', { name: '공급행 삭제' }))
  expect(screen.getByText('적용하면 관리자가 추가한 이 행과 하위 조건을 지웁니다. 복구할 수 없고 변경 이력에만 남습니다.')).toBeVisible()
  expect(screen.getByRole('table', { name: '공급정보 표' })).toHaveTextContent('삭제 예정')
  fireEvent.click(screen.getByRole('button', { name: '삭제 취소' }))
  expect(screen.getByRole('table', { name: '공급정보 표' })).not.toHaveTextContent('삭제 예정')
  fireEvent.click(screen.getByRole('button', { name: '공급행 삭제' }))
  fireEvent.change(screen.getByLabelText('보정 이유'), { target: { value: '중복으로 추가한 행 삭제' } })
  fireEvent.click(screen.getByRole('button', { name: '공급정보 저장' }))
  await screen.findByText('공급정보를 저장했습니다.')
  expect(previewSupply).toHaveBeenCalledWith('21', expect.objectContaining({ rows: [expect.objectContaining({ key: 'row:1' })], deletedRowKeys: ['row:5'] }))
})
it('공급행이 없는 공고에 첫 행과 임대조건을 직접 작성해 같은 저장 API로 생성한다', async () => {
  vi.mocked(loadSupplyWorkspace).mockResolvedValue({ ...workspace, rows: [] })
  await setup(false)
  expect(screen.getByText('등록된 공급정보가 없습니다.')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '직접 추가' }))
  fireEvent.change(screen.getByLabelText('공고의 단지명'), { target: { value: '첫 단지' } })
  fireEvent.change(screen.getByLabelText('이 공고의 주택형 표기'), { target: { value: '46A' } })
  fireEvent.change(screen.getByLabelText('공급 PNU'), { target: { value: '1114010100100010000' } })
  fireEvent.click(screen.getByRole('button', { name: '공급대상 추가' }))
  fireEvent.change(screen.getByLabelText('공급대상'), { target: { value: '일반' } })
  fireEvent.change(screen.getByLabelText('보증금 (원)'), { target: { value: '9007199254740993' } })
  fireEvent.change(screen.getByLabelText('보정 이유'), { target: { value: '공식 공고의 첫 공급행' } })
  fireEvent.click(screen.getByRole('button', { name: '공급정보 저장' }))
  await waitFor(() => expect(applySupply).toHaveBeenCalledOnce())
  expect(vi.mocked(applySupply).mock.calls[0][1].rows).toEqual([expect.objectContaining({ id: null, typeName: '46A', targets: [expect.objectContaining({ id: null, target: '일반', deposit: '9007199254740993' })] })])
})
it('검증 오류에서는 저장하지 않고 입력을 보존한다', async () => {
  vi.mocked(previewSupply).mockRejectedValue(new Error('잘못된 주택형 소속'))
  await setup(); editAndSave()
  expect(await screen.findByRole('alert')).toHaveTextContent('잘못된 주택형 소속')
  expect(applySupply).not.toHaveBeenCalled()
  expect(screen.getByLabelText('이 공고의 주택형 표기')).toHaveValue('26A')
})
it('동시 수정은 최신값을 가져와 겹친 필드만 선택하게 한다', async () => {
  vi.mocked(applySupply).mockRejectedValue(new ManagementError('원천이 변경됨', 409, {}, 'ADMIN_DATA_STALE'))
  await setup(); editAndSave()
  expect(await screen.findByRole('alert')).toHaveTextContent('저장하는 사이 등록값이나 원천이 바뀌어 저장하지 않았습니다.')
  expect(screen.getAllByRole('alert')).toHaveLength(1)
  expect(screen.queryByText('원천이 변경됨')).not.toBeInTheDocument()
  vi.mocked(loadSupplyWorkspace).mockResolvedValue({ ...workspace, snapshotToken: 'latest', rows: [{ ...row, typeName: '다른 관리자 형명', householdCount: 20 }] })
  fireEvent.click(screen.getByRole('button', { name: '입력을 유지하고 최신값 비교' }))
  await screen.findByText('겹치는 변경을 선택하세요')
  expect(screen.getByRole('button', { name: '공급정보 저장' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '내 입력 사용' }))
  expect(screen.getByLabelText('이 공고의 주택형 표기')).toHaveValue('26A')
  expect(screen.getByLabelText('금회 모집수 (세대)')).toHaveValue(20)
})
it('다시 조회해도 풀리지 않는 409는 서버가 알려 준 이유를 그대로 보여 주고 최신값 비교를 권하지 않는다', async () => {
  vi.mocked(previewSupply).mockRejectedValue(new ManagementError('제외·연결 변경할 단지에 접수 일정이 있습니다. 일정 적용 단지를 먼저 수정하세요.', 409, {}, 'ADMIN_DATA_CONFLICT'))
  await setup(); editAndSave()
  expect(await screen.findByRole('alert')).toHaveTextContent('제외·연결 변경할 단지에 접수 일정이 있습니다. 일정 적용 단지를 먼저 수정하세요.')
  expect(screen.queryByText(/저장하는 사이 등록값이나 원천이 바뀌어/)).not.toBeInTheDocument()
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
  const input = { operationId: 'pending-id', snapshotToken: 'before', reason: '근거 확인', sourceUrl: 'https://example.com/notice.pdf', sourcePage: null, rows: preview.after }
  rememberApply('21', { input, result: preview })
  await setup(false)
  fireEvent.click(screen.getByRole('button', { name: '같은 작업으로 적용 결과 확인' }))
  await screen.findByText('공급정보를 저장했습니다.')
  expect(previewSupply).not.toHaveBeenCalled()
  expect(applySupply).toHaveBeenCalledWith('21', input, 'confirmed')
  expect(sessionStorage.getItem('admin:pending-supply-apply:21')).toBeNull()
})

it('공고 표기가 달라도 연결한 단지 이름으로 보여 같은 단지가 다른 단지처럼 보이지 않는다', async () => {
  vi.mocked(loadSupplyWorkspace).mockResolvedValue({ ...workspace, rows: [row, { ...row, id: 2, key: 'row:2', complexName: '익산 한스빌 국민임대', typeName: '36' }] })
  render(<MemoryRouter><SupplyCorrectionWorkbench id="21" sourceUrl="https://example.com/notice.pdf" onChanged={vi.fn()} onBusyChange={vi.fn()} onDirtyChange={vi.fn()} /></MemoryRouter>)
  const table = await screen.findByRole('table', { name: '공급정보 표' })
  expect(within(table).getAllByRole('rowheader').map(cell => cell.textContent)).toEqual(['한스빌', '한스빌'])
  expect(within(table).getAllByRole('rowheader')[1]).toHaveAttribute('title', '공고 표기: 익산 한스빌 국민임대')
})
