import { fireEvent, render, screen } from '@testing-library/react'
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
