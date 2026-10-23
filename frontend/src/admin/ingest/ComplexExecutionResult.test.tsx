import { fireEvent, render, screen, within } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { ComplexExecutionResult } from './ComplexExecutionResult'
import type { DataPipelineExecution } from './api'

vi.mock('./ComplexRunErrors', () => ({ ComplexRunErrors: () => <p>수집 오류 내역</p> }))
const execution: DataPipelineExecution = {
  executionId: 'collect-1', type: 'COMPLEX_COLLECTION', status: 'COMPLETED', currentStepName: null,
  currentStepIndex: 2, totalStepCount: 2, completedSteps: ['마이홈 단지 수집', 'LH 임대 카탈로그 수집'],
  skippedSteps: [], partiallyFailedSteps: [], failure: null,
}

it('오류 조회 버튼의 펼침 상태와 연결된 하단 영역을 표시하고 접을 수 있다', () => {
  render(<ComplexExecutionResult execution={execution} />)
  const button = screen.getByRole('button', { name: '결과 오류 조회' })
  expect(button).toHaveAttribute('aria-expanded', 'false')
  expect(button).toHaveTextContent(/^결과 오류 조회$/)
  expect(screen.queryByText('수집 오류 내역')).not.toBeInTheDocument()
  fireEvent.click(button)
  expect(button).toHaveAttribute('aria-expanded', 'true')
  expect(button).toHaveTextContent(/^결과 오류 조회$/)
  const panel = screen.getByRole('region', { name: '결과 오류' })
  expect(panel.id).toBe(button.getAttribute('aria-controls'))
  expect(panel).toHaveTextContent('수집 오류 내역')
  expect(button.compareDocumentPosition(panel) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  fireEvent.click(button)
  expect(button).toHaveAttribute('aria-expanded', 'false')
  expect(screen.queryByRole('region', { name: '결과 오류' })).not.toBeInTheDocument()
})

it.each([
  ['COMPLEX_COLLECTION', '마이홈 단지 수집'],
  ['COMPLEX_REFINEMENT', '마이홈 단지 정제'],
] as const)('%s 진행 표시와 중지 아이콘은 같은 동작을 제공하며 중지 접수 후 잠긴다', (type, step) => {
  const onStop = vi.fn()
  const running: DataPipelineExecution = { ...execution, type, status: 'RUNNING', completedSteps: [], currentStepName: step }
  const view = render(<ComplexExecutionResult execution={running} onStop={onStop} />)
  expect(within(screen.getByRole('group', { name: '실행 상태' })).getByText('진행 중')).toBeVisible()
  expect(within(screen.getByRole('list', { name: '실행 단계' })).getByText('진행 중')).toBeVisible()
  const stop = screen.getByRole('button', { name: '실행 중지' })
  expect(stop).toHaveTextContent(/^$/)
  expect(stop.querySelector('svg')).not.toBeNull()
  expect(stop).toHaveAttribute('title', '실행 중지 · 현재 처리 후 종료')
  fireEvent.click(stop)
  expect(onStop).toHaveBeenCalledOnce()
  view.rerender(<ComplexExecutionResult execution={{ ...running, stopRequested: true }} onStop={onStop} />)
  expect(screen.getByRole('button', { name: '중지 요청 중…' })).toBeDisabled()
  expect(within(screen.getByRole('group', { name: '실행 상태' })).getByText('중지 요청 중')).toBeVisible()
  expect(screen.getByText(/현재 처리가 끝나면 중지/)).toBeVisible()
})

it.each([
  ['COMPLETED', '완료', 'success'],
  ['COMPLETED_WARNINGS', '일부 실패', 'warning'],
  ['FAILED', '실패', 'danger'],
] as const)('%s 결과와 개별 단계 상태를 같은 의미로 구분한다', (status, label, tone) => {
  const value: DataPipelineExecution = { ...execution, status,
    completedSteps: status === 'COMPLETED' ? ['마이홈 단지 수집'] : [],
    partiallyFailedSteps: status === 'COMPLETED_WARNINGS' ? [{ step: 'COLLECT_MYHOME_COMPLEXES', stepName: '마이홈 단지 수집', report: { failedRequestCount: 1 } }] : [],
    failure: status === 'FAILED' ? { stepName: '마이홈 단지 수집', message: '수집 요청 실패', serverResponse: null } : null }
  render(<ComplexExecutionResult execution={value} />)
  expect(within(screen.getByRole('group', { name: '실행 상태' })).getByText(label)).toHaveAttribute('data-tone', tone)
  expect(within(screen.getByRole('list', { name: '실행 단계' })).getByText(label)).toHaveAttribute('data-tone', tone)
  expect(screen.queryByRole('button', { name: '실행 중지' })).not.toBeInTheDocument()
})
