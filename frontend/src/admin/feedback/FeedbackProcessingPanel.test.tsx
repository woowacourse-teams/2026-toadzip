import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, expect, it, vi } from 'vitest'
import { FeedbackProcessingPanel } from './FeedbackProcessingPanel'
import { getFeedback, getFeedbackChanges, saveFeedback, type FeedbackEntry } from './api'
import { getManagementPage, ManagementError } from '../management/api'

vi.mock('./api', async original => ({ ...await original<typeof import('./api')>(),
  getFeedback: vi.fn(), saveFeedback: vi.fn(), getFeedbackChanges: vi.fn() }))
const entry: FeedbackEntry = { id: 1, content: '공고 조건 확인 요청\n두 번째 줄', createdAt: '2026-10-19T00:00:00Z', version: 0, status: 'OPEN', note: '', relatedResource: '', relatedId: null, actor: null, processedAt: null }
vi.mock('../management/api', async original => ({ ...await original<typeof import('../management/api')>(), getManagementPage: vi.fn() }))
beforeEach(() => { vi.resetAllMocks(); vi.mocked(getFeedbackChanges).mockResolvedValue([]) })
const renderPanel = (value = entry, onSaved = vi.fn()) => {
  render(<MemoryRouter><FeedbackProcessingPanel entry={value} onSaved={onSaved} /></MemoryRouter>); return onSaved
}
const saveButton = () => screen.getByRole('button', { name: '처리 기록 저장' })

it('제목·상태·접수 시각·의견 전문과 처리 기록 없음 상태를 보여 준다', async () => {
  renderPanel()
  expect(screen.getByRole('heading', { name: '의견 #1' })).toBeVisible()
  expect(screen.getByText('접수 2026.10.19 09:00')).toBeVisible()
  expect(screen.getByRole('region', { name: '의견 내용' }).textContent).toBe(entry.content)
  expect(screen.getByRole('button', { name: '접수' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('status')).toHaveTextContent('접수')
  expect(saveButton()).toBeDisabled()
  expect(screen.getByRole('button', { name: '되돌리기' })).toBeDisabled()
  expect(await screen.findByText('아직 처리 기록이 없습니다.')).toBeVisible()
  expect(screen.queryByText(/사용자에게 답변/)).not.toBeInTheDocument()
})

it('처리 완료와 보류는 결과나 이유가 있어야 저장할 수 있다', () => {
  renderPanel()
  for (const name of ['처리 완료', '보류']) {
    fireEvent.click(screen.getByRole('button', { name }))
    expect(screen.getByLabelText(/처리 결과·보류 이유 \(필수\)/)).toBeRequired()
    expect(screen.getByText('처리 완료·보류는 결과나 이유를 적어야 저장할 수 있습니다.')).toBeVisible()
    expect(saveButton()).toBeDisabled()
  }
  fireEvent.change(screen.getByLabelText(/처리 결과·보류 이유/), { target: { value: '   ' } })
  expect(saveButton()).toBeDisabled()
  fireEvent.change(screen.getByLabelText(/처리 결과·보류 이유/), { target: { value: '확인 완료' } })
  expect(saveButton()).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: '처리 중' }))
  expect(screen.queryByText('처리 완료·보류는 결과나 이유를 적어야 저장할 수 있습니다.')).not.toBeInTheDocument()
})

it('변경하면 안내가 바뀌고 되돌리기로 원래 값으로 돌아간다', () => {
  renderPanel()
  fireEvent.click(screen.getByRole('button', { name: '처리 중' }))
  expect(screen.getByRole('status')).toHaveTextContent('저장하지 않은 변경이 있습니다.')
  fireEvent.click(screen.getByRole('button', { name: '되돌리기' }))
  expect(screen.getByRole('button', { name: '접수' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('status')).toHaveTextContent('접수')
})

it('저장하면 현재 version으로 요청하고 저장 안내와 처리 이력을 새로 보여 준다', async () => {
  const saved: FeedbackEntry = { ...entry, version: 1, status: 'DONE', note: '보정함', actor: 'operator', processedAt: '2026-10-19T01:00:00Z' }
  vi.mocked(saveFeedback).mockResolvedValue(saved)
  const onSaved = renderPanel()
  fireEvent.click(screen.getByRole('button', { name: '처리 완료' }))
  fireEvent.change(screen.getByLabelText(/처리 결과·보류 이유/), { target: { value: '보정함' } })
  vi.mocked(getFeedbackChanges).mockResolvedValue([{ id: 5, action: 'PROCESS_FEEDBACK', actor: 'operator', occurredAt: saved.processedAt!,
    beforeValue: JSON.stringify({ ...entry }), afterValue: JSON.stringify(saved) }])
  fireEvent.click(saveButton())
  await waitFor(() => expect(onSaved).toHaveBeenCalledWith(saved))
  expect(saveFeedback).toHaveBeenCalledWith(1, { version: 0, status: 'DONE', note: '보정함', relatedResource: '', relatedId: null })
  expect(screen.getByRole('status')).toHaveTextContent('처리 기록을 저장했습니다.')
  expect(await screen.findByText('상태 접수 → 처리 완료, 메모 수정 · operator')).toBeVisible()
  expect(screen.getByText('10.19 10:00')).toBeVisible()
})

it('이전에 처리한 의견은 최근 처리 시각과 처리자를 보여 준다', () => {
  renderPanel({ ...entry, status: 'DONE', note: '끝', actor: 'operator', processedAt: '2026-10-19T01:00:00Z' })
  expect(screen.getByRole('status')).toHaveTextContent('최근 처리: 10.19 10:00 · operator')
})

it('다른 운영자가 먼저 저장하면 작성 내용을 두고 최신 저장과 비교해 보여 준 뒤 최신 version으로 다시 저장한다', async () => {
  const latest: FeedbackEntry = { ...entry, version: 2, status: 'IN_PROGRESS', note: '확인 중' }
  vi.mocked(saveFeedback).mockRejectedValueOnce(new ManagementError('충돌', 409))
  vi.mocked(getFeedback).mockResolvedValue(latest)
  const onSaved = renderPanel()
  expect(await screen.findByText('아직 처리 기록이 없습니다.')).toBeVisible()
  vi.mocked(getFeedbackChanges).mockResolvedValue([{ id: 9, action: 'PROCESS_FEEDBACK', actor: 'other', occurredAt: '2026-10-19T02:00:00Z',
    beforeValue: JSON.stringify(entry), afterValue: JSON.stringify({ ...entry, version: 2, status: 'IN_PROGRESS' }) }])
  fireEvent.click(screen.getByRole('button', { name: '처리 완료' }))
  fireEvent.change(screen.getByLabelText(/처리 결과·보류 이유/), { target: { value: '내가 쓴 결과' } })
  fireEvent.click(saveButton())
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 운영자가 먼저 저장했습니다. 작성 중인 내용은 그대로 두었습니다. 최신 상태와 비교한 뒤 다시 저장하세요.')
  const table = await screen.findByRole('table', { name: '최신 저장과 내 작성 비교' })
  expect(table).toHaveTextContent('처리 중처리 완료')
  expect(table).toHaveTextContent('확인 중내가 쓴 결과')
  expect(screen.getByLabelText(/처리 결과·보류 이유/)).toHaveValue('내가 쓴 결과')
  expect(await screen.findByText('상태 접수 → 처리 중 · other')).toBeVisible()
  expect(screen.getByRole('heading', { name: '의견 #1' }).parentElement).toHaveTextContent('처리 중')
  expect(saveButton()).toBeDisabled()
  expect(onSaved).not.toHaveBeenCalled()
  const saved = { ...entry, version: 3, status: 'DONE' as const, note: '내가 쓴 결과', actor: 'operator', processedAt: '2026-10-19T01:00:00Z' }
  vi.mocked(saveFeedback).mockResolvedValue(saved)
  fireEvent.click(screen.getByRole('button', { name: '최신 상태 기준으로 다시 저장' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledWith(saved))
  expect(saveFeedback).toHaveBeenLastCalledWith(1, { version: 2, status: 'DONE', note: '내가 쓴 결과', relatedResource: '', relatedId: null })
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
})

it('충돌이 아닌 저장 실패는 입력을 보존하고 다시 저장할 수 있게 둔다', async () => {
  vi.mocked(saveFeedback).mockRejectedValueOnce(new Error('서버 오류'))
  renderPanel()
  fireEvent.click(screen.getByRole('button', { name: '처리 중' }))
  fireEvent.click(saveButton())
  expect(await screen.findByRole('alert')).toHaveTextContent('서버 오류')
  expect(screen.getByRole('button', { name: '처리 중' })).toHaveAttribute('aria-pressed', 'true')
  expect(saveButton()).toBeEnabled()
  expect(screen.queryByRole('button', { name: '최신 상태 기준으로 다시 저장' })).not.toBeInTheDocument()
})

it('처리 이력을 읽지 못하면 오류를 알리고 빈 이력처럼 보이지 않는다', async () => {
  vi.mocked(getFeedbackChanges).mockRejectedValue(new Error('수정 이력 응답이 올바르지 않습니다.'))
  renderPanel()
  expect(await screen.findByRole('alert')).toHaveTextContent('수정 이력 응답이 올바르지 않습니다.')
  expect(screen.queryByText('아직 처리 기록이 없습니다.')).not.toBeInTheDocument()
})

it('관련 단지는 검색해 연결하고 연결을 해제할 수 있다', async () => {
  vi.mocked(getManagementPage).mockResolvedValue({
    items: [{ id: 30, name: '가나단지', subtitle: '서울' }], page: 0, hasNext: false, totalElements: 1, totalPages: 1 } as never)
  renderPanel()
  fireEvent.click(screen.getByRole('button', { name: '단지 연결' }))
  fireEvent.change(screen.getByLabelText('단지 이름으로 검색'), { target: { value: '가나' } })
  fireEvent.click(screen.getByRole('button', { name: '단지 검색' }))
  fireEvent.click(await screen.findByRole('button', { name: '선택: 가나단지' }))
  expect(screen.getByText('가나단지')).toBeVisible()
  expect(screen.getByRole('link', { name: '정보 열기' })).toHaveAttribute('href', expect.stringContaining('/admin/complexes/30'))
  expect(saveButton()).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: '연결 해제' }))
  expect(screen.getByRole('button', { name: '단지 연결' })).toBeVisible()
  expect(saveButton()).toBeDisabled()
})
