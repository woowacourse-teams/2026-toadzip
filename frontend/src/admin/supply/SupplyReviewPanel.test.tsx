import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { SupplyReviewPanel } from './SupplyReviewPanel'
import { blankRow, blankTarget } from './supplyCorrectionApi'
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
it('방금 적용한 보정의 근거로 검토 폼을 펼쳐 채우고 미저장 입력으로 보지 않는다', async () => {
  const dirty = vi.fn(); const reviewed = vi.fn()
  render(<SupplyReviewPanel id="21" workspace={{ snapshotToken: 'snapshot', version: 1, sources: [], rows: [{ ...blankRow(0), id: 1, key: 'row:1', typeName: '26A', complexName: '한스빌' }] }}
    sourceUrl="https://example.com/notice.pdf" disabled={false} onBusyChange={vi.fn()} onDirtyChange={dirty} onReviewed={reviewed}
    evidence={{ note: '공식 공고의 A형 확인', sourceUrl: 'https://example.com/correction.pdf', sourcePage: '12쪽' }} />)
  expect(await screen.findByLabelText('확인 근거·보류 사유')).toHaveValue('공식 공고의 A형 확인')
  expect(screen.getByLabelText('검토 근거 URL')).toHaveValue('https://example.com/correction.pdf')
  expect(screen.getByLabelText('검토 페이지·표 위치')).toHaveValue('12쪽')
  expect(screen.getByText(/방금 적용한 보정의 근거를 채웠습니다/)).toBeVisible()
  expect(dirty).toHaveBeenLastCalledWith(false)
  fireEvent.change(screen.getByLabelText('검토할 행·조건'), { target: { value: 'row:1' } })
  fireEvent.click(screen.getByRole('checkbox', { name: '금회 모집수 (세대)' }))
  fireEvent.click(screen.getByRole('button', { name: '선택 범위 검토 기록' }))
  await waitFor(() => expect(reviewed).toHaveBeenCalledOnce())
  expect(saveSupplyReview).toHaveBeenCalledWith('21', expect.objectContaining({ note: '공식 공고의 A형 확인', sourceUrl: 'https://example.com/correction.pdf', sourcePage: '12쪽' }))
  expect(dirty).toHaveBeenLastCalledWith(false)
})
it('방금 바꾼 항목을 순서대로 선택하고 확인 항목은 자동으로 체크하지 않는다', async () => {
  const many = { values: { 'row:1': { householdCount: 10 }, 'target:2': { rent: '200000' }, 'row:3': { householdCount: 5 } }, records: [] }
  vi.mocked(loadSupplyReviews).mockResolvedValue(many); vi.mocked(saveSupplyReview).mockResolvedValue(many)
  const reviewed = vi.fn()
  render(<SupplyReviewPanel id="21" workspace={{ snapshotToken: 'snapshot', version: 1, sources: [], rows: [
    { ...blankRow(0), id: 1, key: 'row:1', typeName: '26A', complexName: '한스빌', targets: [{ ...blankTarget(0), id: 2, key: 'target:2', target: '일반' }] },
    { ...blankRow(1), id: 3, key: 'row:3', typeName: '36', complexName: '한스빌' }] }}
    sourceUrl="https://example.com/notice.pdf" disabled={false} onBusyChange={vi.fn()} onReviewed={reviewed}
    evidence={{ note: '공식 공고 확인', sourceUrl: 'https://example.com/notice.pdf', sourcePage: null }} pending={['row:1', 'target:2']} />)
  const select = await screen.findByLabelText('검토할 행·조건')
  await waitFor(() => expect(select).toHaveValue('row:1'))
  expect(screen.getByRole('group', { name: '방금 바꾼 항목 2개' })).toBeInTheDocument()
  expect(screen.getByText('방금 바꾼 항목 2개가 남았습니다.')).toBeVisible()
  expect(screen.getByRole('checkbox', { name: '금회 모집수 (세대)' })).not.toBeChecked()
  fireEvent.click(screen.getByRole('checkbox', { name: '금회 모집수 (세대)' }))
  fireEvent.click(screen.getByRole('button', { name: '선택 범위 검토 기록' }))
  await waitFor(() => expect(select).toHaveValue('target:2'))
  expect(saveSupplyReview).toHaveBeenLastCalledWith('21', expect.objectContaining({ target: 'row:1', fields: ['householdCount'] }))
  expect(screen.getByLabelText('확인 근거·보류 사유')).toHaveValue('공식 공고 확인')
  expect(screen.getByText('방금 바꾼 항목 1개가 남았습니다.')).toBeVisible()
  expect(reviewed).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('checkbox', { name: '월세 (원)' }))
  fireEvent.click(screen.getByRole('button', { name: '선택 범위 검토 기록' }))
  await waitFor(() => expect(reviewed).toHaveBeenCalledOnce())
  expect(select).toHaveValue('')
  expect(screen.getByLabelText('확인 근거·보류 사유')).toHaveValue('')
})
