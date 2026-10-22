import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { LocationSummaryUpload } from './LocationSummaryUpload'
import { uploadLocationSummary, startDataPipeline, type LocationSummaryImportReport } from './api'

vi.mock('./api', () => ({ uploadLocationSummary: vi.fn(), startDataPipeline: vi.fn() }))
const upload = vi.mocked(uploadLocationSummary)
beforeEach(() => vi.resetAllMocks())
const report: LocationSummaryImportReport = {
  sourceFileName: 'summary.zip', textFileCount: 16, scannedRowCount: 6422078,
  targetRoadAddressCount: 3500, matchedRoadAddressCount: 3400, unmatchedRoadAddressCount: 100,
  storedLocationCount: 3600, replacedRowCount: 12, invalidatedMappingCandidateCount: 0, provinceCodes: ['11'],
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
it('좌표 행 수와 구별한 주소 수 세 개만 보여 주며 정제를 자동 실행하지 않는다', async () => {
  upload.mockResolvedValue(report)
  show(true)
  select()
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
  await screen.findByText('좌표 적재 완료')
  expect(screen.getByText('좌표를 찾을 주소').nextSibling).toHaveTextContent('3,500개')
  expect(screen.getByText('좌표를 찾은 주소').nextSibling).toHaveTextContent('3,400개')
  expect(screen.getByText('좌표를 찾지 못한 주소').nextSibling).toHaveTextContent('100개')
  expect(screen.queryByText('저장한 좌표 행')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '아래 3단계에서 단지 정제·저장' })).toHaveAttribute('href', '#complex-refinement')
  expect(screen.getByText(/결과의 ‘좌표 문제’/)).toBeVisible()
  expect(screen.queryByRole('button', { name: /정제/ })).not.toBeInTheDocument()
  expect(startDataPipeline).not.toHaveBeenCalled()
})
it('독립 주소 업로드 화면도 정제 버튼 없이 단지 수집 3단계로 안내한다', () => {
  show()
  expect(screen.getByRole('link', { name: '단지 수집 3단계에서 단지 정제·저장' })).toHaveAttribute('href', '/admin/ingest/complexes#complex-refinement')
  expect(screen.queryByRole('button', { name: /정제/ })).not.toBeInTheDocument()
})
it('업로드 실패는 빈 성공과 구별하고 같은 파일로 재시도할 수 있다', async () => {
  upload.mockRejectedValueOnce(new Error('전국 월전체분이 아닙니다.')).mockResolvedValue(report)
  show()
  select()
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('전국 월전체분이 아닙니다.')
  expect(screen.queryByText('좌표 적재 완료')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
  await screen.findByText('좌표 적재 완료')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})
it('공통 실행 잠금이 있으면 ZIP을 선택하거나 업로드할 수 없다', () => {
  show(true, true)
  expect(screen.getByLabelText('위치정보요약DB 월 전체분 ZIP')).toBeDisabled()
  expect(screen.getByRole('button', { name: '좌표 데이터 업로드' })).toBeDisabled()
})
