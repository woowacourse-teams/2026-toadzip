import { fireEvent, render, screen, within } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { ComplexExecutionResult, ComplexExecutionStop } from './ComplexExecutionResult'
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
  ['COMPLEX_COLLECTION', '마이홈 단지 수집', '수집 중지'],
  ['COMPLEX_REFINEMENT', '마이홈 단지 정제', '정제 중지'],
] as const)('%s 상태와 중지 문구를 구분하며 중지 접수 후 잠긴다', (type, step, label) => {
  const onStop = vi.fn()
  const running: DataPipelineExecution = { ...execution, type, status: 'RUNNING', completedSteps: [], currentStepName: step }
  const view = render(<><ComplexExecutionResult execution={running} /><ComplexExecutionStop execution={running} label={label} onStop={onStop} /></>)
  expect(within(screen.getByRole('group', { name: '실행 상태' })).getByText('진행 중')).toBeVisible()
  expect(within(screen.getByRole('list', { name: '실행 단계' })).getByText('진행 중')).toBeVisible()
  const stop = screen.getByRole('button', { name: label })
  expect(stop).toHaveTextContent(label)
  expect(stop.querySelector('svg')).not.toBeNull()
  expect(stop).toHaveAttribute('title', `${label} · 현재 처리 후 종료`)
  fireEvent.click(stop)
  expect(onStop).toHaveBeenCalledOnce()
  view.rerender(<><ComplexExecutionResult execution={{ ...running, stopRequested: true }} /><ComplexExecutionStop execution={{ ...running, stopRequested: true }} label={label} onStop={onStop} /></>)
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
})


it.each(['COMPLEX_COLLECTION', 'COMPLEX_REFINEMENT'] as const)('%s는 실행 전에도 오류 조회를 열 수 있고 실행 기록 없음은 오류 없음과 구별한다', type => {
  render(<ComplexExecutionResult execution={{ ...execution, type, executionId: null, status: 'IDLE', completedSteps: [] }} />)
  const button = screen.getByRole('button', { name: '결과 오류 조회' })
  expect(button).toBeVisible()
  fireEvent.click(button)
  expect(screen.getByRole('region', { name: '결과 오류' })).toHaveTextContent('아직 실행 기록이 없습니다.')
  expect(screen.queryByText('수집 오류 내역')).not.toBeInTheDocument()
  expect(screen.queryByText(/오류가 없습니다/)).not.toBeInTheDocument()
})

it.each([
  ['RUNNING', false, '실행 중입니다.'],
  ['COMPLETED', true, '실행 접수를 확인하고 있습니다.'],
] as const)('%s 접수 중=%s에는 오류 조회 버튼을 유지하되 과거 결과를 새 결과처럼 조회하지 않는다', (status, pending, message) => {
  render(<ComplexExecutionResult execution={{ ...execution, status }} pending={pending} />)
  fireEvent.click(screen.getByRole('button', { name: '결과 오류 조회' }))
  expect(screen.getByRole('region', { name: '결과 오류' })).toHaveTextContent(message)
  expect(screen.queryByText('수집 오류 내역')).not.toBeInTheDocument()
})

it('중지 전부터 오류 조회 버튼을 유지하고 열린 패널에서 중지 결과를 조회할 수 있다', () => {
  const view = render(<ComplexExecutionResult execution={{ ...execution, status: 'RUNNING' }} />)
  const button = screen.getByRole('button', { name: '결과 오류 조회' })
  fireEvent.click(button)
  expect(screen.queryByText('수집 오류 내역')).not.toBeInTheDocument()
  view.rerender(<ComplexExecutionResult execution={{ ...execution, status: 'STOPPED' }} />)
  expect(screen.getByRole('button', { name: '결과 오류 조회' })).toBe(button)
  expect(button).toHaveAttribute('aria-expanded', 'true')
  expect(screen.getByRole('region', { name: '결과 오류' })).toHaveTextContent('수집 오류 내역')
})

it('이력에서는 오류 조회 진입점을 표시하지 않는다', () => {
  render(<ComplexExecutionResult execution={execution} showErrors={false} />)
  expect(screen.queryByRole('button', { name: '결과 오류 조회' })).not.toBeInTheDocument()
})


it('실행 ID를 확인하지 못한 결과는 오류 없음 대신 조회 불가로 표시한다', () => {
  render(<ComplexExecutionResult execution={{ ...execution, executionId: null }} />)
  fireEvent.click(screen.getByRole('button', { name: '결과 오류 조회' }))
  expect(screen.getByRole('alert')).toHaveTextContent('실행 기록을 확인하지 못했습니다.')
  expect(screen.queryByText('수집 오류 내역')).not.toBeInTheDocument()
})
