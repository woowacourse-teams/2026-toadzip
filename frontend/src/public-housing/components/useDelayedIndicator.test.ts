import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDelayedIndicator } from './useDelayedIndicator.ts'

describe('useDelayedIndicator', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  function renderIndicator() {
    return renderHook(({ active }) => useDelayedIndicator(active), { initialProps: { active: true } })
  }

  it('지연 시간 안에 끝난 요청은 표시하지 않는다', () => {
    const { result, rerender } = renderIndicator()
    act(() => vi.advanceTimersByTime(299))
    rerender({ active: false })
    act(() => vi.advanceTimersByTime(1000))
    expect(result.current).toBe(false)
  })

  it('지연 직후 끝난 요청은 최소 표시 시간을 채운 뒤 내린다', () => {
    const { result, rerender } = renderIndicator()
    act(() => vi.advanceTimersByTime(300))
    expect(result.current).toBe(true)
    act(() => vi.advanceTimersByTime(50))
    rerender({ active: false })
    act(() => vi.advanceTimersByTime(449))
    expect(result.current).toBe(true)
    act(() => vi.advanceTimersByTime(1))
    expect(result.current).toBe(false)
  })

  it('최소 표시 시간이 지난 뒤 끝난 요청은 바로 내린다', () => {
    const { result, rerender } = renderIndicator()
    act(() => vi.advanceTimersByTime(900))
    rerender({ active: false })
    act(() => vi.advanceTimersByTime(0))
    expect(result.current).toBe(false)
  })

  it('최소 표시 중 다시 요청하면 깜빡이지 않고 계속 표시한다', () => {
    const { result, rerender } = renderIndicator()
    act(() => vi.advanceTimersByTime(300))
    rerender({ active: false })
    act(() => vi.advanceTimersByTime(100))
    rerender({ active: true })
    act(() => vi.advanceTimersByTime(1000))
    expect(result.current).toBe(true)
  })
})
