import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { ManagementError } from '../management/api'
import { ReviewTaskPanel } from './ReviewTaskPanel'
const mocks = vi.hoisted(() => ({ loadReviewTask: vi.fn(), saveReviewTask: vi.fn(), taskAssignees: vi.fn() }))
vi.mock('./reviewTaskApi', async importOriginal => ({ ...await importOriginal<typeof import('./reviewTaskApi')>(), ...mocks }))
const task = { resource: 'COMPLEX', id: 7, name: '단지', revision: 2, assignee: 'first', dueDate: '2026-10-15',
  status: 'OPEN', note: '기존 메모', actor: 'first', updatedAt: '2026-10-16T00:00:00Z', overdue: true, sourceChanged: false }
beforeEach(() => { vi.clearAllMocks(); mocks.loadReviewTask.mockResolvedValue(task); mocks.taskAssignees.mockResolvedValue(['first', 'second']) })
async function setup() {
  const saved = vi.fn()
  render(<ReviewTaskPanel resource="COMPLEX" id={7} onSaved={saved} />)
  fireEvent.click(screen.getByText('업무 담당·기한 관리'))
  fireEvent.click(screen.getByRole('button', { name: '담당 업무 조회' }))
  await screen.findByDisplayValue('기존 메모')
  return saved
}
it('담당자·기한·완료 상태를 저장하면서 데이터 검증과 구분한다', async () => {
  mocks.saveReviewTask.mockResolvedValue({ ...task, revision: 3, assignee: 'second', status: 'DONE', note: '업무 확인' })
  const saved = await setup()
  fireEvent.change(screen.getByLabelText('담당자'), { target: { value: 'second' } })
  fireEvent.change(screen.getByLabelText('업무 상태'), { target: { value: 'DONE' } })
  fireEvent.change(screen.getByLabelText('업무 메모'), { target: { value: '업무 확인' } })
  fireEvent.click(screen.getByRole('button', { name: '담당 업무 저장' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  expect(mocks.saveReviewTask).toHaveBeenCalledWith('COMPLEX', 7, { expectedRevision: 2, assignee: 'second',
    dueDate: '2026-10-15', status: 'DONE', note: '업무 확인' }, expect.any(AbortSignal))
  expect(screen.getByText(/데이터 검증 기록과 등록값은 별도/)).toBeVisible()
})
it('동시 수정 충돌시 입력을 유지하고 최신 업무를 확인한 뒤 다시 저장한다', async () => {
  mocks.saveReviewTask.mockRejectedValue(new ManagementError('다른 관리자가 업무를 변경했습니다.', 409))
  await setup()
  fireEvent.change(screen.getByLabelText('업무 메모'), { target: { value: '내가 작성 중인 메모' } })
  fireEvent.click(screen.getByRole('button', { name: '담당 업무 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 관리자')
  expect(screen.getByRole('button', { name: '담당 업무 저장' })).toBeDisabled()
  mocks.loadReviewTask.mockResolvedValue({ ...task, revision: 3, note: '다른 관리자 메모' })
  fireEvent.click(screen.getByRole('button', { name: '최신 업무 조회' }))
  await screen.findByText('다른 관리자 메모')
  expect(screen.getByLabelText('업무 메모')).toHaveValue('내가 작성 중인 메모')
  expect(screen.getByRole('button', { name: '담당 업무 저장' })).toBeEnabled()
  mocks.saveReviewTask.mockResolvedValue({ ...task, revision: 4, note: '내가 작성 중인 메모' })
  fireEvent.click(screen.getByRole('button', { name: '담당 업무 저장' }))
  await waitFor(() => expect(mocks.saveReviewTask).toHaveBeenLastCalledWith('COMPLEX', 7,
    expect.objectContaining({ expectedRevision: 3, note: '내가 작성 중인 메모' }), expect.any(AbortSignal)))
})
it('조회 실패하면 새 업무인 것처럼 저장하지 않는다', async () => {
  mocks.loadReviewTask.mockRejectedValue(new Error('조회 실패'))
  render(<ReviewTaskPanel resource="COMPLEX" id={7} />)
  fireEvent.click(screen.getByText('업무 담당·기한 관리'))
  fireEvent.click(screen.getByRole('button', { name: '담당 업무 조회' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('조회 실패')
  expect(screen.queryByRole('button', { name: '담당 업무 저장' })).not.toBeInTheDocument()
})
