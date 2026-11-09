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
  it.each(['COMPLETED', 'FAILED'] as const)('신규 등록 %s 이력에 단지·주소 수와 상태별 결과를 표시한다', async status => {
    const result = { step: 'REGISTER_MYHOME_COMPLEXES', stepName: '준비된 신규 단지 등록', report: {
      targetCount: 3, uniqueAddressCount: 2,
      counts: { COMPLETED: 2, FAILED: 1, EXISTING: 4, READY: 0, INFORMATION_REQUIRED: 0, LOCATION_REQUIRED: 0 },
    } }
    getHistory.mockResolvedValue([execution({ type: 'COMPLEX_REGISTRATION', status, totalStepCount: 1,
      completedSteps: status === 'COMPLETED' ? [result.stepName] : [],
      completedStepResults: status === 'COMPLETED' ? [result] : [],
      partiallyFailedSteps: status === 'FAILED' ? [result] : [],
    })])
    render(<MemoryRouter><PipelineHistory domain="complex" compact readOnlyComplex /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: /상세 (펼치기|접기)/ }))
    const detail = screen.getByRole('region', { name: '선택한 실행 상세' })
    for (const [label, count] of [['등록 대상 단지', '3'], ['고유 원천 주소', '2'], ['등록 완료', '2'],
      ['등록 실패', '1'], ['기존 단지 건너뜀', '4'], ['등록 가능', '0'], ['정보 보완 필요', '0'], ['좌표 준비 필요', '0']]) {
      expect(within(detail).getByText(label).nextSibling).toHaveTextContent(count)
    }
    expect(within(detail).queryByText('대상 공고')).not.toBeInTheDocument()
  })
  it.each([true, false])('등록 실패 이력(단지 전용=%s)은 기존 오류 조회 대신 현재 등록 현황으로 안내한다', async readOnlyComplex => {
    getHistory.mockResolvedValue([execution({ type: 'COMPLEX_REGISTRATION', status: 'FAILED',
      failure: { stepName: '준비된 신규 단지 등록', message: '단지 저장 실패', serverResponse: null },
    })])
    render(<MemoryRouter><PipelineHistory domain="complex" readOnlyComplex={readOnlyComplex} /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: /상세 (펼치기|접기|보기)/ }))
    const detail = screen.getByRole('region', { name: '선택한 실행 상세' })
    expect(within(detail).getByRole('alert')).toHaveTextContent('단지 저장 실패')
    expect(within(detail).queryByRole('button', { name: '결과 오류 조회' })).not.toBeInTheDocument()
    expect(within(detail).getByRole('link', { name: '현재 등록 현황 보기' }))
      .toHaveAttribute('href', '/admin/ingest/complexes#complex-registration')
    expect(detail).toHaveTextContent('현재 상태')
    expect(detail).toHaveTextContent('상태 필터')
    expect(getFailureReviews).not.toHaveBeenCalled()
  })
  it('단지 이력은 아이콘으로 페이지 이동·새로고침·상세 닫기를 제공한다', async () => {
    const first = Array.from({ length: 20 }, (_, index) => execution({ executionId: `run-${index}` }))
    getHistory.mockResolvedValueOnce(first).mockResolvedValueOnce([execution()]).mockResolvedValueOnce(first).mockResolvedValueOnce(first)
    render(<MemoryRouter><PipelineHistory domain="complex" compact readOnlyComplex /></MemoryRouter>)
    const trigger = (await screen.findAllByRole('button', { name: /상세 (펼치기|접기)/ }))[0]
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('region', { name: '선택한 실행 상세' }).id).toBe(trigger.getAttribute('aria-controls'))
    expect(screen.getByRole('heading', { name: '선택한 실행 상세' })).toHaveFocus()
    fireEvent.click(trigger)
    expect(screen.queryByRole('region', { name: '선택한 실행 상세' })).not.toBeInTheDocument()
    fireEvent.click(trigger)
    const close = screen.getByRole('button', { name: '닫기' })
    expect(close).toHaveTextContent(/^$/)
    fireEvent.click(close)
    expect(trigger).toHaveFocus()
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: '선택한 실행 상세' })).not.toBeInTheDocument()
    for (const label of ['새로고침', '이전', '다음']) {
      const button = screen.getByRole('button', { name: label })
      expect(button).toHaveTextContent(/^$/)
      expect(button.querySelector('svg')).not.toBeNull()
    }
    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    await screen.findByRole('button', { name: /상세 (펼치기|접기)/ })
    expect(screen.getByText('2 페이지')).toBeVisible()
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '이전' }))
    await screen.findAllByRole('button', { name: /상세 (펼치기|접기)/ })
    fireEvent.click(screen.getByRole('button', { name: '새로고침' }))
    await screen.findAllByRole('button', { name: /상세 (펼치기|접기)/ })
    expect(getHistory.mock.calls).toEqual([[0, 'complex'], [1, 'complex'], [0, 'complex'], [0, 'complex']])
  })
  it('단지 이력에서 과거 정제 결과와 해당 실행에 연결된 오류의 현재 상태를 조회한다', async () => {
    getHistory.mockResolvedValue([execution({ type: 'COMPLEX_REFINEMENT', completedStepResults: [
      { step: 'MAP_MYHOME_COMPLEXES', stepName: '마이홈 단지 정제', report: { createdComplexCount: 4, updatedComplexCount: 7, failedSourceCount: 2 } },
    ] })])
    render(<MemoryRouter><PipelineHistory domain="complex" compact readOnlyComplex /></MemoryRouter>)
    fireEvent.click(await screen.findByRole('button', { name: /상세 (펼치기|접기)/ }))
    const detail = screen.getByRole('region', { name: '선택한 실행 상세' })
    expect(within(detail).getByText('신규 단지').nextSibling).toHaveTextContent('4')
    expect(within(detail).getByText('갱신 단지').nextSibling).toHaveTextContent('7')
    expect(within(detail).getByText('실패한 원천').nextSibling).toHaveTextContent('2')
    expect(screen.queryByRole('combobox', { name: '오류 종류' })).not.toBeInTheDocument()
    expect(getFailureReviews).not.toHaveBeenCalled()
    expect(detail).toHaveTextContent('현재 검토 상태')
    fireEvent.click(within(detail).getByRole('button', { name: '결과 오류 조회' }))
    await screen.findByRole('region', { name: '결과 오류' })
    expect(getFailureReviews).toHaveBeenCalledWith('complex', 'complex', 'ALL', 0, expect.any(AbortSignal), { executionId: 'run-1', keyword: '' })
    expect(screen.queryByRole('button', { name: /실행 중지|정제 실행|수정|보정/ })).not.toBeInTheDocument()
  })
  it('공고 이력의 실행 상세는 이력 카드 뒤 별도 카드로 열어 단계별 성공·실패를 보이고 닫으면 누른 행으로 돌아간다', async () => {
    getHistory.mockResolvedValue([
      execution({ executionId: 'sync-1', type: 'ANNOUNCEMENT_SYNC', status: 'COMPLETED_WARNINGS', totalStepCount: 4,
        completedSteps: ['LH 공고 목록 수집', 'LH 공고 공급 원본 수집', 'LH 공고 상세 원본 수집'],
        partiallyFailedSteps: [{ step: 'MAP_LH_ANNOUNCEMENTS', stepName: 'LH 공고 정제', report: { failedSourceCount: 2 } }] }),
      execution({ executionId: 'sync-0', type: 'ANNOUNCEMENT_SYNC', status: 'RUNNING', totalStepCount: 2,
        currentStepName: 'LH 공고 목록 수집' }),
    ])
    render(<MemoryRouter><PipelineHistory domain="announcement" compact /></MemoryRouter>)
    const [syncTrigger, runningTrigger] = await screen.findAllByRole('button', { name: /상세 보기/ })
    const history = screen.getByRole('region', { name: '실행 이력' })

    fireEvent.click(syncTrigger)
    const detail = screen.getByRole('region', { name: '실행 상세' })
    expect(history).not.toContainElement(detail)
    expect(history.compareDocumentPosition(detail) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(within(detail).getByRole('heading', { name: '실행 상세' })).toHaveFocus()
    expect(within(detail).getByRole('button', { name: 'LH 공고 목록 수집 성공' })).toBeVisible()
    expect(within(detail).getByRole('button', { name: 'LH 공고 정제 부분 실패' })).toBeVisible()
    expect(within(detail).queryByText(/선택 시점의 기록/)).not.toBeInTheDocument()
    fireEvent.click(within(detail).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('region', { name: '실행 상세' })).not.toBeInTheDocument()
    expect(syncTrigger).toHaveFocus()

    // 실행 중인 기록은 선택 시점 기록임을 알리고 이력에서 중지하지 않는다.
    fireEvent.click(runningTrigger)
    const running = screen.getByRole('region', { name: '실행 상세' })
    expect(within(running).getByText(/선택 시점의 기록/)).toBeVisible()
    expect(within(running).getByRole('list', { name: '실행 단계' }).children).toHaveLength(2)
    expect(within(running).getByRole('button', { name: 'LH 공고 목록 수집 진행' })).toBeVisible()
    expect(within(running).queryByRole('button', { name: /실행 중지/ })).not.toBeInTheDocument()
  })
  it('공고 이력에 함께 오는 GH 수집도 단계 결과와 공개 전 확인 안내를 보인다', async () => {
    getHistory.mockResolvedValue([execution({ executionId: 'gh-1', type: 'GH_ANNOUNCEMENT_COLLECTION', totalStepCount: 2,
      completedSteps: ['GH 공고 원천 수집', 'GH 공고 정제'] })])
    render(<MemoryRouter><PipelineHistory domain="announcement" compact /></MemoryRouter>)
    const row = (await screen.findByText('GH 공고 수집')).closest('tr')!
    fireEvent.click(within(row).getByRole('button', { name: /상세 보기/ }))
    const detail = screen.getByRole('region', { name: '실행 상세' })
    expect(within(detail).getByRole('button', { name: 'GH 공고 원천 수집 성공' })).toBeVisible()
    expect(within(detail).getByRole('button', { name: 'GH 공고 정제 성공' })).toBeVisible()
    expect(within(detail).getByRole('link', { name: '공개 전 확인이 필요한 공고 보기' }))
      .toHaveAttribute('href', '/admin/announcements?unconfirmed=true&provider=GH')
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
