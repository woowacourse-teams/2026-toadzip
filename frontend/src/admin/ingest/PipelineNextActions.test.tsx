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

it.each([
  ['SH_ANNOUNCEMENT_COLLECTION', 'SH'],
  ['GH_ANNOUNCEMENT_COLLECTION', 'GH'],
] as const)('%s가 끝나면 공개 전 확인이 필요한 %s 공고 목록으로 안내한다', (type, provider) => {
  render(<MemoryRouter><PipelineNextActions execution={execution(type, 'COMPLETED')} /></MemoryRouter>)
  expect(screen.getByRole('link', { name: '공개 전 확인이 필요한 공고 보기' }))
    .toHaveAttribute('href', `/admin/announcements?unconfirmed=true&provider=${provider}`)
  expect(screen.getByText(/PDF 보정에서 공급행을 단지에 연결하고 보정을 적용하면 곧바로 사용자에게 보입니다/)).toBeVisible()
})

it.each(['IDLE', 'RUNNING', 'FAILED', 'STOPPED'] as const)('SH 수집이 %s이면 공개 전 확인 안내를 보이지 않는다', status => {
  render(<MemoryRouter><PipelineNextActions execution={execution('SH_ANNOUNCEMENT_COLLECTION', status)} /></MemoryRouter>)
  expect(screen.queryByRole('link', { name: '공개 전 확인이 필요한 공고 보기' })).not.toBeInTheDocument()
})

it('호출 제한으로 일부만 받았어도 정제한 공고를 안내하고 이어서 다시 실행하라고 알린다', () => {
  render(<MemoryRouter><PipelineNextActions execution={execution('SH_ANNOUNCEMENT_COLLECTION', 'COMPLETED_WITH_SKIPS')} /></MemoryRouter>)
  expect(screen.getByText(/이미 받은 페이지는 정제했으며, 다시 실행하면 남은 글을 이어서 받습니다/)).toBeVisible()
  expect(screen.getByRole('link', { name: '공개 전 확인이 필요한 공고 보기' })).toBeVisible()
})
