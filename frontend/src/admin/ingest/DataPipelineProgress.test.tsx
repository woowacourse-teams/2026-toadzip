import { render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import type { DataPipelineExecution } from './api'

afterEach(() => vi.useRealTimers())
import { DataPipelineProgress } from './DataPipelineProgress'

it('진행 중인 단계를 완료로 세지 않고 실제 종료된 단계로 진행률을 계산한다', () => {
  render(<DataPipelineProgress label="공고 수집" execution={{
    executionId: 'run-1', type: 'ANNOUNCEMENT_COLLECTION', status: 'RUNNING',
    currentStepName: 'LH 공급 수집', currentStepIndex: 3, totalStepCount: 4,
    completedSteps: ['마이홈 공고 수집'], skippedSteps: [{ stepName: 'LH 카탈로그 수집', reason: '호출 제한', serverResponse: null }],
    partiallyFailedSteps: [{ step: 'MYHOME', stepName: '마이홈 공고 수집', report: {} }],
    failure: null, externalRequestCount: 42,
  }} />)
  expect(screen.getByText('단계 처리 2/4')).toBeVisible()
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  expect(screen.getByText('42회')).toBeVisible()
})

it('도입 전 실행의 없는 요청 횟수를 0회로 단정하지 않는다', () => {
  render(<DataPipelineProgress label="단지 수집" execution={{
    executionId: 'legacy', type: 'COMPLEX_COLLECTION', status: 'FAILED',
    currentStepName: null, currentStepIndex: 0, totalStepCount: 2,
    completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null,
    externalRequestCount: 0, lastProgressAt: null,
  }} />)
  expect(screen.getByText('외부 요청 횟수 기록이 없습니다.')).toBeVisible()
  expect(screen.queryByText('0회')).not.toBeInTheDocument()
})

const running: DataPipelineExecution = {
  executionId: 'eta', type: 'COMPLEX_COLLECTION', status: 'RUNNING',
  currentStepName: '마이홈 단지 수집', currentStepIndex: 1, totalStepCount: 2,
  completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null,
  workProgress: {
    label: '마이홈 단지 · 전체 지역', unit: '지역', completedCount: 20, totalCount: 100,
    startedAt: '2026-09-26T00:00:00Z', updatedAt: '2026-09-26T00:01:00Z',
  },
}

it('실제 처리량과 경과 시간으로 남은 시간을 계산하고 단계 비율과 구분한다', () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-26T00:01:00Z'))
  render(<DataPipelineProgress label="단지 수집" execution={running} />)
  expect(screen.getByText('약 4분')).toBeVisible()
  expect(screen.getByRole('progressbar', { name: '단지 수집 수집 대상 진행률' })).toHaveAttribute('value', '20')
  expect(screen.getByRole('progressbar', { name: '단지 수집 수집 대상 진행률' })).toHaveAttribute('max', '100')
})

it('처리가 지연되거나 중지를 요청한 실행에는 예상 시간을 확정해서 보여주지 않는다', () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-26T00:04:00Z'))
  const { rerender } = render(<DataPipelineProgress label="단지 수집" execution={running} />)
  expect(screen.getByText('응답 지연 · 재계산 대기')).toBeVisible()
  rerender(<DataPipelineProgress label="단지 수집" execution={{ ...running, stopRequested: true }} />)
  expect(screen.queryByText('예상 남은 시간')).not.toBeInTheDocument()
})

it('전체 대상 수를 모를 때는 가짜 비율이나 예상 시간을 만들지 않는다', () => {
  render(<DataPipelineProgress label="단지 수집" execution={{ ...running,
    workProgress: { ...running.workProgress!, totalCount: -1 },
  }} />)
  expect(screen.getByText('전체 대상 확인 중')).toBeVisible()
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
})
