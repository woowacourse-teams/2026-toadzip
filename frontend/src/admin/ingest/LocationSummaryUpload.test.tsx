import { act, fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LocationSummaryImportReport } from './api'
import { LocationSummaryUpload } from './LocationSummaryUpload'

const apiMocks = vi.hoisted(() => ({
  uploadLocationSummary: vi.fn(),
}))

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  ...apiMocks,
}))

beforeEach(() => {
  apiMocks.uploadLocationSummary.mockReset()
})

describe('LocationSummaryUpload', () => {
  it('ZIP을 선택해야 업로드할 수 있고 처리 중에는 입력을 잠근다', async () => {
    const response = deferred<LocationSummaryImportReport>()
    apiMocks.uploadLocationSummary.mockReturnValue(response.promise)
    render(<LocationSummaryUpload />)
    const input = screen.getByLabelText('월 전체분 ZIP')
    const submit = screen.getByRole('button', { name: '좌표 데이터 업로드' })

    expect(submit).toBeDisabled()
    const file = new File(['zip-content'], 'summary.zip', { type: 'application/zip' })
    fireEvent.change(input, { target: { files: [file] } })
    fireEvent.click(submit)

    expect(apiMocks.uploadLocationSummary).toHaveBeenCalledWith(file)
    expect(screen.getByRole('button', { name: '업로드 및 선별 적재 중…' })).toBeDisabled()
    expect(input).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('이 페이지를 닫지 마세요')

    await act(async () => response.resolve(report()))
  })

  it('적재 결과와 일치하지 않은 주소 안내를 표시한다', async () => {
    apiMocks.uploadLocationSummary.mockResolvedValue(report())
    render(<LocationSummaryUpload />)

    selectAndUpload()

    expect(await screen.findByText('summary.zip 적재를 완료했습니다.')).toBeVisible()
    expect(screen.getByText('확인한 원천 행').nextSibling).toHaveTextContent('6,422,078건')
    expect(screen.getByText('저장한 좌표 행').nextSibling).toHaveTextContent('3,600건')
    expect(screen.getByText(/일치하지 않은 주소는 단지 정제 결과/)).toBeVisible()
  })

  it('업로드 실패 사유를 표시하고 같은 파일로 다시 시도할 수 있다', async () => {
    apiMocks.uploadLocationSummary.mockRejectedValue(new Error('전국 월전체분이 아닙니다.'))
    render(<LocationSummaryUpload />)

    selectAndUpload()

    expect(await screen.findByRole('alert')).toHaveTextContent('전국 월전체분이 아닙니다.')
    expect(screen.getByRole('button', { name: '좌표 데이터 업로드' })).toBeEnabled()
  })
})

function selectAndUpload() {
  const file = new File(['zip-content'], 'summary.zip', { type: 'application/zip' })
  fireEvent.change(screen.getByLabelText('월 전체분 ZIP'), { target: { files: [file] } })
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
}

function report(): LocationSummaryImportReport {
  return {
    sourceFileName: 'summary.zip',
    textFileCount: 16,
    scannedRowCount: 6_422_078,
    targetRoadAddressCount: 3_500,
    matchedRoadAddressCount: 3_420,
    unmatchedRoadAddressCount: 80,
    storedLocationCount: 3_600,
    replacedRowCount: 0,
    invalidatedMappingCandidateCount: 0,
    provinceCodes: ['11', '26'],
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve
  })
  return { promise, resolve }
}
