import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DataPipelineExecution, LocationSummaryImportReport } from './api'
import { LocationSummaryUpload } from './LocationSummaryUpload'

const apiMocks = vi.hoisted(() => ({
  uploadLocationSummary: vi.fn(),
  getDataPipelineStatus: vi.fn(),
  getDataPipelineExecution: vi.fn(),
  startDataPipeline: vi.fn(),
  stopDataPipeline: vi.fn(),
}))

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  ...apiMocks,
}))

beforeEach(() => {
  Object.values(apiMocks).forEach(mock => mock.mockReset())
  apiMocks.getDataPipelineStatus.mockResolvedValue(execution('IDLE'))
  apiMocks.getDataPipelineExecution.mockResolvedValue(execution('RUNNING'))
})

describe('LocationSummaryUpload', () => {
  it('ZIP을 선택해야 업로드할 수 있고 처리 중에는 입력을 잠근다', async () => {
    const response = deferred<LocationSummaryImportReport>()
    apiMocks.uploadLocationSummary.mockReturnValue(response.promise)
    await show()
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
    await show()

    selectAndUpload()

    expect(await screen.findByText('summary.zip 적재를 완료했습니다.')).toBeVisible()
    expect(screen.getByText('확인한 원천 행').nextSibling).toHaveTextContent('6,422,078건')
    expect(screen.getByText('저장한 좌표 행').nextSibling).toHaveTextContent('3,600건')
    expect(screen.getByText(/일치하지 않은 주소는 단지 정제 결과/)).toBeVisible()
    expect(screen.getByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).toBeEnabled()
    expect(screen.getByText(/현재 저장된 전체 단지 원천을 정제/)).toBeVisible()
    expect(apiMocks.startDataPipeline).not.toHaveBeenCalled()
  })

  it('업로드 실패 사유를 표시하고 같은 파일로 다시 시도할 수 있다', async () => {
    apiMocks.uploadLocationSummary.mockRejectedValue(new Error('전국 월전체분이 아닙니다.'))
    await show()

    selectAndUpload()

    expect(await screen.findByRole('alert')).toHaveTextContent('전국 월전체분이 아닙니다.')
    expect(screen.getByRole('button', { name: '좌표 데이터 업로드' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).not.toBeInTheDocument()
    expect(apiMocks.startDataPipeline).not.toHaveBeenCalled()
  })

  it('업로드 성공 후 명시적으로 정제하고 결과를 보존하며 중복 시작을 막는다', async () => {
    apiMocks.uploadLocationSummary.mockResolvedValue(report())
    const response = deferred<DataPipelineExecution>()
    apiMocks.startDataPipeline.mockReturnValue(response.promise)
    await show()
    selectAndUpload()
    const start = await screen.findByRole('button', { name: '저장된 원천으로 단지 정제 실행' })
    fireEvent.click(start)
    fireEvent.click(start)

    await waitFor(() => expect(apiMocks.startDataPipeline).toHaveBeenCalledExactlyOnceWith('COMPLEX_REFINEMENT'))
    expect(screen.getByRole('button', { name: '단지 정제 시작 중…' })).toBeDisabled()
    expect(screen.getByLabelText('월 전체분 ZIP')).toBeDisabled()
    expect(screen.getByText('summary.zip 적재를 완료했습니다.')).toBeVisible()
    await act(async () => response.resolve(execution('COMPLETED')))
    expect(screen.getByRole('article', { name: '현재 작업 단계' })).toHaveTextContent('완료')
    expect(screen.getByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).toBeEnabled()
  })

  it('정제 시작 실패에도 업로드 결과를 남기고 실행 중이 아니면 다시 시도할 수 있다', async () => {
    apiMocks.uploadLocationSummary.mockResolvedValue(report())
    apiMocks.startDataPipeline.mockRejectedValue(new Error('다른 수집 작업이 실행 중입니다.'))
    await show()
    selectAndUpload()
    fireEvent.click(await screen.findByRole('button', { name: '저장된 원천으로 단지 정제 실행' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('다른 수집 작업이 실행 중입니다.')
    await waitFor(() => expect(screen.getByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).toBeEnabled())
    expect(screen.getByText('summary.zip 적재를 완료했습니다.')).toBeVisible()
    expect(apiMocks.getDataPipelineStatus).toHaveBeenCalledTimes(3)
  })

  it('정제 실행 실패는 완료로 표시하지 않고 같은 좌표로 다시 정제할 수 있다', async () => {
    apiMocks.uploadLocationSummary.mockResolvedValue(report())
    apiMocks.startDataPipeline.mockResolvedValue({
      ...execution('FAILED'),
      failure: { stepName: '마이홈 단지 정제', message: '단지 정제 실패', serverResponse: null },
    })
    await show()
    selectAndUpload()
    fireEvent.click(await screen.findByRole('button', { name: '저장된 원천으로 단지 정제 실행' }))

    const result = await screen.findByRole('article', { name: '현재 작업 단계' })
    expect(within(result).getByRole('alert')).toHaveTextContent('단지 정제 실패')
    expect(within(result).queryByText('완료')).not.toBeInTheDocument()
    expect(screen.getByText('summary.zip 적재를 완료했습니다.')).toBeVisible()
    expect(screen.getByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).toBeEnabled()
    expect(apiMocks.uploadLocationSummary).toHaveBeenCalledTimes(1)
  })

  it('시작 응답 유실 후 서버에서 실행 중인 정제를 복원하고 중지할 수 있다', async () => {
    apiMocks.uploadLocationSummary.mockResolvedValue(report())
    apiMocks.startDataPipeline.mockRejectedValue(new Error('응답 연결이 끊겼습니다.'))
    apiMocks.getDataPipelineStatus.mockResolvedValueOnce(execution('IDLE')).mockResolvedValueOnce(execution('IDLE'))
      .mockResolvedValue(execution('RUNNING'))
    apiMocks.stopDataPipeline.mockResolvedValue(execution('STOPPED'))
    await show()
    selectAndUpload()
    fireEvent.click(await screen.findByRole('button', { name: '저장된 원천으로 단지 정제 실행' }))

    const stop = await screen.findByRole('button', { name: '단지 정제 실행 중지' })
    expect(screen.getByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).toBeDisabled()
    fireEvent.click(stop)
    await waitFor(() => expect(screen.getByRole('article', { name: '현재 작업 단계' })).toHaveTextContent('중지됨'))
    expect(apiMocks.stopDataPipeline).toHaveBeenCalledWith('refinement-1')
    expect(screen.getByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).toBeEnabled()
  })

  it.each([
    ['COMPLETED', '완료'], ['FAILED', '실패'], ['STOPPED', '중지됨'],
  ] as const)('과거 결과는 숨기고 시작 응답 유실 후 새 정제의 %s 결과를 복원한다', async (status, label) => {
    apiMocks.uploadLocationSummary.mockResolvedValue(report())
    apiMocks.startDataPipeline.mockRejectedValue(new Error('응답 연결이 끊겼습니다.'))
    apiMocks.getDataPipelineStatus.mockResolvedValueOnce(execution('COMPLETED'))
      .mockResolvedValueOnce(execution('COMPLETED'))
      .mockResolvedValue({
        ...execution(status), executionId: 'refinement-2',
        failure: status === 'FAILED'
          ? { stepName: '마이홈 단지 정제', message: '일부 단지 정제 실패', serverResponse: null } : null,
      })
    await show()
    expect(screen.queryByRole('article', { name: '현재 작업 단계' })).not.toBeInTheDocument()
    selectAndUpload()
    fireEvent.click(await screen.findByRole('button', { name: '저장된 원천으로 단지 정제 실행' }))

    const result = await screen.findByRole('article', { name: '현재 작업 단계' })
    expect(result).toHaveTextContent(label)
    expect(screen.queryByText('응답 연결이 끊겼습니다.')).not.toBeInTheDocument()
    if (status === 'FAILED') expect(within(result).getByRole('alert')).toHaveTextContent('일부 단지 정제 실패')
    expect(screen.getByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).toBeEnabled()
    expect(apiMocks.startDataPipeline).toHaveBeenCalledTimes(1)
  })

  it('시작 직전에 확인한 과거 완료 기록을 이번 요청의 성공으로 표시하지 않는다', async () => {
    apiMocks.uploadLocationSummary.mockResolvedValue(report())
    apiMocks.startDataPipeline.mockRejectedValue(new Error('요청을 보내지 못했습니다.'))
    apiMocks.getDataPipelineStatus.mockResolvedValueOnce(execution('IDLE')).mockResolvedValue(execution('COMPLETED'))
    await show()
    selectAndUpload()
    fireEvent.click(await screen.findByRole('button', { name: '저장된 원천으로 단지 정제 실행' }))

    await waitFor(() => expect(apiMocks.getDataPipelineStatus).toHaveBeenCalledTimes(3))
    expect(await screen.findByRole('alert')).toHaveTextContent('요청을 보내지 못했습니다.')
    expect(screen.queryByRole('article', { name: '현재 작업 단계' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).toBeEnabled()
  })

  it('시작 직전 다른 정제가 실행 중이면 해당 작업을 표시하고 추가로 시작하지 않는다', async () => {
    apiMocks.uploadLocationSummary.mockResolvedValue(report())
    apiMocks.getDataPipelineStatus.mockResolvedValueOnce(execution('IDLE')).mockResolvedValue(execution('RUNNING'))
    await show()
    selectAndUpload()
    fireEvent.click(await screen.findByRole('button', { name: '저장된 원천으로 단지 정제 실행' }))

    await screen.findByRole('button', { name: '단지 정제 실행 중지' })
    expect(apiMocks.startDataPipeline).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).toBeDisabled()
  })

  it('시작 직전 상태 조회가 실패하면 정제를 보내지 않고 재확인할 수 있다', async () => {
    apiMocks.uploadLocationSummary.mockResolvedValue(report())
    apiMocks.getDataPipelineStatus.mockResolvedValueOnce(execution('IDLE')).mockRejectedValueOnce(new Error('상태 조회 실패'))
      .mockResolvedValue(execution('IDLE'))
    await show()
    selectAndUpload()
    fireEvent.click(await screen.findByRole('button', { name: '저장된 원천으로 단지 정제 실행' }))

    fireEvent.click(await screen.findByRole('button', { name: '단지 정제 상태 다시 확인' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).toBeEnabled())
    expect(apiMocks.startDataPipeline).not.toHaveBeenCalled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('복구 조회 실패 뒤 수동 재확인에서도 새 정제의 완료 결과를 보존한다', async () => {
    apiMocks.uploadLocationSummary.mockResolvedValue(report())
    apiMocks.startDataPipeline.mockRejectedValue(new Error('응답 연결이 끊겼습니다.'))
    apiMocks.getDataPipelineStatus.mockResolvedValueOnce(execution('COMPLETED'))
      .mockResolvedValueOnce(execution('COMPLETED')).mockRejectedValueOnce(new Error('상태 조회 실패'))
      .mockResolvedValue({ ...execution('COMPLETED'), executionId: 'refinement-2' })
    await show()
    selectAndUpload()
    fireEvent.click(await screen.findByRole('button', { name: '저장된 원천으로 단지 정제 실행' }))

    fireEvent.click(await screen.findByRole('button', { name: '단지 정제 상태 다시 확인' }))
    expect(await screen.findByRole('article', { name: '현재 작업 단계' })).toHaveTextContent('완료')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(apiMocks.startDataPipeline).toHaveBeenCalledTimes(1)
  })

  it('시작과 상태 확인이 모두 실패하면 상태를 재확인할 때까지 재시작하지 않는다', async () => {
    apiMocks.uploadLocationSummary.mockResolvedValue(report())
    apiMocks.startDataPipeline.mockRejectedValue(new Error('응답 연결이 끊겼습니다.'))
    apiMocks.getDataPipelineStatus.mockResolvedValueOnce(execution('IDLE')).mockResolvedValueOnce(execution('IDLE'))
      .mockRejectedValueOnce(new Error('상태 조회 실패'))
      .mockResolvedValue(execution('RUNNING'))
    await show()
    selectAndUpload()
    fireEvent.click(await screen.findByRole('button', { name: '저장된 원천으로 단지 정제 실행' }))

    expect(await screen.findByRole('button', { name: '단지 정제 상태 다시 확인' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '저장된 원천으로 단지 정제 실행' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '단지 정제 상태 다시 확인' }))
    await screen.findByRole('button', { name: '단지 정제 실행 중지' })
    expect(apiMocks.startDataPipeline).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('진행 중인 정제를 복원하고 일시적인 상태 조회 실패 뒤 완료를 확인한다', async () => {
    vi.useFakeTimers()
    try {
      apiMocks.getDataPipelineStatus.mockResolvedValue(execution('RUNNING'))
      apiMocks.getDataPipelineExecution.mockRejectedValueOnce(new Error('일시적인 조회 실패'))
        .mockResolvedValue(execution('COMPLETED'))
      await show()
      expect(screen.getByLabelText('월 전체분 ZIP')).toBeDisabled()
      await act(async () => vi.advanceTimersByTimeAsync(1000))
      expect(screen.getByRole('alert')).toHaveTextContent('상태를 다시 확인하고 있습니다.')
      expect(within(screen.getByRole('article', { name: '현재 작업 단계' })).getByText('실행 중')).toBeVisible()
      await act(async () => vi.advanceTimersByTimeAsync(1000))
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.getByRole('article', { name: '현재 작업 단계' })).toHaveTextContent('완료')
      expect(screen.getByLabelText('월 전체분 ZIP')).toBeEnabled()
      expect(apiMocks.getDataPipelineExecution).toHaveBeenCalledWith('refinement-1')
    } finally {
      vi.useRealTimers()
    }
  })
})

async function show() {
  render(<MemoryRouter><LocationSummaryUpload /></MemoryRouter>)
  await act(async () => {})
}

function execution(status: DataPipelineExecution['status']): DataPipelineExecution {
  return {
    type: 'COMPLEX_REFINEMENT', status,
    executionId: status === 'IDLE' ? null : 'refinement-1',
    currentStepName: status === 'RUNNING' ? '마이홈 단지 정제' : null,
    currentStepIndex: status === 'COMPLETED' ? 2 : 1,
    totalStepCount: 2, completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null,
  }
}

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
