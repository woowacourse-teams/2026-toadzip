import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { SharedDraftPanel } from './SharedDraftPanel'
import { ManagementError } from '../management/api'
const mocks = vi.hoisted(() => ({ loadSharedDraft: vi.fn(), saveSharedDraft: vi.fn(), archiveDocument: vi.fn() }))
vi.mock('./sharedDraftApi', () => mocks)
vi.mock('./documentArchiveApi', () => mocks)
const input = { target: 'basic', documentVersion: 1, sha256: 'a'.repeat(64), sourceUrl: 'https://example.com/notice', pages: '1', resultJson: '', note: '로컬 메모' }
const draft = { ...input, revision: 3, actor: 'other', updatedAt: '2026-10-16T00:00:00Z' }
beforeEach(() => { vi.clearAllMocks(); mocks.archiveDocument.mockResolvedValue(input.sha256); mocks.loadSharedDraft.mockResolvedValue(draft) })
function setup(file: File | null = new File(['%PDF-'], 'test.pdf')) {
  const restore = vi.fn().mockResolvedValue(undefined)
  render(<SharedDraftPanel resource="complexes" id={7} target="basic" input={file ? input : null} file={file} disabled={false} onBusyChange={vi.fn()} onRestore={restore} />)
  fireEvent.click(screen.getByText('다른 관리자와 공유하는 작업 초안'))
  return restore
}
it('원본을 선택하지 않은 다른 기기에서도 공유 초안을 조회·복원할 수 있다', async () => {
  const restore = setup(null)
  fireEvent.click(screen.getByRole('button', { name: '최신 공유 초안 확인' }))
  await screen.findByText(/revision 3/)
  fireEvent.click(screen.getByRole('button', { name: '공유 초안 복원' }))
  await waitFor(() => expect(restore).toHaveBeenCalledWith(draft, expect.any(AbortSignal)))
  expect(mocks.saveSharedDraft).not.toHaveBeenCalled()
})
it('충돌하면 로컬 입력을 전달한 채 저장을 잠그고 최신 조회 전까지 덮어쓰지 않는다', async () => {
  mocks.saveSharedDraft.mockRejectedValue(new ManagementError('다른 관리자가 변경했습니다.', 409))
  setup()
  fireEvent.click(screen.getByRole('button', { name: '현재 작업을 서버 초안으로 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('다른 관리자')
  expect(screen.getByRole('button', { name: '현재 작업을 서버 초안으로 저장' })).toBeDisabled()
  expect(mocks.saveSharedDraft).toHaveBeenCalledWith('complexes', 7, input, 0, expect.any(AbortSignal))
  fireEvent.click(screen.getByRole('button', { name: '최신 공유 초안 확인' }))
  await screen.findByText(/revision 3/)
  expect(screen.getByRole('button', { name: '현재 작업을 서버 초안으로 저장' })).toBeEnabled()
})
it('원본 해시가 달라지면 초안 저장을 중단한다', async () => {
  mocks.archiveDocument.mockResolvedValue('b'.repeat(64))
  setup()
  fireEvent.click(screen.getByRole('button', { name: '현재 작업을 서버 초안으로 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('해시가 달라')
  expect(mocks.saveSharedDraft).not.toHaveBeenCalled()
})
