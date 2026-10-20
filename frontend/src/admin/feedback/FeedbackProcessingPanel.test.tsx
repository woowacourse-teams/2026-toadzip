import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { FeedbackProcessingPanel } from './FeedbackProcessingPanel'
import { getFeedback, saveFeedback, type FeedbackEntry } from './api'
import { ManagementError } from '../management/api'
vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(), getFeedback: vi.fn(), saveFeedback: vi.fn() }))
const entry: FeedbackEntry = { id: 1, content: '공고 조건 확인 요청', createdAt: '2026-10-19T00:00:00Z', version: 0, status: 'OPEN', note: '', relatedResource: '', relatedId: null, actor: null, processedAt: null }
beforeEach(() => { vi.clearAllMocks() })
it('완료 근거를 저장하고 동시 변경 거절에는 입력을 보존하며 실제 처리자를 보여준다', async () => {
  const onSaved = vi.fn()
  vi.mocked(saveFeedback).mockRejectedValueOnce(new ManagementError('이미 다른 관리자가 변경함', 409))
  vi.mocked(getFeedback).mockResolvedValue({ ...entry, version: 2, status: 'IN_PROGRESS', note: '확인 중' })
  render(<MemoryRouter><FeedbackProcessingPanel entry={entry} onSaved={onSaved} /></MemoryRouter>)
  fireEvent.change(screen.getByLabelText('처리 상태'), { target: { value: 'DONE' } })
  expect(screen.getByLabelText('처리 결과·보류 이유')).toBeRequired()
  fireEvent.change(screen.getByLabelText('처리 결과·보류 이유'), { target: { value: '원문 확인 후 공급행 보정' } })
  fireEvent.click(screen.getByRole('button', { name: '처리 기록 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('이미 다른 관리자')
  expect(screen.getByLabelText('처리 결과·보류 이유')).toHaveValue('원문 확인 후 공급행 보정')
  expect(screen.getByRole('button', { name: '처리 기록 저장' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '입력을 유지하고 최신 상태 확인' }))
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('최신 상태: 처리 중'))
  expect(onSaved).not.toHaveBeenCalled()
  const saved = { ...entry, version: 3, status: 'DONE' as const, note: '원문 확인 후 공급행 보정', actor: 'operator', processedAt: '2026-10-19T01:00:00Z' }
  vi.mocked(saveFeedback).mockResolvedValue(saved)
  fireEvent.click(screen.getByRole('button', { name: '처리 기록 저장' }))
  expect(await screen.findByText(/최근 처리: operator/)).toBeVisible()
  expect(saveFeedback).toHaveBeenLastCalledWith(1, { version: 2, status: 'DONE', note: saved.note, relatedResource: '', relatedId: null })
  expect(onSaved).toHaveBeenCalledWith(saved)
})
