import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { DataPipelineControl } from './DataPipelineControl'
import { getDataPipelineStatus, startDataPipeline, stopDataPipeline, type DataPipelineExecution, type DataPipelineType } from './api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(),
  getDataPipelineStatus: vi.fn(), startDataPipeline: vi.fn(), stopDataPipeline: vi.fn(),
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
  await waitFor(() => expect(screen.getByRole('button', { name: '단지 정제·저장 실행' })).toBeEnabled())
  return view
}
function assertStages() {
  for (const name of ['단지 원천 수집', '주소에 맞는 좌표 적재', '단지 정제·저장']) expect(screen.getByRole('region', { name })).toBeVisible()
}
it('세 단계와 각 기능의 실행 버튼 하나를 유지하고 통합 실행 진입점을 제거한다', async () => {
  await show()
  assertStages()
  expect(screen.getAllByRole('button', { name: '단지 원천 수집 실행' })).toHaveLength(1)
  expect(screen.getAllByRole('button', { name: '단지 정제·저장 실행' })).toHaveLength(1)
  expect(screen.getAllByRole('button', { name: '좌표 데이터 업로드' })).toHaveLength(1)
  expect(screen.queryByRole('button', { name: '단지 수집·정제' })).not.toBeInTheDocument()
  const upload = screen.getByRole('region', { name: '주소에 맞는 좌표 적재' })
  expect(within(upload).queryByRole('button', { name: /정제/ })).not.toBeInTheDocument()
  expect(screen.getByText(/현재 저장된 단지 원본 전체/)).toBeVisible()
})
it('키 안내를 클릭·키보드로 열고 수집 접수 후에도 세 단계를 유지하며 키를 비운다', async () => {
  let resolve: (value: DataPipelineExecution) => void = () => {}
  vi.mocked(startDataPipeline).mockReturnValue(new Promise(complete => { resolve = complete }))
  await show()
  const info = screen.getByRole('button', { name: '공공데이터포털 API 키 안내' })
  expect(info).toHaveAttribute('aria-expanded', 'false')
  fireEvent.click(info)
  expect(screen.getByText(/입력한 키는 이번 실행에만/)).toBeVisible()
  fireEvent.keyDown(info, { key: 'Escape' })
  expect(info).toHaveAttribute('aria-expanded', 'false')
  const input = screen.getByLabelText('공공데이터포털 API 키')
  fireEvent.change(input, { target: { value: ' fixture-key ' } })
  fireEvent.click(screen.getByRole('button', { name: '단지 원천 수집 실행' }))
  assertStages()
  expect(input).toHaveValue('')
  expect(startDataPipeline).toHaveBeenCalledExactlyOnceWith('COMPLEX_COLLECTION', 'fixture-key')
  expect(screen.getByRole('button', { name: '단지 정제·저장 실행' })).toBeDisabled()
  expect(screen.getByText(/실행 접수를 확인/)).toBeVisible()
  await act(async () => resolve(execution('COMPLEX_COLLECTION', { executionId: 'collect-1', status: 'RUNNING', currentStepName: '마이홈 단지 수집' })))
  assertStages()
  expect(screen.getByRole('button', { name: '실행 중지' })).toBeVisible()
})
it('재진입 시 진행 중 수집과 마지막 정제 결과를 복구하고 단위를 유지한다', async () => {
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_COLLECTION'
    ? { executionId: 'collect-1', status: 'RUNNING', currentStepName: '마이홈 단지 수집', workProgress: {
      label: '마이홈 지역', unit: '개 지역', completedCount: 20, totalCount: 250, startedAt: '2026-10-22T00:00:00Z', updatedAt: '2026-10-22T00:01:00Z',
    } } : type === 'COMPLEX_REFINEMENT' ? { executionId: 'refine-1', status: 'COMPLETED' } : {}))
  render(<MemoryRouter><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  await screen.findByRole('button', { name: '실행 중지' })
  assertStages()
  expect(screen.getByText(/20 \/ 250 개 지역/)).toBeVisible()
  expect(within(screen.getByRole('region', { name: '단지 정제·저장' })).getByText('완료')).toBeVisible()
  expect(screen.getByLabelText('위치정보요약DB 월 전체분 ZIP')).toBeDisabled()
})
it('정제만 실행하고 그 결과를 같은 영역에 남긴다', async () => {
  vi.mocked(startDataPipeline).mockResolvedValue(execution('COMPLEX_REFINEMENT', { executionId: 'refine-1', status: 'COMPLETED_WARNINGS',
    completedStepResults: [{ step: 'MAP_MYHOME_COMPLEXES', stepName: '마이홈 단지 정제', report: { createdComplexCount: 4, updatedComplexCount: 7, failedSourceCount: 2 } }],
  }))
  await show()
  fireEvent.click(screen.getByRole('button', { name: '단지 정제·저장 실행' }))
  await screen.findByText('일부 실패')
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
  fireEvent.click(screen.getByRole('button', { name: '단지 정제·저장 실행' }))
  fireEvent.click(await screen.findByRole('button', { name: '실행 중지' }))
  await screen.findByText('중지됨')
  expect(stopDataPipeline).toHaveBeenCalledExactlyOnceWith('refine-1')
  view.unmount()
})
it('기존 통합 실행도 복구하되 중지 버튼은 현재 처리 중인 단계에 하나만 둔다', async () => {
  vi.mocked(getDataPipelineStatus).mockImplementation(async type => execution(type, type === 'COMPLEX_SYNC'
    ? { executionId: 'legacy-sync', status: 'RUNNING', currentStepName: '마이홈 단지 정제', totalStepCount: 4,
      completedSteps: ['마이홈 단지 수집', 'LH 임대 카탈로그 수집'] } : {}))
  render(<MemoryRouter><DataPipelineControl domain="complex" compact /></MemoryRouter>)
  await screen.findByRole('button', { name: '실행 중지' })
  expect(screen.getAllByRole('button', { name: '실행 중지' })).toHaveLength(1)
  expect(within(screen.getByRole('region', { name: '단지 원천 수집' })).queryByRole('button', { name: '실행 중지' })).not.toBeInTheDocument()
  assertStages()
})
