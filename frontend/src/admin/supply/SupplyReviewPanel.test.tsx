import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { SupplyReviewPanel } from './SupplyReviewPanel'
import { blankRow } from './supplyCorrectionApi'
import { loadSupplyReviews, saveSupplyReview } from './supplyReviewApi'
vi.mock('./supplyReviewApi', async original => ({ ...await original<typeof import('./supplyReviewApi')>(), loadSupplyReviews: vi.fn(), saveSupplyReview: vi.fn() }))
const overview = { values: { 'row:1': { householdCount: 10, names: { typeName: '26A' } } }, records: [] }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(loadSupplyReviews).mockResolvedValue(overview); vi.mocked(saveSupplyReview).mockResolvedValue(overview) })
async function open(disabled = false) {
  render(<SupplyReviewPanel id="21" workspace={{ snapshotToken: 'snapshot', version: 1, sources: [], rows: [{ ...blankRow(0), id: 1, key: 'row:1', typeName: '26A', complexName: '한스빌' }] }} sourceUrl="https://example.com/notice.pdf" disabled={disabled} onBusyChange={vi.fn()} />)
  fireEvent.click(screen.getByText('저장된 공급정보 검토·보류'))
  await screen.findByLabelText('검토할 행·조건')
}
it('확인한 항목과 보류 이유만 기록하며 미선택 항목을 완료로 보내지 않는다', async () => {
  await open()
  fireEvent.change(screen.getByLabelText('검토할 행·조건'), { target: { value: 'row:1' } })
  fireEvent.click(screen.getByRole('checkbox', { name: '금회 모집수 (세대)' }))
  fireEvent.change(screen.getByLabelText('검토 결과'), { target: { value: 'ON_HOLD' } })
  fireEvent.change(screen.getByLabelText('확인 근거·보류 사유'), { target: { value: '170이 공동 모집인지 추가 확인 필요' } })
  fireEvent.click(screen.getByRole('button', { name: '선택 범위 검토 기록' }))
  await waitFor(() => expect(saveSupplyReview).toHaveBeenCalledWith('21', expect.objectContaining({ fields: ['householdCount'], outcome: 'ON_HOLD', sources: [], snapshotToken: 'snapshot' })))
  expect(await screen.findByRole('status')).toHaveTextContent('다른 항목의 검토 상태는 확정하지 않습니다')
})
it('저장 전 보정안으로는 검토 완료를 기록할 수 없다', async () => {
  await open(true)
  expect(screen.getByLabelText('검토할 행·조건')).toBeDisabled()
  expect(screen.getByRole('button', { name: '선택 범위 검토 기록' })).toBeDisabled()
})
