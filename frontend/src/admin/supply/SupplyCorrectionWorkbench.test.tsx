import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { rememberApply } from './supplyApplyRecovery'
import { ManagementError } from '../management/api'
import { SupplyCorrectionWorkbench } from './SupplyCorrectionWorkbench'
import { applySupply, loadSupplyWorkspace, previewSupply, type CorrectionRow, type SupplyPreview } from './supplyCorrectionApi'

vi.mock('./SupplyReviewPanel', () => ({ SupplyReviewPanel: () => null }))
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
async function setup() {
  const onChanged = vi.fn()
  render(<MemoryRouter><SupplyCorrectionWorkbench id="21" sourceUrl="https://example.com/notice.pdf" onChanged={onChanged} onBusyChange={vi.fn()} onDirtyChange={vi.fn()} /></MemoryRouter>)
  fireEvent.click(await screen.findByText('한스빌 · 26'))
  return onChanged
}
async function editAndPreview() {
  fireEvent.change(screen.getByLabelText('공고의 주택형명'), { target: { value: '26A' } })
  fireEvent.change(screen.getByLabelText('보정 이유'), { target: { value: '공식 공고의 A형 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '변경 미리보기' }))
  await screen.findByRole('region', { name: '공급정보 변경 미리보기' })
}
it('행과 하위 조건을 유지한 미리보기를 확인한 다음에만 원자 적용한다', async () => {
  const changed = await setup()
  await editAndPreview()
  expect(applySupply).not.toHaveBeenCalled()
  expect(previewSupply).toHaveBeenCalledWith('21', expect.objectContaining({ snapshotToken: 'before', reason: '공식 공고의 A형 확인', rows: [expect.objectContaining({ typeName: '26A', targets: row.targets })] }))
  fireEvent.click(screen.getByRole('button', { name: '확인한 보정 일괄 적용' }))
  await screen.findByText('보정을 저장했습니다. 공개 결과와 남은 항목을 확인하세요.')
  expect(changed).toHaveBeenCalledOnce()
  expect(applySupply).toHaveBeenCalledWith('21', vi.mocked(previewSupply).mock.calls[0][1], 'confirmed')
  expect(screen.getByText('남은 확인 항목 1개')).toBeVisible()
  expect(screen.getByRole('link', { name: '사용자 공고 화면 확인 ↗' })).toHaveAttribute('href', '/explore?announcementId=21')
})
it('미리보기 뒤 다시 수정하면 이전 적용 버튼을 제거한다', async () => {
  await setup(); await editAndPreview()
  fireEvent.change(screen.getByLabelText('금회 모집수 (세대)'), { target: { value: '' } })
  expect(screen.queryByRole('button', { name: '확인한 보정 일괄 적용' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '변경 미리보기' }))
  await waitFor(() => expect(previewSupply).toHaveBeenCalledTimes(2))
  expect(vi.mocked(previewSupply).mock.calls[1][1].rows[0].householdCount).toBeNull()
})
it('제외는 하위 조건을 지우지 않으며 복구할 수 있다', async () => {
  await setup()
  fireEvent.click(screen.getByRole('button', { name: '공급행 제외' }))
  expect(screen.getByText(/하위 조건이 공개 결과에서 제외됩니다/)).toBeVisible()
  expect(screen.getByLabelText('보증금 (원)')).toHaveValue('100000000')
  fireEvent.click(screen.getByRole('button', { name: '공급행 복구' }))
  expect(screen.getByRole('button', { name: '공급행 제외' })).toBeVisible()
})
it('동시에 바뀐 필드는 자동 덮어쓰지 않고 현재값과 내 입력 중 선택한다', async () => {
  await setup(); await editAndPreview()
  vi.mocked(applySupply).mockRejectedValue(new ManagementError('원천이 변경됨', 409))
  fireEvent.click(screen.getByRole('button', { name: '확인한 보정 일괄 적용' }))
  await screen.findByRole('alert')
  vi.mocked(loadSupplyWorkspace).mockResolvedValue({ ...workspace, snapshotToken: 'latest', rows: [{ ...row, typeName: '다른 관리자 형명', householdCount: 20 }] })
  fireEvent.click(screen.getByRole('button', { name: '입력을 유지하고 최신값 비교' }))
  await screen.findByText('겹치는 변경을 선택하세요')
  expect(screen.getByRole('button', { name: '변경 미리보기' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '내 입력 사용' }))
  expect(screen.getByLabelText('공고의 주택형명')).toHaveValue('26A')
  expect(screen.getByLabelText('금회 모집수 (세대)')).toHaveValue(20)
  expect(screen.getByRole('button', { name: '변경 미리보기' })).toBeEnabled()
})
it('미리보기 실패는 적용 성공으로 표시하지 않고 작성 내용을 유지한다', async () => {
  await setup(); vi.mocked(previewSupply).mockRejectedValue(new Error('잘못된 주택형 소속'))
  fireEvent.change(screen.getByLabelText('공고의 주택형명'), { target: { value: '26A' } })
  fireEvent.change(screen.getByLabelText('보정 이유'), { target: { value: '원문 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '변경 미리보기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('잘못된 주택형 소속')
  expect(screen.getByLabelText('공고의 주택형명')).toHaveValue('26A')
  expect(screen.queryByRole('button', { name: '확인한 보정 일괄 적용' })).not.toBeInTheDocument()
})
it('누락 행을 추가해 원문 순서를 조정해도 기존 대상 조건은 유지한다', async () => {
  await setup()
  fireEvent.click(screen.getByRole('button', { name: '누락 공급행 추가' }))
  const newRow = screen.getByText('새 공급행 · 주택형 미입력').closest('details')!
  fireEvent.change(within(newRow).getByLabelText('공고의 단지명'), { target: { value: '새 단지' } })
  fireEvent.click(within(newRow).getByRole('button', { name: '위로' }))
  expect(screen.getByLabelText('보증금 (원)')).toHaveValue('100000000')
})

it('응답 유실 후 입력을 잠그고 동일한 작업 ID로 결과를 재확인한다', async () => {
  await setup(); await editAndPreview()
  vi.mocked(applySupply).mockRejectedValueOnce(new TypeError('Failed to fetch'))
  fireEvent.click(screen.getByRole('button', { name: '확인한 보정 일괄 적용' }))
  await screen.findByRole('button', { name: '같은 작업으로 적용 결과 확인' })
  expect(screen.getByLabelText('공고의 주택형명')).toBeDisabled()
  expect(screen.getByRole('button', { name: '입력을 유지하고 최신값 비교' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '같은 작업으로 적용 결과 확인' }))
  await screen.findByText('보정을 저장했습니다. 공개 결과와 남은 항목을 확인하세요.')
  expect(vi.mocked(applySupply).mock.calls[0]).toEqual(vi.mocked(applySupply).mock.calls[1])
})

it('새로고침 후에도 응답 유실 작업을 복원해 새 작업을 만들지 않고 재확인한다', async () => {
  const input = { operationId: 'pending-id', snapshotToken: 'before', reason: '근거 확인', sourceUrl: 'https://example.com/notice.pdf', sourcePage: null, rows: preview.after }
  rememberApply('21', { input, result: preview })
  render(<MemoryRouter><SupplyCorrectionWorkbench id="21" sourceUrl="https://example.com/notice.pdf" onChanged={vi.fn()} onBusyChange={vi.fn()} onDirtyChange={vi.fn()} /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '같은 작업으로 적용 결과 확인' }))
  await screen.findByText('보정을 저장했습니다. 공개 결과와 남은 항목을 확인하세요.')
  expect(previewSupply).not.toHaveBeenCalled()
  expect(applySupply).toHaveBeenCalledWith('21', input, 'confirmed')
  expect(sessionStorage.getItem('admin:pending-supply-apply:21')).toBeNull()
})
it('가져온 입력행은 대응을 선택하기 전까지 기존 변경안에 섞지 않는다', async () => {
  await setup()
  fireEvent.click(screen.getByText('구조화한 공급정보를 변경안으로 가져오기'))
  fireEvent.change(screen.getByLabelText('공급정보 JSON'), { target: { value: JSON.stringify({ areaUnit: 'm2', moneyUnit: 'KRW', rows: [{ typeName: '26B', householdCount: null }] }) } })
  fireEvent.click(screen.getByRole('button', { name: '입력행 확인' }))
  expect(screen.getByLabelText('공고의 주택형명')).toHaveValue('26')
  expect(screen.getByRole('button', { name: '선택한 행을 변경안에 추가' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('입력 1의 적용 대상'), { target: { value: 'row:1' } })
  fireEvent.click(screen.getByRole('button', { name: '선택한 행을 변경안에 추가' }))
  expect(screen.getByLabelText('공고의 주택형명')).toHaveValue('26B')
  expect(screen.getByLabelText('금회 모집수 (세대)')).toHaveValue(null)
  expect(screen.getByLabelText('보증금 (원)')).toHaveValue('100000000')
  expect(applySupply).not.toHaveBeenCalled()
})
it('변경 미리보기를 쓸 수 없는 이유를 버튼 설명으로 알려 준다', async () => {
  vi.mocked(previewSupply).mockRejectedValue(new ManagementError('공급정보가 변경됨', 409))
  await setup()
  const button = screen.getByRole('button', { name: '변경 미리보기' })
  expect(button).toBeDisabled()
  expect(button).toHaveAccessibleDescription(/바꾼 공급정보가 없습니다/)
  fireEvent.change(screen.getByLabelText('공고의 주택형명'), { target: { value: '26A' } })
  expect(button).toBeEnabled()
  expect(button).not.toHaveAccessibleDescription(/./)
  fireEvent.change(screen.getByLabelText('보정 이유'), { target: { value: '공식 공고의 A형 확인' } })
  fireEvent.click(button)
  await waitFor(() => expect(button).toHaveAccessibleDescription(/최신값 비교/))
  expect(button).toBeDisabled()
})
