import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { LocationSummaryUpload } from './LocationSummaryUpload'
import { uploadLocationSummary, startDataPipeline, type LocationSummaryImportReport } from './api'

vi.mock('./LocationPreparation', () => ({ LocationPreparation: () => <section aria-label="주소·좌표 준비 현황"><h3>단지별 준비 현황</h3></section> }))
vi.mock('./api', () => ({ uploadLocationSummary: vi.fn(), startDataPipeline: vi.fn() }))
const upload = vi.mocked(uploadLocationSummary)
beforeEach(() => vi.resetAllMocks())
const report: LocationSummaryImportReport = {
  sourceFileName: 'summary.zip', textFileCount: 16, scannedRowCount: 6422078,
  targetRoadAddressCount: 3500, matchedRoadAddressCount: 3400, unmatchedRoadAddressCount: 100,
  preparedRoadAddressCount: 3300, storedLocationCount: 3600, replacedRowCount: 12, invalidatedMappingCandidateCount: 0, provinceCodes: ['11'],
}

function show(embedded = false, disabled = false, onBusyChange = vi.fn()) {
  render(<MemoryRouter><LocationSummaryUpload embedded={embedded} disabled={disabled} onBusyChange={onBusyChange} /></MemoryRouter>)
  return onBusyChange
}
function select() {
  const file = new File(['fixture'], 'summary.zip', { type: 'application/zip' })
  fireEvent.change(screen.getByLabelText('위치정보요약DB 월 전체분 ZIP'), { target: { files: [file] } })
  return file
}
it.each([false, true])('ZIP 업로드와 단지별 준비 현황을 독립된 영역으로 구분한다 (embedded=%s)', embedded => {
  show(embedded)
  const uploadRegion = screen.getByRole('region', { name: '좌표 ZIP 업로드' })
  const preparationRegion = screen.getByRole('region', { name: '주소·좌표 준비 현황' })
  expect(within(uploadRegion).getByRole('heading', { name: '좌표 자료 업로드' })).toBeVisible()
  expect(uploadRegion).not.toContainElement(preparationRegion)
  expect(within(preparationRegion).getByRole('heading', { name: '단지별 준비 현황' })).toBeVisible()
})
it('ZIP 선택 후 업로드하고 처리 중 입력과 중복 요청을 막으며 종료 안내를 표시한다', async () => {
  let resolve: (value: LocationSummaryImportReport) => void = () => {}
  upload.mockReturnValue(new Promise(complete => { resolve = complete }))
  const busy = show()
  expect(screen.getByRole('button', { name: '좌표 데이터 업로드' })).toBeDisabled()
  const file = select()
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
  expect(upload).toHaveBeenCalledExactlyOnceWith(file)
  expect(screen.getByLabelText('위치정보요약DB 월 전체분 ZIP')).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('좌표 파일을 처리하고 있습니다. 완료될 때까지 이 페이지를 닫지 마세요.')
  expect(busy).toHaveBeenCalledWith(true)
  await act(async () => resolve(report))
  expect(busy).toHaveBeenLastCalledWith(false)
})
it('주소 일치와 좌표 준비 완료를 구분하고 정제를 자동 실행하지 않는다', async () => {
  upload.mockResolvedValue(report)
  show(true)
  select()
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
  await screen.findByRole('region', { name: '좌표 적재 결과' })
  expect(screen.getByRole('region', { name: '좌표 적재 결과' })).toHaveTextContent('완료')
  expect(screen.getByRole('complementary', { name: '다음 작업 안내' })).toHaveTextContent('100개')
  expect(screen.getByText('원천의 고유 주소').nextSibling).toHaveTextContent('3,500개')
  expect(screen.getByText('ZIP 주소 일치').nextSibling).toHaveTextContent('3,400개')
  expect(screen.getByText('ZIP 좌표 준비 완료').nextSibling).toHaveTextContent('3,300개')
  expect(screen.getByText('ZIP 주소 불일치').nextSibling).toHaveTextContent('100개')
  expect(screen.queryByText('저장한 좌표 행')).not.toBeInTheDocument()
  expect(screen.getByRole('complementary', { name: '다음 작업 안내' })).toHaveTextContent('주소 검색 또는 직접 입력')
  expect(screen.queryByRole('link', { name: /단지 정제/ })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /정제/ })).not.toBeInTheDocument()
  expect(startDataPipeline).not.toHaveBeenCalled()
})
it('독립 주소 업로드 화면은 지원을 종료한 단지 정제로 안내하지 않는다', () => {
  show()
  expect(screen.queryByRole('link', { name: /단지 정제/ })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /정제/ })).not.toBeInTheDocument()
})
it('업로드 실패는 빈 성공과 구별하고 같은 파일로 재시도할 수 있다', async () => {
  upload.mockRejectedValueOnce(new Error('전국 월전체분이 아닙니다.')).mockResolvedValue(report)
  show()
  select()
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('전국 월전체분이 아닙니다.')
  expect(screen.queryByRole('region', { name: '좌표 적재 결과' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
  await screen.findByRole('region', { name: '좌표 적재 결과' })
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})
it('공통 실행 잠금이 있으면 ZIP을 선택하거나 업로드할 수 없다', () => {
  show(true, true)
  expect(screen.getByLabelText('위치정보요약DB 월 전체분 ZIP')).toBeDisabled()
  expect(screen.getByRole('button', { name: '좌표 데이터 업로드' })).toBeDisabled()
})

it('새 파일을 선택하면 이전 결과·다음 작업을 비우고 모두 일치한 결과에는 다음 작업 안내를 표시하지 않는다', async () => {
  upload.mockResolvedValueOnce(report).mockResolvedValueOnce({ ...report, matchedRoadAddressCount: 3500, unmatchedRoadAddressCount: 0 })
  show(true)
  select()
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
  await screen.findByRole('complementary', { name: '다음 작업 안내' })
  select()
  expect(screen.queryByRole('region', { name: '좌표 적재 결과' })).not.toBeInTheDocument()
  expect(screen.queryByRole('complementary', { name: '다음 작업 안내' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
  await screen.findByRole('region', { name: '좌표 적재 결과' })
  expect(screen.getByText('ZIP 주소 불일치').nextSibling).toHaveTextContent('0개')
  expect(screen.queryByRole('complementary', { name: '다음 작업 안내' })).not.toBeInTheDocument()
  expect(startDataPipeline).not.toHaveBeenCalled()
})
