import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { DataPipelineExecution } from './api'
import { PipelineExecutionSteps } from './PipelineExecutionSteps'

function execution(overrides: Partial<DataPipelineExecution> = {}): DataPipelineExecution {
  return { executionId: 'run-1', type: 'ANNOUNCEMENT_REGISTRATION', status: 'RUNNING',
    currentStepName: '마이홈 공고 수집', currentStepIndex: 1, totalStepCount: 4,
    completedSteps: [], skippedSteps: [], partiallyFailedSteps: [], failure: null,
    startedAt: '2026-10-08T01:00:00Z', ...overrides }
}
function show(value: DataPipelineExecution, onStop?: () => void) {
  return render(<MemoryRouter><PipelineExecutionSteps execution={value} onStop={onStop} /></MemoryRouter>)
}

describe('v2 공통 단계 표시', () => {
  it('단건은 실제 네 단계와 진행·대기를 표시하며 개별 시각을 추정하지 않는다', () => {
    show(execution())
    const steps = screen.getByRole('list', { name: '실행 단계' })
    expect(within(steps).getAllByRole('listitem')).toHaveLength(4)
    expect(within(steps).getByRole('button', { name: /마이홈 원천 확보 진행/ })).toHaveAttribute('aria-current', 'step')
    expect(within(steps).getByRole('button', { name: /LH 공급 수집 대기/ })).toBeVisible()
    expect(screen.getByText(/종료: 기록 없음/)).toBeVisible()
  })

  it('부분 실패와 치명 실패를 구분하고 후속 단계를 미실행으로 표시한다', () => {
    show(execution({ type: 'ANNOUNCEMENT_SYNC', status: 'FAILED',
      completedSteps: ['마이홈 공고 수집'],
      partiallyFailedSteps: [{ step: 'COLLECT_LH_ANNOUNCEMENT_CATALOG', stepName: 'LH 공고 목록 수집', report: { failedRequestCount: 1 } }],
      skippedSteps: [{ stepName: 'LH 공고 공급 원본 수집', reason: '호출 제한', serverResponse: {} }],
      failure: { stepName: 'LH 공고 상세 원본 수집', message: '외부 API 실패', serverResponse: { failedRequestCount: 2 } },
    }))
    const steps = screen.getByRole('list', { name: '실행 단계' })
    expect(within(steps).getByRole('button', { name: /마이홈 공고 수집 성공/ })).toBeVisible()
    expect(within(steps).getByRole('button', { name: /LH 공고 목록 수집 부분 실패/ })).toBeVisible()
    expect(within(steps).getByRole('button', { name: /공급 원본 수집 생략/ })).toBeVisible()
    fireEvent.click(within(steps).getByRole('button', { name: /상세 원본 수집 실패/ }))
    const detail = screen.getByRole('region', { name: '단계 상세' })
    expect(within(detail).getByText('외부 API 실패')).toBeVisible()
    expect(within(detail).getByRole('link', { name: '실패 대상 확인' })).toHaveAttribute('href',
      '/admin/failures?domain=announcement&category=collection&executionId=run-1')
    expect(within(detail).getByText('상세 보고서').closest('details')).not.toHaveAttribute('open')
    expect(within(steps).getByRole('button', { name: /마이홈 공고 정제 미실행/ })).toBeVisible()
  })

  it('부분 실패 뒤 후속 성공을 유지하고 최종 실패 사유를 중복 치명 실패로 표시하지 않는다', () => {
    show(execution({ status: 'FAILED', completedSteps: ['마이홈 공고 정제'],
      partiallyFailedSteps: [{ step: 'COLLECT_MYHOME_ANNOUNCEMENTS', stepName: '마이홈 공고 수집', report: {} }],
      failure: { stepName: '마이홈 공고 수집', message: '일부 실패', serverResponse: {} } }))
    expect(screen.getByRole('button', { name: /마이홈 원천 확보 부분 실패/ })).toBeVisible()
    expect(screen.getByRole('button', { name: /정제·저장 성공/ })).toBeVisible()
  })

  it('LH 외 기관은 해당 없음으로 표시하고 정제 성공과 관리 링크를 유지한다', () => {
    show(execution({ status: 'COMPLETED_WITH_SKIPS', currentStepName: null,
      completedSteps: ['마이홈 공고 수집', '마이홈 공고 정제'],
      skippedSteps: ['LH 공고 공급 원본 수집', 'LH 공고 상세 원본 수집'].map(stepName =>
        ({ stepName, reason: '해당 없음: LH 외 기관입니다.', serverResponse: {} })) }))
    expect(screen.getAllByRole('button', { name: /해당 없음/ })).toHaveLength(2)
    expect(screen.getByRole('button', { name: /정제·저장 성공/ })).toBeVisible()
    expect(screen.getByRole('link', { name: '공고 관리로 이동' })).toBeVisible()
  })

  it('중지한 현재 단계와 아직 실행하지 않은 다음 단계를 구분한다', () => {
    show(execution({ status: 'STOPPED', currentStepName: 'LH 공고 공급 원본 수집',
      completedSteps: ['마이홈 공고 수집'] }))
    expect(screen.getByRole('button', { name: /LH 공급 수집 중지/ })).toBeVisible()
    expect(screen.getByRole('button', { name: /LH 상세 수집 미실행/ })).toBeVisible()
  })

  it('실행 화면에만 중지를 제공하고 요청 후에는 잠근다', () => {
    const stop = vi.fn()
    show(execution({ stopRequested: true }), stop)
    expect(screen.getByRole('button', { name: '중지 요청 중…' })).toBeDisabled()
    expect(stop).not.toHaveBeenCalled()
  })

  it('단지 수집·정제의 실제 네 단계와 정제 실패 대상 링크를 표시한다', () => {
    show(execution({ type: 'COMPLEX_SYNC', status: 'FAILED',
      failure: { stepName: '마이홈 단지 정제', message: '단지 매칭 실패', serverResponse: null } }))
    expect(screen.getByRole('list', { name: '실행 단계' }).children).toHaveLength(4)
    fireEvent.click(screen.getByRole('button', { name: /마이홈 단지 정제 실패/ }))
    expect(screen.getByRole('link', { name: '실패 대상 확인' })).toHaveAttribute('href',
      '/admin/failures?domain=complex&category=complex&executionId=run-1')
    expect(screen.queryByRole('button', { name: /실행 중지/ })).not.toBeInTheDocument()
  })
})
