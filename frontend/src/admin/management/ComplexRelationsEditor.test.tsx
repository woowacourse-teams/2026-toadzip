import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ManagementDetail } from './ManagementDetail'
import { ManagementError } from './api'
import { applySupply, loadSupplyWorkspace, previewSupply, blankRow } from '../supply/supplyCorrectionApi'
import type { ManagementDetailData } from './managementContract'

const mocks = vi.hoisted(() => ({ getManagementDetail: vi.fn(), requestManagementApi: vi.fn(), getManagementHistory: vi.fn() }))
vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), ...mocks }))
vi.mock('../supply/SupplyReviewPanel', () => ({ SupplyReviewPanel: () => null }))
vi.mock('../supply/supplyCorrectionApi', async original => ({ ...await original<typeof import('../supply/supplyCorrectionApi')>(), loadSupplyWorkspace: vi.fn(), previewSupply: vi.fn(), applySupply: vi.fn() }))
vi.mock('./housingTypeImpactApi', () => ({ loadHousingTypeImpact: () => Promise.resolve({ token: 'impact', rows: [] }) }))
const summary = { id: 7, name: '두꺼비 단지', subtitle: '서울', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null }
const announcement: ManagementDetailData = {
  summary: { ...summary, id: 21, name: '두꺼비 입주 공고' }, sourceIdentifier: 'A-21',
  data: { version: 4, name: '두꺼비 입주 공고', rentalType: 'HAPPY_HOUSING', agencyCode: 'LH', recruitmentType: 'NEW',
    originalUrl: 'https://example.com/notice', postedDate: '2026-10-01', applicationStartDate: '2026-10-10',
    applicationEndDate: '2026-10-20', winnerAnnouncementDate: '2026-11-01' },
  housingTypes: [], announcements: [], supplyRows: [], schedules: [], scheduleReviewed: false,
}
const complex: ManagementDetailData = {
  summary, sourceIdentifier: 'C-7', data: { version: 2, name: summary.name },
  housingTypes: [{ id: 9, name: '46A', exclusiveArea: 46.8, householdCount: null }],
  announcements: [announcement.summary], supplyRows: [], schedules: [], scheduleReviewed: false,
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.getManagementDetail.mockImplementation((resource: string) => Promise.resolve(resource === 'complexes' ? complex : announcement))
  mocks.getManagementHistory.mockResolvedValue([])
  vi.mocked(loadSupplyWorkspace).mockResolvedValue({ snapshotToken: 'before', version: 4, rows: [], sources: [] })
})
async function openRelations() {
  render(<MemoryRouter initialEntries={['/admin/complexes/7']}><Routes>
    <Route path="/admin/complexes/:id" element={<ManagementDetail resource="complexes" embedded />} />
  </Routes></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: '주택형·연결 공고' }))
}

it('주택형을 수정하고 세대수 미확인과 0을 구분해 저장한다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ ...complex, data: { version: 3 }, housingTypes: [{ id: 9, name: '59B', exclusiveArea: 59.1234, householdCount: 0 }] })
  await openRelations()
  fireEvent.click(screen.getByRole('button', { name: '46A 수정' }))
  fireEvent.change(screen.getByLabelText('주택형 이름'), { target: { value: '59B' } })
  fireEvent.change(screen.getByLabelText('전용면적 (㎡)'), { target: { value: '59.1234' } })
  fireEvent.change(screen.getByLabelText('세대수'), { target: { value: '0' } })
  fireEvent.submit(screen.getByRole('button', { name: '수정 영향 확인' }).closest('form')!)
  fireEvent.click(await screen.findByRole('button', { name: '영향을 확인한 주택형 저장' }))
  await waitFor(() => expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/housing-complexes/7/housing-types/9', 'PUT', { version: 2, name: '59B', exclusiveArea: 59.1234, householdCount: 0, impactToken: 'impact' }))
  expect(await screen.findByRole('button', { name: '59B 수정' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '59B 수정' }))
  fireEvent.change(screen.getByLabelText('세대수'), { target: { value: '' } })
  fireEvent.submit(screen.getByRole('button', { name: '수정 영향 확인' }).closest('form')!)
  fireEvent.click(await screen.findByRole('button', { name: '영향을 확인한 주택형 저장' }))
  await waitFor(() => expect(mocks.requestManagementApi).toHaveBeenLastCalledWith(expect.any(String), 'PUT', expect.objectContaining({ version: 3, householdCount: null })))
})

it('주택형 저장 충돌은 입력을 유지하고 탭 이동 취소는 편집을 유지한다', async () => {
  mocks.requestManagementApi.mockRejectedValue(new ManagementError('다른 작업에서 변경한 단지입니다.', 409))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  await openRelations()
  fireEvent.click(screen.getByRole('button', { name: '46A 수정' }))
  fireEvent.change(screen.getByLabelText('주택형 이름'), { target: { value: '수정한 주택형' } })
  fireEvent.submit(screen.getByRole('button', { name: '수정 영향 확인' }).closest('form')!)
  fireEvent.click(await screen.findByRole('button', { name: '영향을 확인한 주택형 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 작업에서 변경한 단지')
  fireEvent.click(screen.getByRole('button', { name: '기본정보' }))
  expect(confirm).toHaveBeenCalledOnce()
  expect(screen.getByLabelText('주택형 이름')).toHaveValue('수정한 주택형')
  confirm.mockRestore()
})

it('연결 공고를 같은 단지 탭 안에서 열고 공고 ID로 수정한다', async () => {
  mocks.requestManagementApi.mockResolvedValue({ ...announcement, data: { ...announcement.data, version: 5, name: '변경한 입주 공고' } })
  await openRelations()
  fireEvent.click(screen.getByRole('button', { name: '두꺼비 입주 공고 수정' }))
  const editor = await screen.findByRole('region', { name: '연결 공고 편집' })
  fireEvent.click(await within(editor).findByRole('button', { name: '기본정보 수정' }))
  fireEvent.change(within(editor).getByLabelText('이름'), { target: { value: '변경한 입주 공고' } })
  fireEvent.submit(within(editor).getByRole('button', { name: '변경사항 저장' }).closest('form')!)
  await waitFor(() => expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/announcements/21', 'PUT', expect.objectContaining({ version: 4, name: '변경한 입주 공고' })))
  expect(await within(editor).findByRole('status')).toHaveTextContent('변경사항을 저장했습니다.')
  await waitFor(() => expect(within(editor).getByRole('button', { name: '공급정보·단지 연결' })).toBeEnabled())
  expect(screen.getByRole('button', { name: '주택형·연결 공고' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(within(editor).getByRole('button', { name: '공급정보·단지 연결' }))
  expect(within(editor).getByText('등록된 공급정보가 없습니다.')).toBeVisible()
  expect(mocks.getManagementDetail).toHaveBeenCalledWith('announcements', '21', expect.any(AbortSignal))
})

it('휴지통 단지의 주택형은 편집할 수 없다', async () => {
  mocks.getManagementDetail.mockResolvedValue({ ...complex, summary: { ...summary, deleted: true } })
  await openRelations()
  expect(screen.queryByRole('button', { name: '46A 수정' })).not.toBeInTheDocument()
})

it('연결 공고의 일괄 보정 중 탐색을 막고 적용 후 단지의 연결 목록을 갱신한다', async () => {
  const row = { ...blankRow(0), id: 45, key: 'row:45', housingComplexId: 7, housingComplexName: summary.name,
    housingTypeId: 9, complexName: summary.name, typeName: '46A', pnu: '1114010100100010000' }
  const workspace = { snapshotToken: 'before', version: 4, rows: [row], sources: [] }
  vi.mocked(loadSupplyWorkspace).mockResolvedValue(workspace)
  vi.mocked(previewSupply).mockResolvedValue({ confirmationToken: 'preview', before: [row], after: [{ ...row, housingComplexId: null, housingTypeId: null }], warnings: [] })
  let complete!: (value: Awaited<ReturnType<typeof applySupply>>) => void
  vi.mocked(applySupply).mockReturnValue(new Promise(resolve => { complete = resolve }))
  await openRelations()
  fireEvent.click(screen.getByRole('button', { name: '두꺼비 입주 공고 수정' }))
  const editor = await screen.findByRole('region', { name: '연결 공고 편집' })
  fireEvent.click(await within(editor).findByRole('button', { name: '공급정보·단지 연결' }))
  fireEvent.click(await within(editor).findByText('두꺼비 단지 · 46A'))
  fireEvent.click(within(editor).getByRole('button', { name: '단지·주택형 연결 해제' }))
  fireEvent.change(within(editor).getByLabelText('보정 이유'), { target: { value: '다른 단지로 잘못 연결된 행 확인' } })
  fireEvent.click(within(editor).getByRole('button', { name: '변경 미리보기' }))
  fireEvent.click(await within(editor).findByRole('button', { name: '확인한 보정 일괄 적용' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '주택형·연결 공고' })).toBeDisabled())
  expect(screen.getByRole('button', { name: '두꺼비 입주 공고 수정' })).toBeDisabled()
  expect(applySupply).toHaveBeenCalledWith('21', expect.objectContaining({ rows: [expect.objectContaining({ housingComplexId: null, housingTypeId: null })] }), 'preview')
  mocks.getManagementDetail.mockImplementation((resource: string) => Promise.resolve(resource === 'complexes' ? { ...complex, announcements: [] } : announcement))
  complete({ changeId: 1, alreadyApplied: false, workspace: { ...workspace, snapshotToken: 'after', version: 5, rows: [{ ...row, housingComplexId: null, housingTypeId: null }] } })
  expect(await screen.findByText('표시할 공고가 없습니다.')).toBeVisible()
})
