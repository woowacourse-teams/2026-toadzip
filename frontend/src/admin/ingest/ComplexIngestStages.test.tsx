import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { DataPipelineControl } from './DataPipelineControl'
import { getDataPipelineStatus, getLocationPreparation, getInitialRegistration, refreshInitialRegistration, startDataPipeline, stopDataPipeline, type DataPipelineExecution, type DataPipelineType, type LocationSummaryImportReport, uploadLocationSummary } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(),
  getDataPipelineStatus: vi.fn(), startDataPipeline: vi.fn(), stopDataPipeline: vi.fn(), uploadLocationSummary: vi.fn(),
  getInitialRegistration: vi.fn(), refreshInitialRegistration: vi.fn(), getLocationPreparation: vi.fn(),
}))
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getLocationPreparation).mockResolvedValue({ counts: { READY: 0, INVALID_ADDRESS: 0, ADDRESS_NOT_FOUND: 0, COORDINATE_NOT_FOUND: 0, COORDINATE_CONVERSION_ERROR: 0 }, targets: [], totalElements: 0, page: 0, totalPages: 0 })
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type))
  localStorage.removeItem('toadzip:admin:initial-registration:page-size')
  vi.mocked(getInitialRegistration).mockResolvedValue({
    counts: { EXISTING: 0, LOCATION_REQUIRED: 2, INFORMATION_REQUIRED: 0, READY: 38, FAILED: 0, COMPLETED: 0 },
    targets: [{ sourceComplexIdentifier: '123:HAPPY_HOUSING', name: '등록 확인 단지', roadAddress: null,
      status: 'READY', detail: '등록 준비 완료', housingComplexId: null, checkedAt: '2026-11-04T00:00:00Z' }],
    totalElements: 40, page: 0, totalPages: 2,
  })
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
  expect(screen.queryByRole('button', { name: '단지 정제·저장 실행', hidden: true })).not.toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: '신규 단지 등록' })).not.toBeInTheDocument()
  expect(screen.getByText('등록 확인 단지')).not.toBeVisible()
  expect(screen.getAllByRole('button', { name: '좌표 데이터 업로드', hidden: true })).toHaveLength(1)
  expect(screen.queryByRole('button', { name: '단지 수집·정제' })).not.toBeInTheDocument()
  select('2 주소·좌표 준비')
  expect(within(screen.getByRole('tabpanel')).queryByRole('button', { name: /정제/ })).not.toBeInTheDocument()
  select('3 신규 단지 등록')
  expect(screen.queryByText(/현재 저장된 단지 원본 전체/)).not.toBeInTheDocument()
  expect(within(screen.getByRole('tabpanel')).getByRole('heading', { name: '신규 단지 등록' })).toBeVisible()
  expect(screen.getByText('등록 확인 단지')).toBeVisible()
  select('1 원천 수집')
  expect(screen.getByText('등록 확인 단지')).not.toBeVisible()
})
it('세 단계의 안내를 호버·포커스·클릭으로 열고 Escape나 영역 이탈로 닫으며 실행하지 않는다', async () => {
  await show()
  for (const [tab, stage, text] of [
    ['1 원천 수집', '단지 원천 수집', '마이홈 단지와 LH 임대 카탈로그를 수집해 원본을 저장합니다.'],
    ['2 주소·좌표 준비', '주소·좌표 준비', '위치정보요약DB ZIP으로 주소와 지도용 좌표를 확인하고, 보완할 단지는 주소 검색 또는 직접 입력으로 준비합니다.'],
    ['3 신규 단지 등록', '신규 단지 등록', '등록 가능한 단지 전체를 처리합니다. 목록 필터는 실행 범위를 제한하지 않습니다. 기존 단지와 관리자 보정값은 유지하며, 실패한 단지는 별도로 기록합니다. 화면을 이동해도 실행은 계속됩니다.'],
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
  select('3 신규 단지 등록')
  expect(screen.getByRole('button', { name: '대상 상태 확인' })).toBeDisabled()
  await act(async () => resolve(execution('COMPLEX_COLLECTION', { executionId: 'collect-1', status: 'RUNNING', currentStepName: '마이홈 단지 수집' })))
  select('1 원천 수집')
  expect(screen.getByRole('button', { name: '수집 중지' })).toBeVisible()
})
it('방향키·Home·End로 탭을 선택하고 포커스를 옮긴다', async () => {
  await show()
  const first = screen.getByRole('tab', { name: '1 원천 수집' })
  first.focus()
  fireEvent.keyDown(first, { key: 'ArrowRight' })
  const second = screen.getByRole('tab', { name: '2 주소·좌표 준비' })
  expect(second).toHaveFocus()
  expect(second).toHaveAttribute('aria-selected', 'true')
  fireEvent.keyDown(second, { key: 'End' })
  const third = screen.getByRole('tab', { name: '3 신규 단지 등록' })
  expect(third).toHaveFocus()
  fireEvent.keyDown(third, { key: 'ArrowRight' })
  expect(first).toHaveFocus()
  fireEvent.keyDown(first, { key: 'ArrowLeft' })
  expect(third).toHaveFocus()
  fireEvent.keyDown(third, { key: 'Home' })
  expect(first).toHaveFocus()
})
it('탭을 바꿔도 선택한 ZIP과 업로드 결과를 유지한다', async () => {
  let resolve: (value: LocationSummaryImportReport) => void = () => {}
  vi.mocked(uploadLocationSummary).mockReturnValue(new Promise(complete => { resolve = complete }))
  await show()
  select('2 주소·좌표 준비')
  const input = screen.getByLabelText('위치정보요약DB 월 전체분 ZIP')
  const zip = new File(['fixture'], 'summary.zip', { type: 'application/zip' })
  fireEvent.change(input, { target: { files: [zip] } })
  select('1 원천 수집')
  select('2 주소·좌표 준비')
  fireEvent.click(screen.getByRole('button', { name: '좌표 데이터 업로드' }))
  select('1 원천 수집')
  expect(screen.getByRole('button', { name: '단지 원천 수집 실행' })).toBeDisabled()
  expect(screen.getByRole('tab', { name: '2 주소·좌표 준비' })).toHaveAccessibleDescription('진행 중')
  select('3 신규 단지 등록')
  expect(screen.getByRole('button', { name: '대상 상태 확인' })).toBeDisabled()
  select('2 주소·좌표 준비')
  expect(screen.getByText(/좌표 파일을 처리하고 있습니다/)).toBeVisible()
  await act(async () => resolve({ sourceFileName: 'summary.zip', textFileCount: 1, scannedRowCount: 4,
    preparedRoadAddressCount: 1, storedLocationCount: 2, replacedRowCount: 0, invalidatedMappingCandidateCount: 0, provinceCodes: ['11'],
    targetRoadAddressCount: 3, matchedRoadAddressCount: 2, unmatchedRoadAddressCount: 1 }))
  await screen.findByRole('region', { name: '좌표 적재 결과' })
  expect(uploadLocationSummary).toHaveBeenCalledExactlyOnceWith(zip)
  expect(startDataPipeline).not.toHaveBeenCalled()
  select('3 신규 단지 등록')
  select('2 주소·좌표 준비')
  expect(screen.getByRole('region', { name: '좌표 적재 결과' })).toBeVisible()
})
it.each(['complex-registration', 'complex-refinement'])('직접 연결된 %s는 단지 등록을 열고 쓰기 작업을 자동 실행하지 않는다', async hash => {
  render(<MemoryRouter initialEntries={[`/admin/ingest/complexes#${hash}`]}><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  await waitFor(() => expect(screen.getByRole('button', { name: '단지 원천 수집 실행', hidden: true })).toBeEnabled())
  expect(screen.getByRole('tab', { name: '3 신규 단지 등록' })).toHaveAttribute('aria-selected', 'true')
  expect(screen.getByRole('heading', { name: '신규 단지 등록' })).toBeVisible()
  expect(screen.queryByRole('button', { name: '단지 정제·저장 실행' })).not.toBeInTheDocument()
  expect(startDataPipeline).not.toHaveBeenCalled()
  expect(refreshInitialRegistration).not.toHaveBeenCalled()
})
it('재진입 시 진행 중 수집을 복구하고 과거 정제 결과를 등록 현황에 섞지 않는다', async () => {
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_COLLECTION'
    ? { executionId: 'collect-1', status: 'RUNNING', currentStepName: '마이홈 단지 수집', workProgress: {
      label: '마이홈 지역', unit: '개 지역', completedCount: 20, totalCount: 250, startedAt: '2026-10-22T00:00:00Z', updatedAt: '2026-10-22T00:01:00Z',
    } } : type === 'COMPLEX_REFINEMENT' ? { executionId: 'refine-1', status: 'COMPLETED' } : {}))
  render(<MemoryRouter><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  await screen.findByRole('button', { name: '수집 중지' })
  assertStages()
  expect(screen.getByText(/20 \/ 250 개 지역/)).toBeVisible()
  select('3 신규 단지 등록')
  expect(within(screen.getByRole('tabpanel')).getByText('등록 확인 단지')).toBeVisible()
  expect(within(screen.getByRole('tabpanel')).queryByRole('group', { name: '실행 상태' })).not.toBeInTheDocument()
  select('2 주소·좌표 준비')
  expect(screen.getByLabelText('위치정보요약DB 월 전체분 ZIP')).toBeDisabled()
})
it('지난 정제 결과와 지원 종료된 실행 버튼을 단지 등록에 표시하지 않는다', async () => {
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_REFINEMENT'
    ? { executionId: 'refine-1', status: 'COMPLETED_WARNINGS', startedAt: '2026-10-21T00:00:00Z',
      completedStepResults: [{ step: 'MAP_MYHOME_COMPLEXES', stepName: '마이홈 단지 정제', report: { createdComplexCount: 4, updatedComplexCount: 7, failedSourceCount: 2 } }] }
    : {}))
  await show()
  select('3 신규 단지 등록')
  expect(screen.queryByRole('button', { name: '단지 정제·저장 실행' })).not.toBeInTheDocument()
  expect(startDataPipeline).not.toHaveBeenCalled()
  expect(screen.queryByText('신규 단지')).not.toBeInTheDocument()
  expect(screen.queryByText('갱신 단지')).not.toBeInTheDocument()
  expect(screen.getByRole('heading', { name: /등록 대상/ })).toBeVisible()
})
it('시작 응답 유실은 서버 상태로 복구하며 중지 요청도 해당 실행에만 보낸다', async () => {
  const view = await show()
  vi.mocked(startDataPipeline).mockRejectedValue(new Error('연결 실패'))
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_COLLECTION'
    ? { executionId: 'collect-1', status: 'RUNNING', currentStepName: '마이홈 단지 수집' } : {}))
  vi.mocked(stopDataPipeline).mockResolvedValue(execution('COMPLEX_COLLECTION', { executionId: 'collect-1', status: 'STOPPED' }))
  fireEvent.click(screen.getByRole('button', { name: '단지 원천 수집 실행' }))
  fireEvent.click(await screen.findByRole('button', { name: '수집 중지' }))
  await screen.findByText('중지됨')
  expect(stopDataPipeline).toHaveBeenCalledExactlyOnceWith('collect-1')
  view.unmount()
})
it('진행 중인 이전 통합 정제는 별도 안내에서 복구하고 모든 탭에서 중지할 수 있다', async () => {
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_SYNC'
    ? { executionId: 'legacy-sync', status: 'RUNNING', currentStepName: '마이홈 단지 정제', totalStepCount: 4,
      completedSteps: ['마이홈 단지 수집', 'LH 임대 카탈로그 수집'] } : {}))
  render(<MemoryRouter><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  const legacy = await screen.findByRole('region', { name: '이전 정제 실행' })
  expect(within(legacy).getByRole('group', { name: '실행 상태' })).toHaveTextContent('진행 중')
  expect(within(screen.getByRole('tab', { name: '3 신규 단지 등록' })).queryByText('진행 중')).not.toBeInTheDocument()
  expect(within(screen.getByRole('tab', { name: '1 원천 수집' })).queryByText('진행 중')).not.toBeInTheDocument()
  select('3 신규 단지 등록')
  await screen.findByRole('button', { name: '정제 중지' })
  expect(screen.getAllByRole('button', { name: '정제 중지' })).toHaveLength(1)
  select('1 원천 수집')
  expect(screen.getByRole('button', { name: '정제 중지' })).toBeVisible()
  assertStages()
})
it('새 실행을 접수할 때 과거 통합 실행의 결과를 이번 결과로 표시하지 않는다', async () => {
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_SYNC'
    ? { executionId: 'old-sync', status: 'COMPLETED', startedAt: '2026-10-21T00:00:00Z', totalStepCount: 4,
      completedStepResults: [{ step: 'COLLECT_MYHOME_COMPLEXES', stepName: '마이홈 단지 수집', report: { storedRowCount: 999 } }] } : {}))
  let resolve: (value: DataPipelineExecution) => void = () => {}
  vi.mocked(startDataPipeline).mockReturnValue(new Promise(complete => { resolve = complete }))
  await show()
  const collection = screen.getByRole('tabpanel')
  expect(within(collection).getByText('999')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '단지 원천 수집 실행' }))
  expect(within(collection).queryByText('999')).not.toBeInTheDocument()
  expect(within(collection).getByText(/실행 접수를 확인/)).toBeVisible()
  expect(within(collection).queryByRole('group', { name: '실행 상태' })).not.toBeInTheDocument()
  await act(async () => resolve(execution('COMPLEX_COLLECTION', { executionId: 'new-collect', status: 'COMPLETED', startedAt: '2026-10-22T00:00:00Z' })))
})


it('단계 탭을 오가도 등록 상태 필터·표시 건수·페이지를 유지한다', async () => {
  await show()
  select('3 신규 단지 등록')
  fireEvent.change(screen.getByLabelText('진행 상태'), { target: { value: 'READY' } })
  fireEvent.change(screen.getByLabelText('표시 건수'), { target: { value: '10' } })
  await waitFor(() => expect(getInitialRegistration).toHaveBeenLastCalledWith('READY', 0, 10))
  fireEvent.click(screen.getByRole('button', { name: '다음 대상' }))
  await waitFor(() => expect(getInitialRegistration).toHaveBeenLastCalledWith('READY', 1, 10))
  select('2 주소·좌표 준비')
  expect(screen.queryByRole('heading', { name: '신규 단지 등록' })).not.toBeInTheDocument()
  select('3 신규 단지 등록')
  expect(screen.getByLabelText('진행 상태')).toHaveValue('READY')
  expect(screen.getByLabelText('표시 건수')).toHaveValue('10')
  expect(screen.getByRole('navigation', { name: '등록 대상 페이지' })).toHaveTextContent('2 / 2')
  expect(getInitialRegistration).toHaveBeenLastCalledWith('READY', 1, 10)
})

it.each([
  ['COMPLEX_COLLECTION', '1 원천 수집', '단지 원천 수집', '수집 중지', '마이홈 단지 수집', true],
  // 지원 종료 전에 시작한 정제는 멈출 수 있지만 다시 시작할 수는 없다.
  ['COMPLEX_REFINEMENT', '3 신규 단지 등록', '단지 정제·저장', '정제 중지', '마이홈 단지 정제', false],
] as const)('%s의 중지 버튼은 실행 버튼 옆에 하나만 두고 요청 중에는 다시 누를 수 없다', async (type, tab, stage, label, step, restartable) => {
  const running = execution(type, { executionId: 'active-run', status: 'RUNNING', currentStepName: step })
  vi.mocked(getDataPipelineStatus).mockImplementation(async value => value === type ? running : execution(value))
  let finishStop: (value: DataPipelineExecution) => void = () => {}
  vi.mocked(stopDataPipeline).mockReturnValue(new Promise(resolve => { finishStop = resolve }))
  const view = render(<MemoryRouter><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  await screen.findByRole('button', { name: label })
  select(tab)
  const actions = restartable ? screen.getByRole('group', { name: `${stage} 실행 제어` })
    : screen.getByRole('region', { name: '이전 정제 실행' })
  const start = within(actions).queryByRole('button', { name: `${stage} 실행` })
  const stop = within(actions).getByRole('button', { name: label })
  if (restartable) {
    expect(start).toBeDisabled()
    expect(start?.nextElementSibling).toBe(stop)
  } else expect(start).not.toBeInTheDocument()
  expect(screen.getAllByRole('button', { name: label })).toHaveLength(1)
  fireEvent.click(stop)
  expect(within(actions).getByRole('button', { name: '중지 요청 중…' })).toBeDisabled()
  fireEvent.click(stop)
  expect(stopDataPipeline).toHaveBeenCalledExactlyOnceWith('active-run')
  await act(async () => finishStop({ ...running, status: 'STOPPED', stopRequested: true }))
  expect(within(actions).queryByRole('button', { name: label })).not.toBeInTheDocument()
  if (restartable) expect(start).toBeEnabled()
  else expect(screen.queryByRole('region', { name: '이전 정제 실행' })).not.toBeInTheDocument()
  view.unmount()
})


it('등록을 즉시 접수하고 중복 실행을 막으며 재방문해도 진행과 중지 요청을 복원한다', async () => {
  const view = await show()
  select('3 신규 단지 등록')
  const running = execution('COMPLEX_REGISTRATION', { executionId: 'registration-1', status: 'RUNNING' })
  vi.mocked(startDataPipeline).mockResolvedValue(running)
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => type === 'COMPLEX_REGISTRATION' ? running : execution(type))
  const register = await screen.findByRole('button', { name: '준비된 신규 단지 등록' })
  fireEvent.click(register); fireEvent.click(register)
  await waitFor(() => expect(startDataPipeline).toHaveBeenCalledExactlyOnceWith('COMPLEX_REGISTRATION'))
  expect(register).toBeDisabled()
  expect(screen.getByText('등록 준비 중…')).toBeVisible()
  vi.mocked(stopDataPipeline).mockResolvedValue({ ...running, stopRequested: true })
  fireEvent.click(screen.getByRole('button', { name: '등록 중지' }))
  await waitFor(() => expect(stopDataPipeline).toHaveBeenCalledWith('registration-1'))
  expect(await screen.findByRole('button', { name: '중지 요청 중…' })).toBeDisabled()
  view.unmount()
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => type === 'COMPLEX_REGISTRATION'
    ? { ...running, stopRequested: true } : execution(type))
  render(<MemoryRouter initialEntries={['/#complex-registration']}><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  expect(await screen.findByText(/이미 저장한 데이터는 유지됩니다/)).toBeVisible()
  expect(screen.getByRole('button', { name: '준비된 신규 단지 등록' })).toBeDisabled()
})

it('등록 완료 뒤 다시 열면 간결한 완료 안내를 복원한다', async () => {
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_REGISTRATION' ? {
    executionId: 'registration-2', status: 'COMPLETED', completedStepResults: [{ step: 'REGISTER_MYHOME_COMPLEXES',
      stepName: '준비된 신규 단지 등록', report: { targetCount: 2, uniqueAddressCount: 1,
        counts: { EXISTING: 0, LOCATION_REQUIRED: 0, INFORMATION_REQUIRED: 0, READY: 0, FAILED: 0, COMPLETED: 2 } } }],
  } : {}))
  render(<MemoryRouter initialEntries={['/#complex-registration']}><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  expect(await screen.findByRole('status', { name: '등록 실행 상태' })).toHaveTextContent('등록이 완료되었습니다.')
  expect(screen.queryByText(/고유 원천 주소/)).not.toBeInTheDocument()
})
