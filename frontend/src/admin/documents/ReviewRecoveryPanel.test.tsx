import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { ReviewRecoveryPanel } from './ReviewRecoveryPanel'
const store = vi.hoisted(() => ({ readRecoveryInputs: vi.fn(), saveRecoveryInput: vi.fn(), removeRecoveryInput: vi.fn() }))
vi.mock('./reviewBrowserStore', () => store)
const input = { id: 'one', context: 'same', json: '{"proposals":[]}', note: '원래 메모', updatedAt: '2026-10-17T00:00:00Z' }
beforeEach(() => { vi.clearAllMocks(); store.readRecoveryInputs.mockResolvedValue([input]); store.saveRecoveryInput.mockResolvedValue(undefined) })
it('재진입해 읽은 기록을 선택 복원하고 현재 입력도 별도 보관한다', async () => {
  const restore = vi.fn()
  render(<ReviewRecoveryPanel context="same" json="현재 입력" note="현재 메모" revision={0} disabled={false} onRestore={restore} />)
  fireEvent.click(screen.getByText('새로고침 후에도 남는 입력 복구 기록'))
  await waitFor(() => expect(screen.getByRole('button', { name: '선택한 입력 복원' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '선택한 입력 복원' }))
  expect(restore).toHaveBeenCalledWith(input)
  fireEvent.click(screen.getByRole('button', { name: '현재 입력 복구 기록 보관' }))
  await waitFor(() => expect(store.saveRecoveryInput).toHaveBeenCalledWith({ context: 'same', json: '현재 입력', note: '현재 메모' }))
})
it('다른 문맥으로 바꾸면 이전 목록을 비우고 늦은 조회 결과를 무시한다', async () => {
  const view = render(<ReviewRecoveryPanel context="same" json="" note="" revision={0} disabled={false} onRestore={vi.fn()} />)
  await waitFor(() => expect(store.readRecoveryInputs).toHaveBeenCalledWith('same'))
  store.readRecoveryInputs.mockResolvedValue([])
  view.rerender(<ReviewRecoveryPanel context="other" json="" note="" revision={0} disabled={false} onRestore={vi.fn()} />)
  await waitFor(() => expect(screen.getByRole('button', { name: '선택한 입력 복원' })).toBeDisabled())
})
it('복구 기록 삭제는 확인한 선택 기록에만 적용한다', async () => {
  const confirmation = vi.spyOn(window, 'confirm').mockReturnValue(false)
  store.removeRecoveryInput.mockResolvedValue(undefined)
  render(<ReviewRecoveryPanel context="same" json="현재 입력" note="" revision={0} disabled={false} onRestore={vi.fn()} />)
  fireEvent.click(screen.getByText('새로고침 후에도 남는 입력 복구 기록'))
  await waitFor(() => expect(screen.getByRole('button', { name: '선택한 복구 기록 삭제' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '선택한 복구 기록 삭제' }))
  expect(store.removeRecoveryInput).not.toHaveBeenCalled()
  confirmation.mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', { name: '선택한 복구 기록 삭제' }))
  await waitFor(() => expect(store.removeRecoveryInput).toHaveBeenCalledWith('one', 'same'))
  confirmation.mockRestore()
})
