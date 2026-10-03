import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { SupplyEditor } from './SupplyEditor'
import { getManagementDetail, getManagementPage, requestManagementApi } from './api'
import type { ManagementDetailData, ManagementSupplyRow } from './managementContract'

vi.mock('./api', async original => ({
  ...await original<typeof import('./api')>(), getManagementDetail: vi.fn(), getManagementPage: vi.fn(), requestManagementApi: vi.fn(),
}))
const summary = { id: 2, name: '새 단지', subtitle: '서울', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null }
const row: ManagementSupplyRow = { id: 8, housingComplexId: 1, housingComplexName: '기존 단지', housingTypeId: 11, modified: false, data: { sourceComplexName: '원문 단지', sourceHousingTypeName: '36A', supplyPnu: '111', supplyCategory: 'NEW_SUPPLY', totalSupplyHouseholdCount: 0 } }
function detail(id = 1): ManagementDetailData {
  return { summary, sourceIdentifier: 'LH-2', data: { version: 0 }, housingTypes: [{ id: id * 11, name: id === 1 ? '기존형' : '새형', exclusiveArea: 36, householdCount: null }], announcements: [], supplyRows: [row], scheduleReviewed: false, schedules: [] }
}
function renderEditor() {
  const onSaved = vi.fn()
  const result = render(<MemoryRouter><SupplyEditor row={row} announcementId="7" version={0} deleted={false} onSaved={onSaved} /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: '공급정보 수정' }))
  return { ...result, onSaved }
}
async function selectNewComplex() {
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택: 새 단지' }))
}
beforeEach(() => {
  vi.mocked(getManagementDetail).mockReset().mockResolvedValue(detail())
  vi.mocked(getManagementPage).mockReset().mockResolvedValue({ items: [summary], page: 0, hasNext: false, totalElements: 1, totalPages: 1 })
  vi.mocked(requestManagementApi).mockReset().mockResolvedValue(detail())
})

it('단지 변경 시 이전 주택형을 비우고 새 연결과 버전·0 세대수를 저장한다', async () => {
  const { onSaved } = renderEditor()
  await screen.findByRole('option', { name: '기존형 · 36㎡' })
  vi.mocked(getManagementDetail).mockResolvedValue(detail(2))
  await selectNewComplex()
  await screen.findByRole('option', { name: '새형 · 36㎡' })
  expect(screen.getByLabelText('주택형')).toHaveValue('')
  fireEvent.change(screen.getByLabelText('주택형'), { target: { value: '22' } })
  fireEvent.click(screen.getByRole('button', { name: '공급정보 저장' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledWith(detail()))
  expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/announcements/7/supply-rows/8', 'PUT', {
    version: 0, housingComplexId: 2, housingTypeId: 22, supplyRow: { ...row.data, expectedMoveInMonth: null },
  })
})

it('새 단지 조회 실패 시 기존 연결과 입력을 유지하고 다시 시도할 수 있다', async () => {
  renderEditor()
  await screen.findByRole('option', { name: '기존형 · 36㎡' })
  fireEvent.change(screen.getByLabelText('원문 단지명'), { target: { value: '변경 중' } })
  vi.mocked(getManagementDetail).mockRejectedValueOnce(new Error('단지 조회 실패'))
  await selectNewComplex()
  expect(await screen.findByRole('alert')).toHaveTextContent('단지 조회 실패')
  expect(screen.getByText('기존 단지')).toBeVisible()
  expect(screen.getByLabelText('주택형')).toHaveValue('11')
  expect(screen.getByLabelText('원문 단지명')).toHaveValue('변경 중')
  expect(screen.getByRole('button', { name: '공급정보 저장' })).toBeEnabled()
})

it.each(['resolve', 'reject'] as const)('새 단지 선택 후 늦은 기존 단지 조회 %s가 주택형·오류를 덮어쓰지 않는다', async outcome => {
  let resolve!: (value: ManagementDetailData) => void
  let reject!: (error: Error) => void
  const pending = new Promise<ManagementDetailData>((yes, no) => { resolve = yes; reject = no })
  vi.mocked(getManagementDetail).mockReturnValueOnce(pending).mockResolvedValue(detail(2))
  renderEditor()
  await selectNewComplex()
  await screen.findByRole('option', { name: '새형 · 36㎡' })
  fireEvent.change(screen.getByLabelText('주택형'), { target: { value: '22' } })
  await act(async () => { if (outcome === 'resolve') resolve(detail()); else reject(new Error('오래된 조회 실패')) })
  expect(screen.getByRole('option', { name: '새형 · 36㎡' })).toBeVisible()
  expect(screen.getByLabelText('주택형')).toHaveValue('22')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('취소한 편집의 조회는 중단하고 재편집의 결과에 섞지 않는다', async () => {
  let resolve!: (value: ManagementDetailData) => void
  vi.mocked(getManagementDetail).mockReturnValueOnce(new Promise<ManagementDetailData>(yes => { resolve = yes })).mockResolvedValue(detail())
  const { unmount } = renderEditor()
  const previousSignal = vi.mocked(getManagementDetail).mock.calls[0][2]
  fireEvent.click(screen.getByRole('button', { name: '취소' }))
  expect(previousSignal?.aborted).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: '공급정보 수정' }))
  await screen.findByRole('option', { name: '기존형 · 36㎡' })
  await act(async () => resolve(detail(2)))
  expect(screen.queryByRole('option', { name: '새형 · 36㎡' })).not.toBeInTheDocument()
  const latestSignal = vi.mocked(getManagementDetail).mock.calls[1][2]
  unmount()
  expect(latestSignal?.aborted).toBe(true)
})
