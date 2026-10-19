import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'
import { SupplyRowDraft } from './SupplyRowDraft'
import { blankRow, type CorrectionRow } from './supplyCorrectionApi'
import { getManagementDetail, getManagementPage } from '../management/api'
import type { ManagementDetailData } from '../management/managementContract'
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(), getManagementDetail: vi.fn(), getManagementPage: vi.fn() }))
const summary = { id: 7, name: '새 단지', subtitle: '서울', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false, reviewRequired: false, updatedAt: null }
const detail: ManagementDetailData = { summary, sourceIdentifier: 'source', data: { address: { pnu: '1114010100100010000' } }, housingTypes: [{ id: 9, name: '26', exclusiveArea: 26.5195, householdCount: null }], announcements: [], supplyRows: [], schedules: [], scheduleReviewed: false }
function Editor() {
  const [row, setRow] = useState<CorrectionRow>({ ...blankRow(0), housingComplexId: 1, housingComplexName: '기존 단지', housingTypeId: 2, typeName: '26A' })
  return <SupplyRowDraft value={row} onChange={setRow} onRemove={vi.fn()} onMove={vi.fn()} first last />
}
beforeEach(() => {
  vi.resetAllMocks(); vi.mocked(getManagementDetail).mockResolvedValue(detail)
  vi.mocked(getManagementPage).mockResolvedValue({ page: 0, hasNext: false, totalElements: 1, totalPages: 1, items: [summary] })
})
it('다른 단지를 선택하면 이전 주택형을 해제하고 확인된 단지 지번을 사용한다', async () => {
  render(<Editor />)
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택: 새 단지' }))
  await screen.findByRole('option', { name: '26 · 전용 26.5195㎡ · #9' })
  expect(screen.getByLabelText('공통 주택형')).toHaveValue('')
  expect(screen.getByLabelText('공급 PNU')).toHaveValue('1114010100100010000')
  fireEvent.change(screen.getByLabelText('공통 주택형'), { target: { value: 'new' } })
  expect(screen.getByLabelText('새 공통 주택형 이름')).toHaveValue('26A')
})
it('새 단지 조회가 실패하면 기존 연결과 작성한 값을 유지한다', async () => {
  vi.mocked(getManagementDetail).mockRejectedValue(new Error('단지 조회 실패'))
  render(<Editor />)
  fireEvent.change(screen.getByLabelText('공고의 주택형명'), { target: { value: '작성한 26B' } })
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택: 새 단지' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('단지 조회 실패')
  await waitFor(() => expect(screen.getByLabelText('공고의 주택형명')).toBeEnabled())
  expect(screen.getByLabelText('공고의 주택형명')).toHaveValue('작성한 26B')
  expect(screen.getByText(/현재 선택: 기존 단지/)).toBeVisible()
})
