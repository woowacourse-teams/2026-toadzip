import { MemoryRouter } from 'react-router'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DataPipelineExecution, DataPipelineType } from './api'
import { DataPipelineControl } from './DataPipelineControl'

const apiMocks = vi.hoisted(() => ({
  getDataPipelineReadiness: vi.fn(),
  getDataPipelineStatus: vi.fn(),
  startDataPipeline: vi.fn(),
  stopDataPipeline: vi.fn(),
  getLhAnnouncementQuality: vi.fn(),
  getInitialRegistration: vi.fn(),
  getLocationPreparation: vi.fn(),
}))

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  ...apiMocks,
}))

beforeEach(() => {
  apiMocks.getLocationPreparation.mockResolvedValue({ counts: { READY: 0, INVALID_ADDRESS: 0, ADDRESS_NOT_FOUND: 0, COORDINATE_NOT_FOUND: 0, COORDINATE_CONVERSION_ERROR: 0 }, targets: [], totalElements: 0, page: 0, totalPages: 0 })
  apiMocks.getInitialRegistration.mockResolvedValue({
    counts: { EXISTING: 0, LOCATION_REQUIRED: 0, INFORMATION_REQUIRED: 0, READY: 0, FAILED: 0, COMPLETED: 0 },
    targets: [], totalElements: 0, page: 0, totalPages: 0,
  })
  apiMocks.getDataPipelineReadiness.mockReset()
  apiMocks.getDataPipelineReadiness.mockResolvedValue({ serviceKeyConfigured: true })
  apiMocks.getDataPipelineStatus.mockReset()
  apiMocks.startDataPipeline.mockReset()
  apiMocks.stopDataPipeline.mockReset()
  apiMocks.getLhAnnouncementQuality.mockReset()
  apiMocks.getLhAnnouncementQuality.mockResolvedValue({
    observedAt: '2026-09-28T00:00:00Z',
    connection: { total: 0, complexLinked: 0, housingTypeLinked: 0, unlinkedReasons: {} },
    amounts: { total: 0, fulfilled: 0 },
    schedules: { total: 0, reviewed: 0, withApplicationSchedule: 0 },
    supplyCollection: { totalRequests: 0, collectedRequests: 0, latestCollectedAt: null },
    detailCollection: { totalRequests: 0, collectedRequests: 0, latestCollectedAt: null },
    unlinkedLhLeaseCatalogCount: 0,
    unlinkedLhCandidates: [],
    preservedSourceRequestCount: 0,
    preservedReasons: {},
    heldRequests: [],
  })
  apiMocks.getDataPipelineStatus.mockImplementation(
    (type: DataPipelineType) => Promise.resolve(execution(type, 'IDLE')),
  )
})

describe('DataPipelineControl', () => {
  it('LH 공고 갱신은 키 입력 없이 한 번 눌러 시작하고 접수 대기 뒤 실제 단계를 연다', async () => {
    const accepted = deferred<DataPipelineExecution>()
    apiMocks.startDataPipeline.mockReturnValue(accepted.promise)
    render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    await act(async () => {})
    fireEvent.click(screen.getByRole('button', { name: 'LH 공고 갱신' }))
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/키/)).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('실행 접수를 확인')
    expect(screen.queryByRole('button', { name: /실행 중지/ })).not.toBeInTheDocument()
    expect(apiMocks.startDataPipeline).toHaveBeenCalledExactlyOnceWith('ANNOUNCEMENT_SYNC')
    await act(async () => accepted.resolve(execution('ANNOUNCEMENT_SYNC', 'RUNNING', { currentStepName: 'LH 공고 목록 수집' })))
    expect(screen.getByRole('button', { name: 'LH 공고 갱신 실행 중지' })).toBeEnabled()
  })

  it('서버 수집 키가 없으면 미설정을 알리고 공공데이터포털을 쓰는 LH 갱신만 막는다', async () => {
    apiMocks.getDataPipelineReadiness.mockResolvedValue({ serviceKeyConfigured: false })
    apiMocks.startDataPipeline.mockResolvedValue(execution('GH_ANNOUNCEMENT_COLLECTION', 'COMPLETED'))
    render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)

    expect(await screen.findByRole('alert')).toHaveTextContent('서버 수집 키 미설정')
    expect(screen.getByRole('alert')).toHaveTextContent('DATA_GO_KR_SERVICE_KEY')
    await waitFor(() => expect(screen.getByRole('button', { name: 'SH 공고 수집' })).toBeEnabled())
    expect(screen.getByRole('button', { name: 'LH 공고 갱신' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'GH 공고 수집' }))
    expect(apiMocks.startDataPipeline).toHaveBeenCalledExactlyOnceWith('GH_ANNOUNCEMENT_COLLECTION')
  })

  it('정제만 실행하는 길은 LH 갱신이 정제 전에 멈춘 실행 결과의 복구 버튼이며 수집 키가 없어도 실행된다', async () => {
    apiMocks.getDataPipelineReadiness.mockResolvedValue({ serviceKeyConfigured: false })
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'ANNOUNCEMENT_SYNC' ? execution(type, 'FAILED', {
        startedAt: '2026-11-06T01:00:00Z', currentStepIndex: 2, currentStepName: 'LH 공고 공급 원본 수집',
        completedSteps: ['LH 공고 목록 수집'],
        failure: { stepName: 'LH 공고 공급 원본 수집', message: '호출 제한으로 멈췄습니다.', serverResponse: null },
      }) : execution(type, 'IDLE')))
    apiMocks.startDataPipeline.mockResolvedValue(execution('ANNOUNCEMENT_REFINEMENT', 'RUNNING'))
    render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    await screen.findByText('서버 수집 키 미설정')
    expect(screen.queryByRole('button', { name: /정제/ })).not.toBeInTheDocument()

    fireEvent.click(within(screen.getByRole('region', { name: 'LH' })).getByRole('button', { name: '실행 결과 보기' }))
    const refine = screen.getByRole('button', { name: '저장된 원천으로 공고 정제 실행' })
    expect(refine).toBeEnabled()
    // 저장된 원천 전체를 다시 정제하므로 범위를 알리고 묻는다. 취소하면 요청이 나가지 않는다.
    fireEvent.click(refine)
    expect(screen.getByRole('dialog', { name: '저장된 원천 전체로 공고 정제를 실행할까요?' })).toHaveTextContent('이전 수집의 원천도 포함됩니다.')
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(apiMocks.startDataPipeline).not.toHaveBeenCalled()
    fireEvent.click(refine)
    fireEvent.click(screen.getByRole('button', { name: '공고 정제 실행' }))
    await waitFor(() => expect(apiMocks.startDataPipeline).toHaveBeenCalledExactlyOnceWith('ANNOUNCEMENT_REFINEMENT'))
  })

  it('준비 상태를 확인하지 못해도 수집을 막지 않는다', async () => {
    apiMocks.getDataPipelineReadiness.mockRejectedValue(new Error('준비 상태 조회 실패'))
    apiMocks.startDataPipeline.mockResolvedValue(execution('ANNOUNCEMENT_SYNC', 'COMPLETED'))
    render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    await act(async () => {})

    expect(screen.queryByText('서버 수집 키 미설정')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'LH 공고 갱신' }))
    expect(apiMocks.startDataPipeline).toHaveBeenCalledExactlyOnceWith('ANNOUNCEMENT_SYNC')
  })

  it('개요로 돌아온 뒤 상태 조회가 이어져도 작업 화면으로 강제 이동하지 않는다', async () => {
    vi.useFakeTimers()
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      execution(type, type === 'ANNOUNCEMENT_SYNC' ? 'RUNNING' : 'IDLE')))
    const view = render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    try {
      await act(async () => {})
      fireEvent.click(screen.getByRole('button', { name: '공고 수집 개요로 돌아가기' }))
      await act(async () => { await vi.advanceTimersByTimeAsync(1100) })
      expect(screen.queryByRole('article', { name: '현재 작업 단계' })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'LH 공고 갱신' })).toBeDisabled()
      fireEvent.click(screen.getByRole('button', { name: '실행 결과 보기' }))
      expect(screen.getByRole('button', { name: 'LH 공고 갱신 실행 중지' })).toBeVisible()
    } finally { view.unmount(); vi.useRealTimers() }
  })

  it('지난 공고 단건 등록이 실행 중이면 공통 실행 잠금에 포함하고 지원 종료한 등록 화면으로 보내지 않는다', async () => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      execution(type, type === 'ANNOUNCEMENT_REGISTRATION' ? 'RUNNING' : 'IDLE')))
    render(<MemoryRouter><DataPipelineControl domain="complex" /></MemoryRouter>)
    await act(async () => {})
    expect(screen.getByRole('button', { name: '단지 원천 수집 실행' })).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('공고 단건 등록 실행 중입니다. 수집·정제는 한 번에 한 작업만 실행할 수 있습니다.')
    expect(screen.queryByRole('link', { name: '실행 중인 작업 보기' })).not.toBeInTheDocument()
  })

  it.each([
    ['SH', 'SH_ANNOUNCEMENT_COLLECTION'], ['GH', 'GH_ANNOUNCEMENT_COLLECTION'],
  ] as const)('v2 공고 화면에서 %s 수집을 키 없이 실행하고 수집·정제 한 단계를 표시한다', async (agency, type) => {
    apiMocks.startDataPipeline.mockResolvedValue(execution(type, 'COMPLETED'))
    render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    await act(async () => {})
    expect(screen.queryByText('아직 실행하지 않았습니다.')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: `${agency} 공고 수집` }))
    expect(apiMocks.startDataPipeline).toHaveBeenCalledWith(type)
    const result = await screen.findByRole('article', { name: '현재 작업 단계' })
    expect(within(result).getByRole('list', { name: '실행 단계' }).children).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: '공고 수집 개요로 돌아가기' }))
    await waitFor(() => expect(screen.getByRole('button', { name: `${agency} 공고 수집` })).toBeEnabled())
  })

  it('첫 화면은 기관별 실행 버튼과 마지막 결과만 두고 수집·정제 단독 실행은 두지 않는다', async () => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'ANNOUNCEMENT_SYNC' ? execution(type, 'COMPLETED', { startedAt: '2026-10-20T02:00:00Z' })
        : type === 'ANNOUNCEMENT_REFINEMENT' ? execution(type, 'COMPLETED_WARNINGS', { startedAt: '2026-10-24T09:35:41Z' })
          : type === 'GH_ANNOUNCEMENT_COLLECTION' ? execution(type, 'COMPLETED', { startedAt: '2026-11-06T05:02:14Z' })
            : execution(type, 'IDLE')))
    render(<MemoryRouter><DataPipelineControl domain="announcement"
      history={<section aria-label="실행 이력 자리" />} /></MemoryRouter>)
    await act(async () => {})

    // LH 마지막 결과는 따로 실행했던 정제가 더 최근이면 그 실행을 이름과 함께 보인다.
    const lh = screen.getByRole('region', { name: 'LH' })
    expect(within(lh).getByRole('button', { name: 'LH 공고 갱신' })).toBeEnabled()
    expect(within(lh).getByText('일부 실패')).toBeVisible()
    expect(within(lh).getByText('공고 정제')).toBeVisible()
    const sh = screen.getByRole('region', { name: 'SH' })
    expect(within(sh).getByText('아직 실행 이력이 없습니다.')).toBeVisible()
    expect(within(sh).queryByRole('button', { name: '실행 결과 보기' })).not.toBeInTheDocument()
    const gh = screen.getByRole('region', { name: 'GH' })
    expect(within(gh).getByText('완료')).toBeVisible()
    expect(within(gh).getAllByText('GH 공고 수집')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: '공고 수집' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '공고 정제' })).not.toBeInTheDocument()
    expect(screen.queryByText('수집·정제 따로 실행')).not.toBeInTheDocument()

    // 실행 이력은 실행 카드 뒤에, 드물게 쓰는 LH 품질·보류 확인은 접힌 채 맨 뒤에 둔다.
    const history = screen.getByRole('region', { name: '실행 이력 자리' })
    const quality = screen.getByText('LH 데이터 품질·공급 감소 보류 확인').closest('details')!
    expect(screen.getByRole('region', { name: '데이터 실행' }).compareDocumentPosition(history) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(history.compareDocumentPosition(quality) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(quality).not.toHaveAttribute('open')
    expect(apiMocks.getLhAnnouncementQuality).not.toHaveBeenCalled()
    fireEvent.click(within(quality).getByText('LH 데이터 품질·공급 감소 보류 확인'))
    expect(await screen.findByRole('heading', { name: '원천 감소·정정 보류 요청' })).toBeVisible()
    expect(apiMocks.getLhAnnouncementQuality).toHaveBeenCalledOnce()

    fireEvent.click(within(lh).getByRole('button', { name: '실행 결과 보기' }))
    expect(screen.getByRole('article', { name: '현재 작업 단계' })).toHaveTextContent('공고 정제')
    expect(screen.queryByText('LH 데이터 품질·공급 감소 보류 확인')).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: '실행 이력 자리' })).toBeInTheDocument()
    expect(apiMocks.startDataPipeline).not.toHaveBeenCalled()
  })

  it('v2 새로고침은 완료 카드를 복원하지 않고 진행 중인 작업 하나만 복원한다', async () => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) =>
      Promise.resolve(execution(type, type === 'ANNOUNCEMENT_SYNC' ? 'RUNNING' : 'COMPLETED')))
    render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    await screen.findByRole('article', { name: '현재 작업 단계' })
    expect(screen.getAllByRole('article', { name: '현재 작업 단계' })).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'LH 공고 갱신 실행 중지' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '공고 수집 개요로 돌아가기' }))
    expect(screen.getByRole('button', { name: 'LH 공고 갱신' })).toBeDisabled()
    expect(apiMocks.getLhAnnouncementQuality).not.toHaveBeenCalled()
  })

  it.each(['ANNOUNCEMENT_SYNC', 'SH_ANNOUNCEMENT_COLLECTION'] as const)(
    'v2 탭 전환 중에도 %s 실행의 잠금과 중지를 보존한다', async (runningType) => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) =>
      Promise.resolve(execution(type, type === runningType ? 'RUNNING' : 'IDLE')))
    const view = render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    await screen.findByRole('article', { name: '현재 작업 단계' })
    view.rerender(<MemoryRouter><DataPipelineControl domain="complex" /></MemoryRouter>)
    expect(screen.queryByRole('article', { name: '현재 작업 단계' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: '3 신규 단지 등록' }))
    expect(screen.getByRole('button', { name: '대상 상태 확인' })).toBeDisabled()
    expect(screen.getByText(/한 번에 한 작업/)).toHaveAttribute('role', 'status')
    view.rerender(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    const stopLabel = runningType === 'SH_ANNOUNCEMENT_COLLECTION'
      ? 'SH 공고 수집 실행 중지' : 'LH 공고 갱신 실행 중지'
    expect(screen.getByRole('button', { name: stopLabel })).toBeVisible()
  })
  it('최초 상태 조회가 실패해도 재조회하여 기존 실행과 중지 버튼을 복원한다', async () => {
    vi.useFakeTimers()
    let attempts = 0
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => {
      if (type !== 'ANNOUNCEMENT_SYNC') return Promise.resolve(execution(type, 'IDLE'))
      attempts += 1
      if (attempts === 1) return Promise.reject(new Error('상태 조회 일시 실패'))
      return Promise.resolve(execution(type, 'RUNNING', { executionId: 'running-sync' }))
    })
    const view = render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    try {
      await act(async () => {})
      expect(screen.getByRole('alert')).toHaveTextContent('상태 조회 일시 실패')
      expect(within(screen.getByRole('region', { name: 'LH' })).getByText('최근 실행을 확인하지 못했습니다.')).toBeVisible()

      await act(async () => { await vi.advanceTimersByTimeAsync(1_000) })

      expect(screen.queryByText(/상태 조회 일시 실패/)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'LH 공고 갱신' })).toBeDisabled()
      expect(screen.getByRole('button', { name: 'SH 공고 수집' })).toBeDisabled()
      fireEvent.click(screen.getByRole('button', { name: '실행 결과 보기' }))
      expect(screen.getByRole('button', { name: 'LH 공고 갱신 실행 중지' })).toBeEnabled()
      expect(apiMocks.startDataPipeline).not.toHaveBeenCalled()
    } finally {
      view.unmount()
      vi.useRealTimers()
    }
  })

  it('시작에 실패하면 서버 안내를 보여 주고 다시 시작할 수 있다', async () => {
    apiMocks.startDataPipeline.mockRejectedValue(new Error('서버 수집 키가 설정되지 않아 수집을 시작할 수 없습니다.'))
    render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    await act(async () => {})
    fireEvent.click(screen.getByRole('button', { name: 'LH 공고 갱신' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('상태 조회 또는 요청에 실패했습니다.')
    expect(alert).toHaveTextContent('서버 수집 키가 설정되지 않아')
    fireEvent.click(screen.getByRole('button', { name: '공고 수집 개요로 돌아가기' }))
    expect(screen.getByRole('button', { name: 'LH 공고 갱신' })).toBeEnabled()
    expect(apiMocks.startDataPipeline).toHaveBeenCalledTimes(1)
  })

  it('실행 중지는 묻고 나서 보내며 취소하면 요청하지 않고, 실제 종료 응답이 오기 전까지 새 실행을 막는다', async () => {
    vi.useFakeTimers()
    const running = execution('ANNOUNCEMENT_SYNC', 'RUNNING', { executionId: 'run-1' })
    let current: DataPipelineExecution = running
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) =>
      Promise.resolve(type === 'ANNOUNCEMENT_SYNC' ? current : execution(type, 'IDLE')))
    apiMocks.stopDataPipeline.mockImplementation(() => {
      current = { ...running, stopRequested: true }
      return Promise.resolve(current)
    })
    const view = render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    try {
      await act(async () => {})
      const stop = screen.getByRole('button', { name: 'LH 공고 갱신 실행 중지' })
      fireEvent.click(stop)
      const dialog = screen.getByRole('dialog', { name: 'LH 공고 갱신 실행을 중지할까요?' })
      expect(dialog).toHaveTextContent('이미 저장한 데이터는 유지되고, 남은 대상은 다시 실행해야 처리됩니다.')
      fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))
      await flush()
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(apiMocks.stopDataPipeline).not.toHaveBeenCalled()
      expect(stop).toBeEnabled()

      fireEvent.click(stop)
      fireEvent.click(screen.getByRole('button', { name: '실행 중지' }))
      await flush()
      expect(apiMocks.stopDataPipeline).toHaveBeenCalledExactlyOnceWith('run-1')
      expect(screen.getByRole('button', { name: '중지 요청 중…' })).toBeDisabled()
      expect(screen.getByText('현재 처리가 끝나면 중지합니다. 이미 저장한 데이터는 유지됩니다.')).toBeVisible()
      fireEvent.click(screen.getByRole('button', { name: '공고 수집 개요로 돌아가기' }))
      await act(async () => { await vi.advanceTimersByTimeAsync(1_000) })
      expect(screen.getByRole('button', { name: 'LH 공고 갱신' })).toBeDisabled()
      expect(screen.getByRole('button', { name: 'GH 공고 수집' })).toBeDisabled()

      current = execution('ANNOUNCEMENT_SYNC', 'STOPPED', { executionId: 'run-1', stopRequested: true })
      await act(async () => { await vi.advanceTimersByTimeAsync(1_000) })
      expect(screen.getByRole('button', { name: 'LH 공고 갱신' })).toBeEnabled()
      expect(within(screen.getByRole('region', { name: 'LH' })).getByText('중지됨')).toBeVisible()
    } finally {
      view.unmount()
      vi.useRealTimers()
    }
  })

  it('중지 API가 실패하면 완료로 표시하지 않고 다시 중지할 수 있다', async () => {
    vi.useFakeTimers()
    const running = execution('ANNOUNCEMENT_SYNC', 'RUNNING', { executionId: 'run-1' })
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) =>
      Promise.resolve(type === 'ANNOUNCEMENT_SYNC' ? running : execution(type, 'IDLE')))
    apiMocks.stopDataPipeline.mockRejectedValueOnce(new Error('중지 요청 실패'))
      .mockResolvedValue(execution('ANNOUNCEMENT_SYNC', 'STOPPED', { executionId: 'run-1', stopRequested: true }))
    const view = render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    try {
      await act(async () => {})
      fireEvent.click(screen.getByRole('button', { name: 'LH 공고 갱신 실행 중지' }))
      fireEvent.click(screen.getByRole('button', { name: '실행 중지' }))
      await flush()
      expect(screen.getByRole('alert')).toHaveTextContent('중지 요청 실패')
      expect(screen.getByRole('button', { name: 'LH 공고 갱신 실행 중지' })).toBeEnabled()

      await act(async () => { await vi.advanceTimersByTimeAsync(1_000) })
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'LH 공고 갱신 실행 중지' })).toBeEnabled()

      fireEvent.click(screen.getByRole('button', { name: 'LH 공고 갱신 실행 중지' }))
      fireEvent.click(screen.getByRole('button', { name: '실행 중지' }))
      await flush()
      expect(apiMocks.stopDataPipeline).toHaveBeenNthCalledWith(1, 'run-1')
      expect(apiMocks.stopDataPipeline).toHaveBeenNthCalledWith(2, 'run-1')
      expect(apiMocks.stopDataPipeline).toHaveBeenCalledTimes(2)
      expect(screen.queryByRole('button', { name: /실행 중지/ })).not.toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: '공고 수집 개요로 돌아가기' }))
      expect(screen.getByRole('button', { name: 'LH 공고 갱신' })).toBeEnabled()
      expect(within(screen.getByRole('region', { name: 'LH' })).getByText('중지됨')).toBeVisible()
    } finally {
      view.unmount()
      vi.useRealTimers()
    }
  })

  it('시작 응답과 상태 조회를 모두 잃으면 실행 잠금을 유지하며 재조회한다', async () => {
    render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    await waitFor(() => expect(apiMocks.getDataPipelineStatus).toHaveBeenCalledTimes(10))
    await act(async () => {})
    apiMocks.startDataPipeline.mockRejectedValue(new Error('네트워크 연결이 끊겼습니다.'))
    apiMocks.getDataPipelineStatus.mockRejectedValue(new Error('상태를 조회하지 못했습니다.'))

    fireEvent.click(screen.getByRole('button', { name: 'LH 공고 갱신' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('접수 여부를 확인하지 못했습니다.')
    expect(alert).toHaveTextContent('네트워크 연결이 끊겼습니다.')
    fireEvent.click(screen.getByRole('button', { name: '공고 수집 개요로 돌아가기' }))
    expect(screen.getByRole('button', { name: 'LH 공고 갱신' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'SH 공고 수집' })).toBeDisabled()
    expect(apiMocks.startDataPipeline).toHaveBeenCalledTimes(1)
  })

  it.each(['FAILED', 'COMPLETED_WITH_SKIPS', 'STOPPED'] as const)(
    '간소화 화면에서도 공고 갱신이 %s로 끝나면 결과를 확인한 뒤 전체 원천 정제를 실행한다', async status => {
      apiMocks.startDataPipeline.mockResolvedValueOnce(execution('ANNOUNCEMENT_SYNC', status))
        .mockResolvedValueOnce(execution('ANNOUNCEMENT_REFINEMENT', 'RUNNING'))
      render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
      await act(async () => {})
      fireEvent.click(screen.getByRole('button', { name: 'LH 공고 갱신' }))
      const recover = await screen.findByRole('button', { name: '저장된 원천으로 공고 정제 실행' })
      expect(apiMocks.startDataPipeline).toHaveBeenCalledTimes(1)
      expect(screen.getByText(/정제는 현재 저장된 원천 전체/)).toBeVisible()
      expect(screen.getByRole('link', { name: '실패 내역 확인' })).toHaveAttribute('href', expect.stringContaining('category=all'))
      fireEvent.click(recover)
      fireEvent.click(screen.getByRole('button', { name: '공고 정제 실행' }))
      await screen.findByRole('button', { name: '공고 정제 실행 중지' })
      expect(apiMocks.startDataPipeline).toHaveBeenLastCalledWith('ANNOUNCEMENT_REFINEMENT')
      expect(apiMocks.startDataPipeline).toHaveBeenCalledTimes(2)
      fireEvent.click(screen.getByRole('button', { name: '공고 수집 개요로 돌아가기' }))
      expect(screen.getByRole('button', { name: 'LH 공고 갱신' })).toBeDisabled()
    },
  )

  it('최초 상태 조회가 끝나기 전에는 실행을 받지 않아, 늦게 온 조회 결과가 새 실행을 덮어쓸 수 없다', async () => {
    const pendingStatus = deferred<DataPipelineExecution>()
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) =>
      type === 'COMPLEX_COLLECTION' ? pendingStatus.promise : Promise.resolve(execution(type, 'IDLE')))
    render(<MemoryRouter><DataPipelineControl domain="complex" /></MemoryRouter>)
    await act(async () => {})
    fireEvent.click(screen.getByRole('button', { name: '단지 원천 수집 실행' }))
    expect(screen.getByRole('button', { name: '단지 원천 수집 실행' })).toBeDisabled()
    expect(apiMocks.startDataPipeline).not.toHaveBeenCalled()

    await act(async () => pendingStatus.resolve(execution('COMPLEX_COLLECTION', 'IDLE')))

    expect(screen.getByRole('button', { name: '단지 원천 수집 실행' })).toBeEnabled()
  })

  it('단지 수집 후에도 좌표 업로드를 다음 단계에서 선택한다', async () => {
    apiMocks.startDataPipeline.mockResolvedValue(execution('COMPLEX_COLLECTION', 'COMPLETED'))
    render(<MemoryRouter><DataPipelineControl domain="complex" /></MemoryRouter>)
    await act(async () => {})
    fireEvent.click(screen.getByRole('button', { name: '단지 원천 수집 실행' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '단지 원천 수집 실행' })).toBeEnabled())
    expect(screen.getByRole('tabpanel')).toHaveTextContent('완료')
    fireEvent.click(screen.getByRole('tab', { name: '2 주소·좌표 준비' }))
    expect(screen.getByLabelText('위치정보요약DB 월 전체분 ZIP')).toBeVisible()
    expect(apiMocks.startDataPipeline).toHaveBeenCalledExactlyOnceWith('COMPLEX_COLLECTION')
  })

  it('단지 등록 단계에 지난 정제 결과를 섞지 않는다', async () => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'COMPLEX_REFINEMENT' ? execution(type, 'COMPLETED_WARNINGS') : execution(type, 'IDLE'),
    ))
    render(<MemoryRouter><DataPipelineControl domain="complex" /></MemoryRouter>)
    await act(async () => {})
    fireEvent.click(screen.getByRole('tab', { name: '3 신규 단지 등록' }))
    expect(screen.getByRole('heading', { name: '신규 단지 등록' })).toBeVisible()
    expect(screen.queryByText('일부 실패')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '단지 저장 결과 확인' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: '2 주소·좌표 준비' }))
    expect(screen.getByLabelText('위치정보요약DB 월 전체분 ZIP')).toBeVisible()
  })

  it('이미 정제가 시작된 LH 갱신 실패를 정제 미실행으로 표시하지 않는다', async () => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'ANNOUNCEMENT_SYNC' ? execution(type, 'FAILED', { currentStepIndex: 5 }) : execution(type, 'IDLE'),
    ))
    render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: '실행 결과 보기' }))

    expect(screen.getByRole('article', { name: '현재 작업 단계' })).toBeVisible()
    expect(screen.queryByText(/자동 정제가 실행되지 않았습니다/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '저장된 원천으로 공고 정제 실행' })).not.toBeInTheDocument()
  })

  it.each([
    ['2026-09-30T02:00:00Z', '2026-09-29T02:00:00Z', '2/3 (67%)'],
    ['2026-09-29T02:00:00Z', '2026-09-30T02:00:00Z', '4/5 (80%)'],
  ])('LH 품질 지표는 갱신·단독 수집 중 최신 실행의 성공률을 표시한다 (%s, %s)', async (syncAt, collectionAt, rate) => {
    apiMocks.getDataPipelineStatus.mockImplementation((type: DataPipelineType) => Promise.resolve(
      type === 'ANNOUNCEMENT_SYNC' || type === 'ANNOUNCEMENT_COLLECTION' ? execution(type, 'FAILED', {
        startedAt: type === 'ANNOUNCEMENT_SYNC' ? syncAt : collectionAt,
        partiallyFailedSteps: [{
          step: 'COLLECT_LH_ANNOUNCEMENT_SUPPLIES', stepName: 'LH 공고 공급 원본 수집',
          report: { successfulRequestCount: type === 'ANNOUNCEMENT_SYNC' ? 2 : 4, failedRequestCount: 1 },
        }],
      }) : execution(type, 'IDLE'),
    ))
    render(<MemoryRouter><DataPipelineControl domain="announcement" /></MemoryRouter>)
    await act(async () => {})
    fireEvent.click(screen.getByText('LH 데이터 품질·공급 감소 보류 확인'))

    const metric = within((await screen.findByRole('heading', { name: '최근 LH 공급 수집' })).parentElement!)
    await waitFor(() => expect(metric.getByText(rate)).toBeVisible())
  })

})

// 가짜 타이머에서 확인 창 응답과 그 뒤 요청이 끝나도록 밀린 작업을 비운다.
async function flush() {
  await act(async () => { await vi.advanceTimersByTimeAsync(0) })
}

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
  if (type === 'SH_ANNOUNCEMENT_COLLECTION' || type === 'GH_ANNOUNCEMENT_COLLECTION') return 2
  if (type === 'ANNOUNCEMENT_SYNC') return 5
  if (type === 'COMPLEX_SYNC' || type === 'ANNOUNCEMENT_COLLECTION') return 4
  if (type === 'ANNOUNCEMENT_REFINEMENT') return 1
  return 2
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolvePromise: (value: T) => void
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve
  })
  return { promise, resolve: resolvePromise! }
}
