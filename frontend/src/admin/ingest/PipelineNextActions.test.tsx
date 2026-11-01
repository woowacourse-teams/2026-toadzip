import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, it, vi } from 'vitest'
import type { DataPipelineExecution, DataPipelineType } from './api'
import { PipelineNextActions } from './PipelineNextActions'

function execution(type: DataPipelineType, status: DataPipelineExecution['status'],
  extra: Partial<DataPipelineExecution> = {}): DataPipelineExecution {
  return { executionId: 'run-1', type, status, currentStepName: null, currentStepIndex: 0,
    totalStepCount: 2, completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null, ...extra }
}

it.each([
  ['단지 수집 완료', execution('COMPLEX_COLLECTION', 'COMPLETED')],
  ['정제 전에 멈춘 단지 수집·정제', execution('COMPLEX_SYNC', 'FAILED', { currentStepIndex: 1 })],
])('%s 뒤에는 지원을 종료한 단지 정제를 권하지 않고 안내만 보인다', (_, run) => {
  const onRefine = vi.fn()
  render(<MemoryRouter><PipelineNextActions execution={run} onRefine={onRefine} /></MemoryRouter>)
  expect(screen.getByText('지원 종료: 단지·주택형은 공고와 PDF 보정으로만 바뀝니다.')).toBeVisible()
  expect(screen.queryByRole('button', { name: /정제 실행/ })).not.toBeInTheDocument()
  expect(screen.queryByText(/자동 정제가 실행되지 않았습니다/)).not.toBeInTheDocument()
})

it('공고 수집 뒤에는 저장된 원천으로 공고 정제를 계속 권한다', () => {
  render(<MemoryRouter><PipelineNextActions execution={execution('ANNOUNCEMENT_COLLECTION', 'COMPLETED')}
    onRefine={vi.fn()} /></MemoryRouter>)
  expect(screen.getByRole('button', { name: '저장된 원천으로 공고 정제 실행' })).toBeEnabled()
  expect(screen.queryByText(/지원 종료/)).not.toBeInTheDocument()
})
