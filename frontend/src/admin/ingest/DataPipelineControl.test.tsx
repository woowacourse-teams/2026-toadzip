import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DataPipelineExecution, DataPipelineType } from './api'
import { DataPipelineControl } from './DataPipelineControl'

const apiMocks = vi.hoisted(() => ({
  getDataPipelineStatus: vi.fn(),
  startDataPipeline: vi.fn(),
}))

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  ...apiMocks,
}))

beforeEach(() => {
  apiMocks.getDataPipelineStatus.mockReset()
  apiMocks.startDataPipeline.mockReset()
  apiMocks.getDataPipelineStatus.mockImplementation(
    (type: DataPipelineType) => Promise.resolve(execution(type, 'IDLE')),
  )
})

describe('DataPipelineControl', () => {
  it('단지 수집 실행 중 네 버튼을 잠그고 현재 단계를 표시한다', async () => {
    apiMocks.startDataPipeline.mockResolvedValue(execution('COMPLEX_COLLECTION', 'RUNNING', {
      currentStepName: '마이홈 단지 수집',
      currentStepIndex: 1,
    }))
    render(<DataPipelineControl />)

    fireEvent.click(screen.getByRole('button', { name: '단지 수집' }))

    expect(await screen.findByRole('button', { name: '단지 수집 실행 중…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '단지 정제' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '공고 수집' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '공고 정제' })).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('1/2 · 마이홈 단지 수집 실행 중')
  })

  it('완료된 단계와 다음 현재 단계를 표시한다', async () => {
    apiMocks.startDataPipeline.mockResolvedValue(execution('COMPLEX_COLLECTION', 'RUNNING', {
      currentStepName: 'LH 임대 카탈로그 수집',
      currentStepIndex: 2,
      completedSteps: ['마이홈 단지 수집'],
    }))
    render(<DataPipelineControl />)

    fireEvent.click(screen.getByRole('button', { name: '단지 수집' }))

    expect(await screen.findByText('마이홈 단지 수집 완료')).toBeVisible()
    expect(screen.getByRole('status')).toHaveTextContent('2/2 · LH 임대 카탈로그 수집 실행 중')
  })

  it('실패 단계와 원인 및 서버 응답을 표시한다', async () => {
    apiMocks.startDataPipeline.mockResolvedValue(execution('ANNOUNCEMENT_REFINEMENT', 'FAILED', {
      currentStepName: '마이홈 공고 정제',
      currentStepIndex: 1,
      failure: {
        stepName: '마이홈 공고 정제',
        message: '마이홈 공고 정제 단계가 일부 실패했습니다.',
        serverResponse: { failedSourceRowCount: 3 },
      },
    }))
    render(<DataPipelineControl />)

    fireEvent.click(screen.getByRole('button', { name: '공고 정제' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '마이홈 공고 정제 단계가 일부 실패했습니다.',
    )
    expect(screen.getByLabelText('서버 응답')).toHaveTextContent('"failedSourceRowCount": 3')
    expect(screen.getByRole('button', { name: '공고 정제' })).toBeEnabled()
  })

  it('화면을 다시 열어도 서버에서 실행 중인 상태를 복구한다', async () => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => {
      if (type === 'ANNOUNCEMENT_COLLECTION') {
        return Promise.resolve(execution(type, 'RUNNING', {
          currentStepName: '마이홈 공고 수집',
          currentStepIndex: 1,
        }))
      }
      return Promise.resolve(execution(type, 'IDLE'))
    })
    render(<DataPipelineControl />)

    expect(await screen.findByRole('status')).toHaveTextContent('1/3 · 마이홈 공고 수집 실행 중')
    expect(screen.getByRole('button', { name: '공고 수집 실행 중…' })).toBeDisabled()
  })

  it('늦게 도착한 최초 상태 조회가 새 실행 상태를 덮어쓰지 않는다', async () => {
    const staleStatus = deferred<DataPipelineExecution>()
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => {
      if (type === 'COMPLEX_COLLECTION') {
        return staleStatus.promise
      }
      return Promise.resolve(execution(type, 'IDLE'))
    })
    apiMocks.startDataPipeline.mockResolvedValue(execution('COMPLEX_COLLECTION', 'RUNNING', {
      currentStepName: '마이홈 단지 수집',
      currentStepIndex: 1,
    }))
    render(<DataPipelineControl />)

    fireEvent.click(screen.getByRole('button', { name: '단지 수집' }))
    expect(await screen.findByRole('status')).toHaveTextContent('마이홈 단지 수집 실행 중')

    await act(async () => staleStatus.resolve(execution('COMPLEX_COLLECTION', 'IDLE')))

    expect(screen.getByRole('status')).toHaveTextContent('마이홈 단지 수집 실행 중')
  })

  it('시작 응답과 상태 조회를 모두 잃으면 실행 잠금을 유지하며 재조회한다', async () => {
    render(<DataPipelineControl />)
    await waitFor(() => expect(apiMocks.getDataPipelineStatus).toHaveBeenCalledTimes(4))
    apiMocks.startDataPipeline.mockRejectedValue(new Error('네트워크 연결이 끊겼습니다.'))
    apiMocks.getDataPipelineStatus.mockRejectedValue(new Error('상태를 조회하지 못했습니다.'))

    fireEvent.click(screen.getByRole('button', { name: '단지 수집' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('네트워크 연결이 끊겼습니다.')
    expect(screen.getByRole('button', { name: '단지 수집 실행 중…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '공고 정제' })).toBeDisabled()
  })

  it('완료 응답을 받으면 버튼을 다시 활성화한다', async () => {
    apiMocks.startDataPipeline.mockResolvedValue(execution('COMPLEX_COLLECTION', 'COMPLETED', {
      completedSteps: [
        '마이홈 단지 수집',
        'LH 임대 카탈로그 수집',
      ],
    }))
    render(<DataPipelineControl />)

    fireEvent.click(screen.getByRole('button', { name: '단지 수집' }))

    await waitFor(() => expect(screen.getByRole('button', { name: '단지 수집' })).toBeEnabled())
    expect(screen.getByRole('status')).toHaveTextContent('단지 수집 작업을 완료했습니다.')
  })

  it('호출 제한으로 건너뛴 단계와 응답을 부분 완료로 표시한다', async () => {
    apiMocks.startDataPipeline.mockResolvedValue(execution(
      'ANNOUNCEMENT_COLLECTION',
      'COMPLETED_WITH_SKIPS',
      {
        completedSteps: ['LH 공고 공급 원본 수집', 'LH 공고 상세 원본 수집'],
        skippedSteps: [{
          stepName: '마이홈 공고 수집',
          reason: '외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.',
          serverResponse: { failedRequestCount: 1, rateLimitedRequestCount: 1 },
        }],
      },
    ))
    render(<DataPipelineControl />)

    fireEvent.click(screen.getByRole('button', { name: '공고 수집' }))

    expect(await screen.findByText(
      '공고 수집 작업을 일부 단계 건너뜀으로 완료했습니다.',
    )).toBeVisible()
    expect(screen.getByText('마이홈 공고 수집 건너뜀')).toBeVisible()
    expect(screen.getByLabelText('마이홈 공고 수집 건너뜀 응답')).toHaveTextContent(
      '"rateLimitedRequestCount": 1',
    )
    expect(screen.getByRole('button', { name: '공고 수집' })).toBeEnabled()
  })
})

function execution(
  type: DataPipelineType,
  status: DataPipelineExecution['status'],
  overrides: Partial<DataPipelineExecution> = {},
): DataPipelineExecution {
  return {
    executionId: status === 'IDLE' ? null : '01991a11-65d2-7000-8000-000000000001',
    type,
    status,
    currentStepName: null,
    currentStepIndex: 0,
    totalStepCount: stepCount(type),
    completedSteps: [],
    skippedSteps: [],
    failure: null,
    ...overrides,
  }
}

function stepCount(type: DataPipelineType): number {
  return type === 'ANNOUNCEMENT_COLLECTION' ? 3 : 2
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolvePromise: (value: T) => void
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve
  })
  return { promise, resolve: resolvePromise! }
}
