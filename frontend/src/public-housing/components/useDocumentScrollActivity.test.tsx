import { useRef } from 'react'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useDocumentScrollActivity } from './useDocumentScrollActivity.ts'

function ScrollArea() {
  const ref = useRef<HTMLDivElement>(null)
  useDocumentScrollActivity(ref)
  return <div ref={ref} data-testid="document-scroll" />
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

it('스크롤 중 손잡이를 표시하고 마지막 스크롤 후 900ms가 지나면 숨긴다', () => {
  render(<ScrollArea />)
  const scroll = screen.getByTestId('document-scroll')
  expect(scroll).toHaveAttribute('data-scrolling', 'false')

  fireEvent.scroll(scroll)
  expect(scroll).toHaveAttribute('data-scrolling', 'true')
  act(() => vi.advanceTimersByTime(600))
  fireEvent.scroll(scroll)
  act(() => vi.advanceTimersByTime(899))
  expect(scroll).toHaveAttribute('data-scrolling', 'true')
  act(() => vi.advanceTimersByTime(1))
  expect(scroll).toHaveAttribute('data-scrolling', 'false')
})

it('문서를 닫으면 스크롤 리스너와 대기 중인 타이머를 정리한다', () => {
  const { unmount } = render(<ScrollArea />)
  const scroll = screen.getByTestId('document-scroll')
  fireEvent.scroll(scroll)
  expect(vi.getTimerCount()).toBe(1)

  unmount()
  expect(scroll).not.toHaveAttribute('data-scrolling')
  expect(vi.getTimerCount()).toBe(0)
  fireEvent.scroll(scroll)
  expect(scroll).not.toHaveAttribute('data-scrolling')
})
