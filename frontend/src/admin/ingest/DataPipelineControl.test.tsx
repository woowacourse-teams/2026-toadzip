import { MemoryRouter } from 'react-router'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DataPipelineExecution, DataPipelineType } from './api'
import { DataPipelineControl } from './DataPipelineControl'

const apiMocks = vi.hoisted(() => ({
  getDataPipelineStatus: vi.fn(),
  startDataPipeline: vi.fn(),
  stopDataPipeline: vi.fn(),
  getLhAnnouncementQuality: vi.fn(),
}))

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  ...apiMocks,
}))

beforeEach(() => {
  apiMocks.getDataPipelineStatus.mockReset()
  apiMocks.startDataPipeline.mockReset()
  apiMocks.stopDataPipeline.mockReset()
  apiMocks.getLhAnnouncementQuality.mockReset()
  apiMocks.getLhAnnouncementQuality.mockResolvedValue({
    observedAt: '2026-09-28T00:00:00Z',
    connection: { total: 0, complexLinked: 0, housingTypeLinked: 0, unlinkedReasons: {} },
    amounts: { total: 0, fulfilled: 0 },
    schedules: { total: 0, reviewed: 0, withApplicationSchedule: 0 },
    supplyCollection: { totalRequests: 0, freshRequests: 0, latestCollectedAt: null },
    detailCollection: { totalRequests: 0, freshRequests: 0, latestCollectedAt: null },
    unlinkedLhLeaseCatalogCount: 0,
    unlinkedLhCandidates: [],
    preservedSourceRequestCount: 0,
    preservedReasons: {},
    preservedAmountTargetCount: 0,
    preservedAmountReasons: {},
    heldRequests: [],
  })
  apiMocks.getDataPipelineStatus.mockImplementation(
    (type: DataPipelineType) => Promise.resolve(execution(type, 'IDLE')),
  )
})

describe('DataPipelineControl', () => {
  it('중지를 요청한 뒤 실제 종료 응답이 오기 전까지 새 실행을 막는다', async () => {
    const running = execution('COMPLEX_COLLECTION', 'RUNNING', { executionId: 'run-1' })
    apiMocks.startDataPipeline.mockResolvedValue(running)
    apiMocks.stopDataPipeline.mockResolvedValue({ ...running, stopRequested: true })
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: '단지 수집' }))
    const stop = await screen.findByRole('button', { name: '단지 수집 실행 중지' })
    fireEvent.click(stop)
    expect(await screen.findByText(/중지 요청됨/)).toBeVisible()
    expect(apiMocks.stopDataPipeline).toHaveBeenCalledWith('run-1')
    expect(screen.getByRole('button', { name: '중지 요청 중…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '공고 수집' })).toBeDisabled()
  })

  it('중지 API가 실패하면 완료로 표시하지 않고 다시 중지할 수 있다', async () => {
    apiMocks.startDataPipeline.mockResolvedValue(execution('COMPLEX_COLLECTION', 'RUNNING', { executionId: 'run-1' }))
    apiMocks.stopDataPipeline.mockRejectedValue(new Error('중지 요청 실패'))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: '단지 수집' }))
    fireEvent.click(await screen.findByRole('button', { name: '단지 수집 실행 중지' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('중지 요청 실패')
    expect(screen.getByRole('button', { name: '단지 수집 실행 중지' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '공고 수집' })).toBeDisabled()
  })

  it('재접속 시 중지된 실행과 완료한 단계를 표시한다', async () => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'COMPLEX_COLLECTION' ? execution(type, 'STOPPED', { completedSteps: ['마이홈 단지 수집'] }) : execution(type, 'IDLE'),
    ))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)
    expect(await screen.findByRole('status')).toHaveTextContent('실행이 중지되었습니다')
    fireEvent.click(screen.getByText('마이홈 단지 수집 완료').closest('details')!.querySelector('summary')!)
    expect(screen.getByText('마이홈 단지 수집 완료')).toBeVisible()
    expect(screen.getByRole('button', { name: '공고 수집' })).toBeEnabled()
  })

  it('단지 수집 실행 중 시작 버튼을 잠그고 현재 단계를 표시한다', async () => {
    apiMocks.startDataPipeline.mockResolvedValue(execution('COMPLEX_COLLECTION', 'RUNNING', {
      currentStepName: '마이홈 단지 수집',
      currentStepIndex: 1,
    }))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

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
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    fireEvent.click(screen.getByRole('button', { name: '단지 수집' }))

    const completed = await screen.findByText('마이홈 단지 수집 완료')
    fireEvent.click(completed.closest('details')!.querySelector('summary')!)
    expect(completed).toBeVisible()
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
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

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
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    expect(await screen.findByRole('status')).toHaveTextContent('1/4 · 마이홈 공고 수집 실행 중')
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
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    fireEvent.click(screen.getByRole('button', { name: '단지 수집' }))
    expect(await screen.findByRole('status')).toHaveTextContent('마이홈 단지 수집 실행 중')

    await act(async () => staleStatus.resolve(execution('COMPLEX_COLLECTION', 'IDLE')))

    expect(screen.getByRole('status')).toHaveTextContent('마이홈 단지 수집 실행 중')
  })

  it('시작 응답과 상태 조회를 모두 잃으면 실행 잠금을 유지하며 재조회한다', async () => {
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)
    await waitFor(() => expect(apiMocks.getDataPipelineStatus).toHaveBeenCalledTimes(6))
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
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    fireEvent.click(screen.getByRole('button', { name: '단지 수집' }))

    await waitFor(() => expect(screen.getByRole('button', { name: '단지 수집' })).toBeEnabled())
    expect(screen.getByRole('status')).toHaveTextContent('단지 수집 작업을 완료했습니다.')
    expect(screen.getByText(/모든 단지의 최신 상태를 보장하지 않습니다/)).toBeVisible()
  })

  it('행별 누락은 완료·주의로 표시하고 보고서를 확인할 수 있다', async () => {
    apiMocks.startDataPipeline.mockResolvedValue(execution(
      'COMPLEX_REFINEMENT',
      'COMPLETED_WARNINGS',
      {
        completedSteps: ['마이홈 단지 정제', 'LH 세대수 보강'],
        partiallyFailedSteps: [{
          step: 'MAP_MYHOME_COMPLEXES',
          stepName: '마이홈 단지 정제',
          report: { failedSourceRowCount: 3 },
        }],
      },
    ))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    fireEvent.click(screen.getByRole('button', { name: '단지 정제' }))

    expect(await screen.findByText(
      '단지 정제 작업을 완료했습니다. 처리되지 않은 원천 행이 있어 확인이 필요합니다.',
    )).toHaveAttribute('role', 'status')
    fireEvent.click(screen.getByText('마이홈 단지 정제 원천 행 확인').closest('details')!.querySelector('summary')!)
    expect(screen.getByText('마이홈 단지 정제 원천 행 확인')).toBeVisible()
    expect(screen.getByLabelText('마이홈 단지 정제 누락 보고서')).toHaveTextContent(
      '"failedSourceRowCount": 3',
    )
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '단지 정제' })).toBeEnabled()
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
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    fireEvent.click(screen.getByRole('button', { name: '공고 수집' }))

    expect(await screen.findByText(
      '공고 수집 작업을 일부 단계 건너뜀으로 완료했습니다.',
    )).toBeVisible()
    fireEvent.click(screen.getByText('마이홈 공고 수집 건너뜀').closest('details')!.querySelector('summary')!)
    expect(screen.getByText('마이홈 공고 수집 건너뜀')).toBeVisible()
    expect(screen.getByLabelText('마이홈 공고 수집 건너뜀 응답')).toHaveTextContent(
      '"rateLimitedRequestCount": 1',
    )
    expect(screen.getByRole('button', { name: '공고 수집' })).toBeEnabled()
    expect(screen.getByRole('group', { name: '공고 수집 결과 요약' })).toHaveTextContent(/실패 요청\s*1/)
  })

  it('완료된 수집의 호출·저장·생략·실패 수치를 단계 보고서에서 합산해 보여준다', async () => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'ANNOUNCEMENT_COLLECTION' ? execution(type, 'COMPLETED', {
        completedStepResults: [
          { step: 'COLLECT_LH_ANNOUNCEMENT_SUPPLIES', stepName: 'LH 공급 수집', report: {
            externalApiCallCount: 2, storedRowCount: 4, skippedRequestCount: 3, failedRequestCount: 0,
          } },
          { step: 'COLLECT_LH_ANNOUNCEMENT_DETAILS', stepName: 'LH 상세 수집', report: {
            externalApiCallCount: 3, storedRowCount: 2, skippedRequestCount: 4, failedRequestCount: 0,
          } },
        ],
      }) : execution(type, 'IDLE'),
    ))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    const result = await screen.findByRole('group', { name: '공고 수집 결과 요약' })
    expect(result).toHaveTextContent(/API 호출\s*5/)
    expect(result).toHaveTextContent(/저장한 행\s*6/)
    expect(result).toHaveTextContent(/건너뛴 요청\s*7/)
    expect(result).toHaveTextContent(/실패 요청\s*0/)
    expect(screen.getByText(/선택된 수집 대상을 처리한 결과/)).toBeVisible()
  })

  it.each([
    ['FAILED', '실패'],
    ['STOPPED', '중지됨'],
  ] as const)('최근 단지 수집이 %s여도 저장된 원천을 다시 정제할 수 있다', async (status, label) => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'COMPLEX_COLLECTION' ? execution(type, status, {
        startedAt: '2026-09-30T00:00:00Z',
      }) : execution(type, 'IDLE'),
    ))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    const recentCollection = await screen.findByText(new RegExp(`최근 수집 포함 실행: ${label}`))
    expect(recentCollection).toBeVisible()
    expect(recentCollection).toHaveTextContent('정제는 저장된 원천을 사용합니다.')
    expect(screen.getByRole('button', { name: '단지 정제' })).toBeEnabled()
  })
  it('통합 실행 버튼은 서버에 한 번 요청하고 모든 시작 버튼을 잠근다', async () => {
    apiMocks.startDataPipeline.mockResolvedValue(execution('ANNOUNCEMENT_SYNC', 'RUNNING', {
      currentStepName: '마이홈 공고 정제', currentStepIndex: 5,
    }))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    fireEvent.click(screen.getByRole('button', { name: '공고 수집·정제' }))

    expect(await screen.findByRole('status')).toHaveTextContent('5/6 · 마이홈 공고 정제 실행 중')
    expect(apiMocks.startDataPipeline).toHaveBeenCalledExactlyOnceWith('ANNOUNCEMENT_SYNC')
    expect(screen.getByRole('button', { name: '단지 수집·정제' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '공고 정제' })).toBeDisabled()
  })

  it.each(['FAILED', 'COMPLETED_WITH_SKIPS', 'STOPPED'] as const)(
    '통합 수집이 %s로 끝나면 자동 정제 없이 저장된 원천으로 정제를 직접 실행할 수 있다',
    async (status) => {
      apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
        execution(type, type === 'ANNOUNCEMENT_SYNC' ? status : 'IDLE', {
          startedAt: type === 'ANNOUNCEMENT_SYNC' ? '2026-09-30T02:00:00Z' : null,
        }),
      ))
      apiMocks.startDataPipeline.mockResolvedValue(execution('ANNOUNCEMENT_REFINEMENT', 'RUNNING'))
      render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

      const recover = await screen.findByRole('button', { name: '저장된 원천으로 공고 정제 실행' })
      expect(screen.getByText(/자동 정제가 실행되지 않았습니다/)).toBeVisible()
      expect(apiMocks.startDataPipeline).not.toHaveBeenCalled()
      fireEvent.click(recover)

      await waitFor(() => expect(apiMocks.startDataPipeline).toHaveBeenCalledExactlyOnceWith('ANNOUNCEMENT_REFINEMENT'))
      expect(recover).toBeDisabled()
      expect(await screen.findByRole('button', { name: '공고 정제 실행 중지' })).toBeVisible()
    },
  )

  it('통합 실행 재접속은 최신 진행을 복원하고 기존 실행 ID로 중지한다', async () => {
    const running = execution('COMPLEX_SYNC', 'RUNNING', { executionId: 'sync-1', currentStepIndex: 3, currentStepName: '마이홈 단지 정제' })
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'COMPLEX_SYNC' ? running : execution(type, 'IDLE'),
    ))
    apiMocks.stopDataPipeline.mockResolvedValue({ ...running, stopRequested: true })
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    fireEvent.click(await screen.findByRole('button', { name: '단지 수집·정제 실행 중지' }))

    expect(apiMocks.stopDataPipeline).toHaveBeenCalledExactlyOnceWith('sync-1')
    expect(await screen.findByText(/중지 요청됨/)).toBeVisible()
    expect(screen.getByRole('button', { name: '단지 정제' })).toBeDisabled()
  })

  it('통합 실행 결과에서 수집·정제·보강의 실패를 각각 확인한다', async () => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      execution(type, type === 'ANNOUNCEMENT_SYNC' ? 'FAILED' : 'IDLE'),
    ))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    expect(await screen.findByRole('link', { name: '공고 수집·정제 수집 실패 요청 보기' })).toHaveAttribute('href', expect.stringContaining('category=collection'))
    expect(screen.getByRole('link', { name: '공고 수집·정제 정제 실패 행 보기' })).toHaveAttribute('href', expect.stringContaining('category=announcement'))
    expect(screen.getByRole('link', { name: '공고 수집·정제 보강 실패 행 보기' })).toHaveAttribute('href', expect.stringContaining('category=enrichment'))
  })

  it('이미 정제가 시작된 통합 실패를 정제 미실행으로 표시하지 않는다', async () => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'COMPLEX_SYNC' ? execution(type, 'FAILED', { currentStepIndex: 3 }) : execution(type, 'IDLE'),
    ))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)
    await waitFor(() => expect(apiMocks.getDataPipelineStatus).toHaveBeenCalledWith('COMPLEX_SYNC'))

    expect(screen.queryByText(/자동 정제가 실행되지 않았습니다/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '단지 정제' })).toBeEnabled()
  })

  it('독립 정제 옆에는 독립 수집보다 새로운 통합 실행의 수집 시각과 상태를 표시한다', async () => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'COMPLEX_SYNC' ? execution(type, 'FAILED', { startedAt: '2026-09-30T02:00:00Z' })
        : type === 'COMPLEX_COLLECTION' ? execution(type, 'COMPLETED', { startedAt: '2026-09-29T02:00:00Z' })
          : execution(type, 'IDLE'),
    ))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    expect(await screen.findByText(/최근 수집 포함 실행: 실패/)).toHaveTextContent('이전 수집의 원천이 포함될 수 있습니다.')
    expect(screen.queryByText(/최근 수집 포함 실행: 선택된 작업 완료/)).not.toBeInTheDocument()
  })

  it.each([
    ['2026-09-30T02:00:00Z', '2026-09-29T02:00:00Z', '2/3 (67%)'],
    ['2026-09-29T02:00:00Z', '2026-09-30T02:00:00Z', '4/5 (80%)'],
  ])('LH 품질 지표는 통합·단독 수집 중 최신 실행의 성공률을 표시한다 (%s, %s)', async (syncAt, collectionAt, rate) => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'ANNOUNCEMENT_SYNC' || type === 'ANNOUNCEMENT_COLLECTION' ? execution(type, 'FAILED', {
        startedAt: type === 'ANNOUNCEMENT_SYNC' ? syncAt : collectionAt,
        partiallyFailedSteps: [{
          step: 'COLLECT_LH_ANNOUNCEMENT_SUPPLIES', stepName: 'LH 공고 공급 원본 수집',
          report: { successfulRequestCount: type === 'ANNOUNCEMENT_SYNC' ? 2 : 4, failedRequestCount: 1 },
        }],
      }) : execution(type, 'IDLE'),
    ))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    const metric = within((await screen.findByRole('heading', { name: '최근 LH 공급 수집' })).parentElement!)
    await waitFor(() => expect(metric.getByText(rate)).toBeVisible())
  })

  it('통합 실행 수집 합계에 성공 단계와 부분 실패 단계를 중복 없이 포함한다', async () => {
    const successful = { step: 'COLLECT_LH_ANNOUNCEMENT_CATALOG', stepName: 'LH 목록', report: { externalApiCallCount: 2, storedRowCount: 5 } }
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'ANNOUNCEMENT_SYNC' ? execution(type, 'FAILED', {
        completedStepResults: [successful],
        partiallyFailedSteps: [successful, { step: 'COLLECT_MYHOME_ANNOUNCEMENTS', stepName: '마이홈 공고', report: { externalApiCallCount: 3, failedRequestCount: 1 } }],
      }) : execution(type, 'IDLE'),
    ))
    render(<MemoryRouter><DataPipelineControl /></MemoryRouter>)

    const summary = await screen.findByRole('group', { name: '공고 수집·정제 결과 요약' })
    expect(summary).toHaveTextContent(/API 호출\s*5/)
    expect(summary).toHaveTextContent(/저장한 행\s*5/)
    expect(summary).toHaveTextContent(/실패 요청\s*1/)
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
    partiallyFailedSteps: [],
    failure: null,
    ...overrides,
  }
}

function stepCount(type: DataPipelineType): number {
  if (type === 'ANNOUNCEMENT_SYNC') return 6
  if (type === 'COMPLEX_SYNC' || type === 'ANNOUNCEMENT_COLLECTION') return 4
  return 2
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolvePromise: (value: T) => void
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve
  })
  return { promise, resolve: resolvePromise! }
}
