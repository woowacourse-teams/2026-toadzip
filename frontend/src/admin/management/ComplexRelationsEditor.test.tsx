import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { ManagementDetail } from './ManagementDetail'
import { ManagementError } from './api'
import type { ManagementDetailData } from './managementContract'

const mocks = vi.hoisted(() => ({ getManagementDetail: vi.fn(), requestManagementApi: vi.fn(), getManagementHistory: vi.fn() }))
vi.mock('./api', async original => ({ ...(await original<typeof import('./api')>()), ...mocks }))
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
  fireEvent.submit(screen.getByRole('button', { name: '주택형 저장' }).closest('form')!)
  await waitFor(() => expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/housing-complexes/7/housing-types/9', 'PUT', { version: 2, name: '59B', exclusiveArea: 59.1234, householdCount: 0 }))
  expect(await screen.findByRole('button', { name: '59B 수정' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '59B 수정' }))
  fireEvent.change(screen.getByLabelText('세대수'), { target: { value: '' } })
  fireEvent.submit(screen.getByRole('button', { name: '주택형 저장' }).closest('form')!)
  await waitFor(() => expect(mocks.requestManagementApi).toHaveBeenLastCalledWith(expect.any(String), 'PUT', expect.objectContaining({ version: 3, householdCount: null })))
})

it('주택형 저장 충돌은 입력을 유지하고 탭 이동 취소는 편집을 유지한다', async () => {
  mocks.requestManagementApi.mockRejectedValue(new ManagementError('다른 작업에서 변경한 단지입니다.', 409))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  await openRelations()
  fireEvent.click(screen.getByRole('button', { name: '46A 수정' }))
  fireEvent.change(screen.getByLabelText('주택형 이름'), { target: { value: '수정한 주택형' } })
  fireEvent.submit(screen.getByRole('button', { name: '주택형 저장' }).closest('form')!)
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
  fireEvent.change(await within(editor).findByLabelText('이름'), { target: { value: '변경한 입주 공고' } })
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

it('연결 공고 공급정보의 단지 연결을 해제하면 단지의 연결 목록을 갱신한다', async () => {
  const supplyRow = { id: 45, housingComplexId: 7, housingComplexName: summary.name, housingTypeId: 9, modified: false,
    data: { sourceComplexName: summary.name, sourceHousingTypeName: '46A', supplyPnu: '1114010100100010000',
      supplyCategory: 'NEW_SUPPLY', totalSupplyHouseholdCount: 10 } }
  mocks.getManagementDetail.mockImplementation((resource: string) => Promise.resolve(resource === 'complexes' ? complex : { ...announcement, supplyRows: [supplyRow] }))
  let complete!: (value: ManagementDetailData) => void
  mocks.requestManagementApi.mockReturnValue(new Promise<ManagementDetailData>(resolve => { complete = resolve }))
  await openRelations()
  fireEvent.click(screen.getByRole('button', { name: '두꺼비 입주 공고 수정' }))
  const editor = await screen.findByRole('region', { name: '연결 공고 편집' })
  fireEvent.click(await within(editor).findByRole('button', { name: '공급정보·단지 연결' }))
  fireEvent.click(within(editor).getByRole('button', { name: '공급정보 수정' }))
  await within(editor).findByRole('option', { name: '46A · 46.8㎡' })
  fireEvent.click(within(editor).getByRole('button', { name: '단지 연결 해제' }))
  fireEvent.submit(within(editor).getByRole('button', { name: '공급정보 저장' }).closest('form')!)
  await waitFor(() => expect(screen.getByRole('button', { name: '주택형·연결 공고' })).toBeDisabled())
  expect(screen.getByRole('button', { name: '두꺼비 입주 공고 수정' })).toBeDisabled()
  expect(mocks.requestManagementApi).toHaveBeenCalledWith('/api/admin/announcements/21/supply-rows/45', 'PUT', expect.objectContaining({ version: 4, housingComplexId: null, housingTypeId: null }))
  mocks.getManagementDetail.mockResolvedValue({ ...complex, announcements: [] })
  complete({ ...announcement, data: { ...announcement.data, version: 5 }, supplyRows: [{ ...supplyRow, housingComplexId: null, housingComplexName: null, housingTypeId: null }] })
  expect(await screen.findByText('표시할 공고가 없습니다.')).toBeVisible()
  expect(within(editor).getByText('연결 단지: 미연결')).toBeVisible()
})
