import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getPipelineHistory, type DataPipelineExecution } from './api'
import { PipelineHistory } from './PipelineHistory'
import { getFailureReviews } from './failureReviewApi'

vi.mock('./api', () => ({ getPipelineHistory: vi.fn() }))
vi.mock('./failureReviewApi', () => ({ getFailureReviews: vi.fn().mockResolvedValue({ items: [], page: 0, totalElements: 0, totalPages: 0, hasNext: false }) }))
const getHistory = vi.mocked(getPipelineHistory)

beforeEach(() => { getHistory.mockReset(); vi.mocked(getFailureReviews).mockClear() })

describe('데이터 실행 이력', () => {
  it('단지 이력은 아이콘으로 페이지 이동·새로고침·상세 닫기를 제공한다', async () => {
    const first = Array.from({ length: 20 }, (_, index) => execution({ executionId: `run-${index}` }))
    getHistory.mockResolvedValueOnce(first).mockResolvedValueOnce([execution()]).mockResolvedValueOnce(first).mockResolvedValueOnce(first)
    render(<MemoryRouter><PipelineHistory domain="complex" compact readOnlyComplex /></MemoryRouter>)
    fireEvent.click((await screen.findAllByRole('button', { name: /상세 보기/ }))[0])
    const close = screen.getByRole('button', { name: '닫기' })
    expect(close).toHaveTextContent(/^$/)
    fireEvent.click(close)
    expect(screen.queryByRole('region', { name: '선택한 실행 상세' })).not.toBeInTheDocument()
    for (const label of ['새로고침', '이전', '다음']) {
      const button = screen.getByRole('button', { name: label })
      expect(button).toHaveTextContent(/^$/)
      expect(button.querySelector('svg')).not.toBeNull()
    }
    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    await screen.findByRole('button', { name: /상세 보기/ })
    expect(screen.getByText('2 페이지')).toBeVisible()
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '이전' }))
    await screen.findAllByRole('button', { name: /상세 보기/ })
    fireEvent.click(screen.getByRole('button', { name: '새로고침' }))
    await screen.findAllByRole('button', { name: /상세 보기/ })
    expect(getHistory.mock.calls).toEqual([[0, 'complex'], [1, 'complex'], [0, 'complex'], [0, 'complex']])
  })
  it('단지 이력 상세는 저장된 단계별 결과만 표시하고 당시 상태가 아닌 오류 조회는 제공하지 않는다', async () => {
    getHistory.mockResolvedValue([execution({ type: 'COMPLEX_REFINEMENT', completedStepResults: [
      { step: 'MAP_MYHOME_COMPLEXES', stepName: '마이홈 단지 정제', report: { createdComplexCount: 4, updatedComplexCount: 7, failedSourceCount: 2 } },
    ] })])
    render(<MemoryRouter><PipelineHistory domain="complex" compact readOnlyComplex /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: /상세 보기/ }))
    const detail = screen.getByRole('region', { name: '선택한 실행 상세' })
    expect(within(detail).getByText('신규 단지').nextSibling).toHaveTextContent('4')
    expect(within(detail).getByText('갱신 단지').nextSibling).toHaveTextContent('7')
    expect(within(detail).getByText('실패한 원천').nextSibling).toHaveTextContent('2')
    expect(screen.queryByRole('button', { name: '결과 오류 조회' })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: '오류 종류' })).not.toBeInTheDocument()
    expect(getFailureReviews).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /실행 중지|정제 실행|수정|보정/ })).not.toBeInTheDocument()
  })
  it('v2 이력 상세에도 같은 단계 표시를 쓰며 진행 중 이력에서 중지하지 않는다', async () => {
    getHistory.mockResolvedValue([execution({ status: 'RUNNING', currentStepName: '마이홈 단지 수집' })])
    render(<MemoryRouter><PipelineHistory domain="complex" compact /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: /상세 보기/ }))
    expect(screen.getByRole('list', { name: '실행 단계' }).children).toHaveLength(4)
    expect(screen.getByRole('button', { name: '마이홈 단지 수집 진행' })).toBeVisible()
    expect(screen.queryByRole('button', { name: /실행 중지/ })).not.toBeInTheDocument()
  })
  it('v2는 도메인 조건을 서버로 보내 실행 이력을 분리한다', async () => {
    getHistory.mockResolvedValue([])
    render(<PipelineHistory domain="complex" />)
    await screen.findByText('실행 이력이 없습니다.')
    expect(getHistory).toHaveBeenCalledWith(0, 'complex')
  })
  it('로딩 중에는 새로고침을 잠그고 빈 결과를 표시하지 않는다', async () => {
    const result = deferred<DataPipelineExecution[]>()
    getHistory.mockReturnValue(result.promise)
    renderHistory()
    expect(screen.getByRole('status')).toHaveTextContent('이력을 불러오는 중…')
    expect(screen.getByRole('button', { name: '새로고침' })).toBeDisabled()
    expect(screen.queryByText('실행 이력이 없습니다.')).not.toBeInTheDocument()

    await act(async () => result.resolve([]))
    expect(screen.getByText('실행 이력이 없습니다.')).toBeVisible()
    expect(screen.getByRole('button', { name: '새로고침' })).toBeEnabled()
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  })

  it('조회 실패를 표시하고 새로고침으로 같은 페이지를 재시도한다', async () => {
    getHistory.mockRejectedValueOnce(new Error('연결 실패')).mockResolvedValueOnce([execution()])
    renderHistory()
    expect(await screen.findByRole('alert')).toHaveTextContent('연결 실패')
    expect(screen.queryByText('실행 이력이 없습니다.')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '새로고침' }))
    expect(await screen.findByRole('table')).toHaveTextContent('단지 수집·정제')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(getHistory.mock.calls).toEqual([[0], [0]])
  })

  it('선택한 실행 결과와 실패 화면 링크를 보여 주고 상세를 닫는다', async () => {
    getHistory.mockResolvedValue([execution()])
    renderHistory()
    fireEvent.click(await screen.findByRole('button', { name: /상세 보기/ }))
    const detail = screen.getByRole('region', { name: '선택한 실행 상세' })
    expect(within(detail).getByRole('status')).toHaveTextContent('단지 수집·정제 작업을 완료했습니다.')
    expect(within(detail).getByRole('link', { name: '단지 수집·정제 수집 실패 요청 보기' }))
      .toHaveAttribute('href', '/admin/failures?domain=complex&category=collection&executionId=run-1')
    fireEvent.click(within(detail).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('region', { name: '선택한 실행 상세' })).not.toBeInTheDocument()
  })

  it('실행 중인 과거 기록은 중지 요청을 보내지 못한다', async () => {
    getHistory.mockResolvedValue([execution({ status: 'RUNNING' })])
    renderHistory()
    fireEvent.click(await screen.findByRole('button', { name: /상세 보기/ }))
    expect(screen.getByRole('button', { name: '중지 요청 중…' })).toBeDisabled()
    expect(getHistory).toHaveBeenCalledOnce()
  })

  it('20건이면 다음 페이지로 이동하고 선택 상세를 초기화하며 이전 페이지로 돌아온다', async () => {
    getHistory.mockResolvedValueOnce(Array.from({ length: 20 }, (_, index) => execution({ executionId: `run-${index}` })))
      .mockResolvedValueOnce([execution({ executionId: 'older-run' })])
      .mockResolvedValueOnce([])
    renderHistory()
    fireEvent.click((await screen.findAllByRole('button', { name: /상세 보기/ }))[0])
    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    await screen.findByRole('button', { name: /상세 보기/ })
    expect(screen.queryByRole('region', { name: '선택한 실행 상세' })).not.toBeInTheDocument()
    expect(screen.getByText('2 페이지')).toBeVisible()
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '이전' }))
    await screen.findByText('실행 이력이 없습니다.')
    expect(getHistory.mock.calls).toEqual([[0], [1], [0]])
  })

  it('언마운트된 화면의 늦은 응답은 다음 화면에 섞이지 않는다', async () => {
    const first = deferred<DataPipelineExecution[]>()
    getHistory.mockReturnValueOnce(first.promise).mockResolvedValueOnce([])
    const { unmount } = renderHistory()
    unmount()
    renderHistory()
    await screen.findByText('실행 이력이 없습니다.')
    await act(async () => first.resolve([execution()]))
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByText('실행 이력이 없습니다.')).toBeVisible()
  })
})

function renderHistory() {
  return render(<MemoryRouter><PipelineHistory /></MemoryRouter>)
}

function execution(overrides: Partial<DataPipelineExecution> = {}): DataPipelineExecution {
  return {
    executionId: 'run-1', type: 'COMPLEX_SYNC', status: 'COMPLETED',
    startedAt: '2026-10-01T00:00:00Z', currentStepName: null, currentStepIndex: 4, totalStepCount: 4,
    completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null, ...overrides,
  }
}

function deferred<T>() {
  let resolve: (value: T) => void = () => { throw new Error('Promise가 초기화되지 않았습니다.') }
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}
