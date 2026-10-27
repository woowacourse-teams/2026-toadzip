import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { RequestError } from './RequestError'

it('실패 제목과 사용자 안내를 분리하고 재시도 행동을 제공한다', () => {
  const retry = vi.fn()
  render(<RequestError title="단지 목록을 불러오지 못했어요." onRetry={retry} />)
  expect(screen.getByRole('alert')).toHaveTextContent('단지 목록을 불러오지 못했어요.')
  expect(screen.getByText('잠시 후 다시 시도해 주세요.').tagName).toBe('P')
  fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
  expect(retry).toHaveBeenCalledOnce()
})
