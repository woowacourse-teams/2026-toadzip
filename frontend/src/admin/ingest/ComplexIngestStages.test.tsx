import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { DataPipelineControl } from './DataPipelineControl'
import { getDataPipelineStatus, startDataPipeline, stopDataPipeline, type DataPipelineExecution, type DataPipelineType, type LocationSummaryImportReport, uploadLocationSummary } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(),
  getDataPipelineStatus: vi.fn(), startDataPipeline: vi.fn(), stopDataPipeline: vi.fn(), uploadLocationSummary: vi.fn(),
}))
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type))
})
function execution(type: DataPipelineType, extra: Partial<DataPipelineExecution> = {}): DataPipelineExecution {
  return { executionId: null, type, status: 'IDLE', currentStepName: null, currentStepIndex: 0,
    totalStepCount: 2, completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null, ...extra }
}
async function show() {
  const view = render(<MemoryRouter><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  await waitFor(() => expect(screen.getByRole('button', { name: '단지 원천 수집 실행' })).toBeEnabled())
  return view
}
function assertStages() {
  expect(screen.getAllByRole('tab')).toHaveLength(3)
  expect(screen.getAllByRole('tabpanel', { hidden: true })).toHaveLength(3)
  expect(screen.getAllByRole('tabpanel')).toHaveLength(1)
}
function select(name: string) {
  fireEvent.click(screen.getByRole('tab', { name }))
}
it('마지막 실행 상태와 종료 시각을 단계 제목 옆에 한 번만 묶어 표시한다', async () => {
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_COLLECTION'
    ? { executionId: 'collect-1', status: 'COMPLETED', startedAt: '2026-10-22T00:00:00Z', finishedAt: '2026-10-22T01:06:00Z' } : {}))
  await show()
  const panel = screen.getByRole('tabpanel')
  const heading = within(panel).getByRole('heading', { name: '단지 원천 수집' }).closest('header')!
  const summary = within(heading).getByRole('group', { name: '실행 상태' })
  expect(summary).toHaveTextContent('완료')
  expect(summary).toHaveTextContent('종료 시각')
  expect(summary.querySelector('time')).toHaveAttribute('dateTime', '2026-10-22T01:06:00Z')
  expect(within(panel).getAllByRole('group', { name: '실행 상태' })).toHaveLength(1)
})
it('가로 탭에 선택한 단계만 표시하고 각 실행 버튼은 한 곳에 둔다', async () => {
  await show()
  assertStages()
  expect(screen.getByRole('tab', { name: '1 원천 수집' })).toHaveAttribute('aria-selected', 'true')
  expect(screen.getAllByRole('button', { name: '단지 원천 수집 실행', hidden: true })).toHaveLength(1)
  expect(screen.getAllByRole('button', { name: '단지 정제·저장 실행', hidden: true })).toHaveLength(1)
  expect(screen.getAllByRole('button', { name: '좌표 데이터 업로드', hidden: true })).toHaveLength(1)
  expect(screen.queryByRole('button', { name: '단지 수집·정제' })).not.toBeInTheDocument()
  select('2 좌표 적재')
  expect(within(screen.getByRole('tabpanel')).queryByRole('button', { name: /정제/ })).not.toBeInTheDocument()
  select('3 정제·저장')
  expect(screen.queryByText(/현재 저장된 단지 원본 전체/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '단지 정제·저장 안내' }))
  expect(screen.getByRole('tooltip')).toHaveTextContent('현재 저장된 단지 원본 전체를 단지·주택형으로 정제해 저장합니다.')
})
it('세 단계의 안내를 호버·포커스·클릭으로 열고 Escape나 영역 이탈로 닫으며 실행하지 않는다', async () => {
  await show()
  for (const [tab, stage, text] of [
    ['1 원천 수집', '단지 원천 수집', '마이홈 단지와 LH 임대 카탈로그를 수집해 원본을 저장합니다.'],
    ['2 좌표 적재', '주소에 맞는 좌표 적재', '위치정보요약DB 월 전체분 ZIP을 업로드해 저장된 단지 원본의 주소에 맞는 좌표를 준비합니다.'],
    ['3 정제·저장', '단지 정제·저장', '현재 저장된 단지 원본 전체를 단지·주택형으로 정제해 저장합니다.'],
  ]) {
    select(tab)
    const button = screen.getByRole('button', { name: `${stage} 안내` })
    const wrapper = button.parentElement!
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    fireEvent.mouseEnter(wrapper)
    expect(screen.getByRole('tooltip')).toHaveTextContent(text)
    expect(button).toHaveAttribute('aria-describedby', screen.getByRole('tooltip').id)
    fireEvent.mouseLeave(wrapper)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    act(() => button.focus())
    expect(screen.getByRole('tooltip')).toHaveTextContent(text)
    fireEvent.keyDown(button, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    fireEvent.click(button)
    expect(screen.getByRole('tooltip')).toBeVisible()
    act(() => button.blur())
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  }
  expect(startDataPipeline).not.toHaveBeenCalled()
  expect(uploadLocationSummary).not.toHaveBeenCalled()
})
it('키를 입력받지 않고 서버 설정으로 수집을 접수하며 탭을 유지한다', async () => {
  let resolve: (value: DataPipelineExecution) => void = () => {}
  vi.mocked(startDataPipeline).mockReturnValue(new Promise(complete => { resolve = complete }))
  await show()
  expect(screen.queryByLabelText('공공데이터포털 API 키')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /API 키/ })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '단지 원천 수집 실행' }))
  assertStages()
  expect(startDataPipeline).toHaveBeenCalledExactlyOnceWith('COMPLEX_COLLECTION')
  expect(screen.getByText(/실행 접수를 확인/)).toBeVisible()
  select('3 정제·저장')
  expect(screen.getByRole('button', { name: '단지 정제·저장 실행' })).toBeDisabled()
  await act(async () => resolve(execution('COMPLEX_COLLECTION', { executionId: 'collect-1', status: 'RUNNING', currentStepName: '마이홈 단지 수집' })))
  select('1 원천 수집')
  expect(screen.getByRole('button', { name: '수집 중지' })).toBeVisible()
})
it('방향키·Home·End로 탭을 선택하고 포커스를 옮긴다', async () => {
  await show()
  const first = screen.getByRole('tab', { name: '1 원천 수집' })
  first.focus()
  fireEvent.keyDown(first, { key: 'ArrowRight' })
  const second = screen.getByRole('tab', { name: '2 좌표 적재' })
  expect(second).toHaveFocus()
  expect(second).toHaveAttribute('aria-selected', 'true')
  fireEvent.keyDown(second, { key: 'End' })
  const third = screen.getByRole('tab', { name: '3 정제·저장' })
  expect(third).toHaveFocus()
  fireEvent.keyDown(third, { key: 'ArrowRight' })
  expect(first).toHaveFocus()
  fireEvent.keyDown(first, { key: 'ArrowLeft' })
  expect(third).toHaveFocus()
  fireEvent.keyDown(third, { key: 'Home' })
  expect(first).toHaveFocus()
})
it('탭을 바꿔도 선택한 ZIP과 업로드 결과를 유지하며 안내로 3단계를 연다', async () => {
  let resolve: (value: LocationSummaryImportReport) => void = () => {}
  vi.mocked(uploadLocationSummary).mockReturnValue(new Promise(complete => { resolve = complete }))
  await show()
  select('2 좌표 적재')
  const input = screen.getByLabelText('위치정보요약DB 월 전체분 ZIP')
  const zip = new File(['fixture'], 'summary.zip', { type: 'application/zip' })
  fireEvent.change(input, { target: { files: [zip] } })
  select('1 원천 수집')
  select('2 좌표 적재')
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
  select('1 원천 수집')
  expect(screen.getByRole('button', { name: '단지 원천 수집 실행' })).toBeDisabled()
  expect(screen.getByRole('tab', { name: '2 좌표 적재' })).toHaveAccessibleDescription('진행 중')
  select('3 정제·저장')
  expect(screen.getByRole('button', { name: '단지 정제·저장 실행' })).toBeDisabled()
  select('2 좌표 적재')
  expect(screen.getByText(/좌표 파일을 처리하고 있습니다/)).toBeVisible()
  await act(async () => resolve({ sourceFileName: 'summary.zip', textFileCount: 1, scannedRowCount: 4,
    storedLocationCount: 2, replacedRowCount: 0, invalidatedMappingCandidateCount: 0, provinceCodes: ['11'],
    targetRoadAddressCount: 3, matchedRoadAddressCount: 2, unmatchedRoadAddressCount: 1 }))
  await screen.findByRole('region', { name: '좌표 적재 결과' })
  expect(uploadLocationSummary).toHaveBeenCalledExactlyOnceWith(zip)
  expect(startDataPipeline).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('link', { name: '3단계에서 단지 정제·저장' }))
  expect(screen.getByRole('tab', { name: '3 정제·저장' })).toHaveAttribute('aria-selected', 'true')
  select('2 좌표 적재')
  expect(screen.getByRole('region', { name: '좌표 적재 결과' })).toBeVisible()
})
it('직접 연결된 3단계를 열되 정제를 자동 실행하지 않는다', async () => {
  render(<MemoryRouter initialEntries={['/admin/ingest/complexes#complex-refinement']}><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  await waitFor(() => expect(screen.getByRole('button', { name: '단지 정제·저장 실행' })).toBeEnabled())
  expect(screen.getByRole('tab', { name: '3 정제·저장' })).toHaveAttribute('aria-selected', 'true')
  expect(startDataPipeline).not.toHaveBeenCalled()
})
it('재진입 시 진행 중 수집과 마지막 정제 결과를 복구하고 단위를 유지한다', async () => {
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_COLLECTION'
    ? { executionId: 'collect-1', status: 'RUNNING', currentStepName: '마이홈 단지 수집', workProgress: {
      label: '마이홈 지역', unit: '개 지역', completedCount: 20, totalCount: 250, startedAt: '2026-10-22T00:00:00Z', updatedAt: '2026-10-22T00:01:00Z',
    } } : type === 'COMPLEX_REFINEMENT' ? { executionId: 'refine-1', status: 'COMPLETED' } : {}))
  render(<MemoryRouter><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  await screen.findByRole('button', { name: '수집 중지' })
  assertStages()
  expect(screen.getByText(/20 \/ 250 개 지역/)).toBeVisible()
  select('3 정제·저장')
  expect(within(screen.getByRole('tabpanel')).getByText('완료')).toBeVisible()
  select('2 좌표 적재')
  expect(screen.getByLabelText('위치정보요약DB 월 전체분 ZIP')).toBeDisabled()
})
it('정제만 실행하고 그 결과를 같은 영역에 남긴다', async () => {
  vi.mocked(startDataPipeline).mockResolvedValue(execution('COMPLEX_REFINEMENT', { executionId: 'refine-1', status: 'COMPLETED_WARNINGS',
    completedStepResults: [{ step: 'MAP_MYHOME_COMPLEXES', stepName: '마이홈 단지 정제', report: { createdComplexCount: 4, updatedComplexCount: 7, failedSourceCount: 2 } }],
  }))
  await show()
  select('3 정제·저장')
  fireEvent.click(screen.getByRole('button', { name: '단지 정제·저장 실행' }))
  await waitFor(() => expect(within(screen.getByRole('group', { name: '실행 상태' })).getByText('일부 실패')).toBeVisible())
  assertStages()
  expect(startDataPipeline).toHaveBeenCalledExactlyOnceWith('COMPLEX_REFINEMENT')
  expect(screen.getByText('신규 단지').nextSibling).toHaveTextContent('4')
  expect(screen.getByText('갱신 단지').nextSibling).toHaveTextContent('7')
  expect(screen.getByRole('button', { name: '결과 오류 조회' })).toBeVisible()
})
it('시작 응답 유실은 서버 상태로 복구하며 중지 요청도 해당 실행에만 보낸다', async () => {
  const view = await show()
  vi.mocked(startDataPipeline).mockRejectedValue(new Error('연결 실패'))
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_REFINEMENT'
    ? { executionId: 'refine-1', status: 'RUNNING', currentStepName: '마이홈 단지 정제' } : {}))
  vi.mocked(stopDataPipeline).mockResolvedValue(execution('COMPLEX_REFINEMENT', { executionId: 'refine-1', status: 'STOPPED' }))
  select('3 정제·저장')
  fireEvent.click(screen.getByRole('button', { name: '단지 정제·저장 실행' }))
  fireEvent.click(await screen.findByRole('button', { name: '정제 중지' }))
  await screen.findByText('중지됨')
  expect(stopDataPipeline).toHaveBeenCalledExactlyOnceWith('refine-1')
  view.unmount()
})
it('기존 통합 실행도 복구하되 중지 버튼은 현재 처리 중인 단계에 하나만 둔다', async () => {
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_SYNC'
    ? { executionId: 'legacy-sync', status: 'RUNNING', currentStepName: '마이홈 단지 정제', totalStepCount: 4,
      completedSteps: ['마이홈 단지 수집', 'LH 임대 카탈로그 수집'] } : {}))
  render(<MemoryRouter><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  await waitFor(() => expect(within(screen.getByRole('tab', { name: '3 정제·저장' })).getByText('진행 중')).toBeVisible())
  expect(within(screen.getByRole('tab', { name: '3 정제·저장' })).getByText('진행 중')).toBeVisible()
  expect(within(screen.getByRole('tab', { name: '1 원천 수집' })).queryByText('진행 중')).not.toBeInTheDocument()
  select('3 정제·저장')
  await screen.findByRole('button', { name: '정제 중지' })
  expect(screen.getAllByRole('button', { name: '정제 중지' })).toHaveLength(1)
  select('1 원천 수집')
  expect(screen.queryByRole('button', { name: '정제 중지' })).not.toBeInTheDocument()
  assertStages()
})
it('새 실행을 접수할 때 과거 통합 실행의 결과를 이번 결과로 표시하지 않는다', async () => {
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_SYNC'
    ? { executionId: 'old-sync', status: 'COMPLETED', startedAt: '2026-10-21T00:00:00Z', totalStepCount: 4,
      completedStepResults: [{ step: 'MAP_MYHOME_COMPLEXES', stepName: '마이홈 단지 정제', report: { createdComplexCount: 999 } }] } : {}))
  let resolve: (value: DataPipelineExecution) => void = () => {}
  vi.mocked(startDataPipeline).mockReturnValue(new Promise(complete => { resolve = complete }))
  await show()
  select('3 정제·저장')
  const refinement = screen.getByRole('tabpanel')
  expect(within(refinement).getByText('999')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '단지 정제·저장 실행' }))
  expect(within(refinement).queryByText('999')).not.toBeInTheDocument()
  expect(within(refinement).getByText(/실행 접수를 확인/)).toBeVisible()
  expect(within(refinement).queryByRole('group', { name: '실행 상태' })).not.toBeInTheDocument()
  await act(async () => resolve(execution('COMPLEX_REFINEMENT', { executionId: 'new-refine', status: 'COMPLETED', startedAt: '2026-10-22T00:00:00Z' })))
})


it.each([
  ['COMPLEX_COLLECTION', '1 원천 수집', '단지 원천 수집', '수집 중지', '마이홈 단지 수집'],
  ['COMPLEX_REFINEMENT', '3 정제·저장', '단지 정제·저장', '정제 중지', '마이홈 단지 정제'],
] as const)('%s의 중지 버튼은 실행 버튼 옆에 하나만 두고 요청 중에는 다시 누를 수 없다', async (type, tab, stage, label, step) => {
  const running = execution(type, { executionId: 'active-run', status: 'RUNNING', currentStepName: step })
  vi.mocked(getDataPipelineStatus).mockImplementation(async value => value === type ? running : execution(value))
  let finishStop: (value: DataPipelineExecution) => void = () => {}
  vi.mocked(stopDataPipeline).mockReturnValue(new Promise(resolve => { finishStop = resolve }))
  const view = render(<MemoryRouter><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  await waitFor(() => expect(within(screen.getByRole('tab', { name: tab })).getByText('진행 중')).toBeVisible())
  select(tab)
  const actions = screen.getByRole('group', { name: `${stage} 실행 제어` })
  const start = within(actions).getByRole('button', { name: `${stage} 실행` })
  const stop = within(actions).getByRole('button', { name: label })
  expect(start).toBeDisabled()
  expect(start.nextElementSibling).toBe(stop)
  expect(screen.getAllByRole('button', { name: label })).toHaveLength(1)
  fireEvent.click(stop)
  expect(within(actions).getByRole('button', { name: '중지 요청 중…' })).toBeDisabled()
  fireEvent.click(stop)
  expect(stopDataPipeline).toHaveBeenCalledExactlyOnceWith('active-run')
  await act(async () => finishStop({ ...running, status: 'STOPPED', stopRequested: true }))
  expect(within(actions).queryByRole('button', { name: label })).not.toBeInTheDocument()
  expect(start).toBeEnabled()
  view.unmount()
})
