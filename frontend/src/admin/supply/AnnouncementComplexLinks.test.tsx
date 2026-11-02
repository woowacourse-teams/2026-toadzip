import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ManagementError } from '../management/api'
import { SupplyCorrectionWorkbench } from './SupplyCorrectionWorkbench'
import { linkAnnouncementComplex, loadSupplyWorkspace, type AnnouncementComplex, type CorrectionRow, type SupplyWorkspace } from './supplyCorrectionApi'

vi.mock('./SupplyReviewPanel', () => ({ SupplyReviewPanel: () => null }))
vi.mock('./supplyCorrectionApi', async original => ({ ...await original<typeof import('./supplyCorrectionApi')>(), loadSupplyWorkspace: vi.fn(), linkAnnouncementComplex: vi.fn() }))
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(),
  getManagementPage: async () => ({ items: [{ id: 9, name: '전주효자 휴먼시아5단지', subtitle: '전북 전주시' }] }) }))

const row: CorrectionRow = {
  id: 1, key: 'row:1', housingComplexId: 7, housingComplexName: '전주효자 휴먼시아6단지', housingTypeId: 8, newHousingType: null,
  groupId: null, sharedHouseholdCount: null, protectedValues: false, excluded: false, displayOrder: 0,
  complexName: '효자5국민임대 B2블록', typeName: '46A', pnu: '', moveInMonth: null, category: 'RESUPPLY',
  householdCount: 2, areaOverride: false, exclusiveArea: 46.98, supplyArea: null, targetCountsComparable: false, targets: [],
}
const auto: AnnouncementComplex = { id: 239, name: '효자5국민임대 B2블록', roadAddress: '전북특별자치도 전주시 완산구 호암로 88', housingComplexId: 7,
  housingComplexName: '전주효자 휴먼시아6단지', linkMethod: 'AUTO', rowCount: 1 }
const workspace: SupplyWorkspace = { snapshotToken: 'before', version: 1, rows: [row], sources: [],
  announcementComplexes: [auto, { ...auto, id: 238, name: '효자5국민임대 B1블록', linkMethod: 'ADMIN' }] }
const moved: SupplyWorkspace = { ...workspace, snapshotToken: 'after', rows: [{ ...row, housingComplexId: 9, housingComplexName: '전주효자 휴먼시아5단지' }],
  announcementComplexes: [{ ...auto, housingComplexId: 9, housingComplexName: '전주효자 휴먼시아5단지', linkMethod: 'ADMIN' }, workspace.announcementComplexes![1]] }

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(loadSupplyWorkspace).mockResolvedValue(structuredClone(workspace))
  vi.mocked(linkAnnouncementComplex).mockResolvedValue(structuredClone(moved))
})
afterEach(() => vi.restoreAllMocks())
async function setup() {
  const onChanged = vi.fn()
  render(<MemoryRouter><SupplyCorrectionWorkbench id="131" sourceUrl="https://example.com/notice.pdf" onChanged={onChanged} onBusyChange={vi.fn()} onDirtyChange={vi.fn()} /></MemoryRouter>)
  await screen.findByRole('table', { name: '공고 단지 연결 표' })
  return onChanged
}

it('공고 단지마다 연결 단지와 연결 방법을 보여 주고, 확인 전 연결에만 연결 맞음을 둔다', async () => {
  await setup()
  const table = screen.getByRole('table', { name: '공고 단지 연결 표' })
  expect(within(table).getAllByRole('row').map(item => item.textContent)).toEqual(expect.arrayContaining([
    expect.stringContaining('자동 연결 · 확인 전'), expect.stringContaining('관리자 확인')]))
  expect(screen.getByRole('button', { name: '효자5국민임대 B2블록 연결 맞음' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '효자5국민임대 B1블록 연결 맞음' })).not.toBeInTheDocument()
})

it('연결 맞음은 지금 단지로 관리자 연결을 저장하고 확인창 없이 공급정보를 새로 받는다', async () => {
  const confirm = vi.spyOn(window, 'confirm')
  const onChanged = await setup()
  fireEvent.click(screen.getByRole('button', { name: '효자5국민임대 B2블록 연결 맞음' }))
  await waitFor(() => expect(linkAnnouncementComplex).toHaveBeenCalledWith('131', 239, { snapshotToken: 'before', housingComplexId: 7 }))
  expect(confirm).not.toHaveBeenCalled()
  expect(await screen.findByText("'효자5국민임대 B2블록'의 연결을 확인했습니다.")).toBeInTheDocument()
  expect(onChanged).toHaveBeenCalled()
})

it('단지를 바꾸면 공급행도 옮긴다고 확인받고 저장한 뒤 공급정보 표를 새 단지로 갱신한다', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  await setup()
  fireEvent.click(screen.getByRole('button', { name: '효자5국민임대 B2블록 단지 바꾸기' }))
  const picker = screen.getByRole('group', { name: '효자5국민임대 B2블록 연결 단지 찾기' })
  fireEvent.click(await within(picker).findByRole('button', { name: '선택: 전주효자 휴먼시아5단지' }))
  await waitFor(() => expect(linkAnnouncementComplex).toHaveBeenCalledWith('131', 239, { snapshotToken: 'before', housingComplexId: 9 }))
  expect(confirm.mock.calls[0][0]).toContain('공급행 1개도 새 단지의 같은 전용면적 주택형으로 옮깁니다')
  await waitFor(() => expect(screen.getByRole('table', { name: '공급정보 표' })).toHaveTextContent('전주효자 휴먼시아5단지'))
  expect(screen.queryByRole('group', { name: '효자5국민임대 B2블록 연결 단지 찾기' })).not.toBeInTheDocument()
})

it('확인창에서 취소하면 저장하지 않는다', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(false)
  await setup()
  fireEvent.click(screen.getByRole('button', { name: '효자5국민임대 B2블록 단지 바꾸기' }))
  fireEvent.click(await within(screen.getByRole('group', { name: '효자5국민임대 B2블록 연결 단지 찾기' })).findByRole('button', { name: '선택: 전주효자 휴먼시아5단지' }))
  expect(linkAnnouncementComplex).not.toHaveBeenCalled()
})

it('편집 중인 공급정보가 있으면 연결을 바꾸지 못하게 한다', async () => {
  await setup()
  fireEvent.click(screen.getByRole('button', { name: '효자5국민임대 B2블록 46A 제외' }))
  expect(screen.getByRole('button', { name: '효자5국민임대 B2블록 단지 바꾸기' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '효자5국민임대 B2블록 연결 맞음' })).toBeDisabled()
  expect(screen.getByText('편집 중인 공급정보를 저장하거나 취소한 뒤 연결을 바꿀 수 있습니다.')).toBeInTheDocument()
})

it('그사이 공급정보가 바뀌었으면 다시 불러오라고 알린다', async () => {
  vi.mocked(linkAnnouncementComplex).mockRejectedValue(new ManagementError('공급정보가 바뀌었습니다.', 409, {}, 'ADMIN_DATA_STALE'))
  await setup()
  fireEvent.click(screen.getByRole('button', { name: '효자5국민임대 B2블록 연결 맞음' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('그사이 공급정보가 바뀌었습니다. 공급정보를 다시 불러온 뒤 연결하세요.')
})
