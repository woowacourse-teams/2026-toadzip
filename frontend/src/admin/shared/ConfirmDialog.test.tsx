import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { useConfirm } from './ConfirmDialog'

function Runner({ run }: { run: () => void }) {
  const [confirm, confirmDialog] = useConfirm()
  return <>
    <button type="button" onClick={() => { void confirm({ title: '전체 단지를 등록할까요?', body: '120곳을 등록합니다.', confirmLabel: '120곳 등록' }).then(ok => { if (ok) run() }) }}>등록</button>
    {confirmDialog}
  </>
}

it('실행 버튼을 눌러야 실행하고, 그 전에는 실행하지 않는다', async () => {
  const run = vi.fn()
  render(<Runner run={run} />)
  fireEvent.click(screen.getByRole('button', { name: '등록' }))
  expect(screen.getByRole('dialog', { name: '전체 단지를 등록할까요?' })).toHaveTextContent('120곳을 등록합니다.')
  expect(run).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '120곳 등록' }))
  await waitFor(() => expect(run).toHaveBeenCalledTimes(1))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('취소하면 실행하지 않고 창을 닫는다', async () => {
  const run = vi.fn()
  render(<Runner run={run} />)
  fireEvent.click(screen.getByRole('button', { name: '등록' }))
  fireEvent.click(screen.getByRole('button', { name: '취소' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(run).not.toHaveBeenCalled()
})
