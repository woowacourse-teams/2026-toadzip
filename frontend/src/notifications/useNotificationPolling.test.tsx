import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useNotificationPolling } from './useNotificationPolling'

beforeEach(() => {
  vi.useFakeTimers()
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
})
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })

it('로그인 시 조회하고 60초 동안 추가 조회하지 않는다', async () => {
  const load = vi.fn(async () => 3)
  const { result } = renderHook(() => useNotificationPolling({ userId: '1', load }))
  await act(async () => { await vi.advanceTimersByTimeAsync(59_000) })
  expect(load).toHaveBeenCalledTimes(1)
  expect(result.current.unreadCount).toBe(3)
  await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
  expect(load).toHaveBeenCalledTimes(2)
})

it('숨김과 오프라인에서는 멈추고 복귀하면 즉시 조회한다', async () => {
  const load = vi.fn(async () => 4)
  renderHook(() => useNotificationPolling({ userId: '1', load }))
  await act(async () => { await vi.advanceTimersByTimeAsync(0) })
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
  await act(async () => { document.dispatchEvent(new Event('visibilitychange')); await vi.advanceTimersByTimeAsync(120_000) })
  expect(load).toHaveBeenCalledTimes(1)
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
  expect(load).toHaveBeenCalledTimes(2)
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
  await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
  expect(load).toHaveBeenCalledTimes(2)
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
  await act(async () => { window.dispatchEvent(new Event('online')) })
  expect(load).toHaveBeenCalledTimes(3)
})

it('동시 갱신 신호를 한 번의 후속 조회로 합친다', async () => {
  let resolve!: (value: number) => void
  const load = vi.fn().mockImplementationOnce(() => new Promise<number>(done => { resolve = done })).mockResolvedValue(5)
  const { result } = renderHook(() => useNotificationPolling({ userId: '1', load }))
  act(() => { result.current.refresh(); result.current.refresh(); result.current.refresh() })
  expect(load).toHaveBeenCalledTimes(1)
  await act(async () => { resolve(2) })
  expect(load).toHaveBeenCalledTimes(2)
  expect(result.current.unreadCount).toBe(5)
})

it('계정 교체와 로그아웃은 요청을 취소하고 늦은 이전 결과를 버린다', async () => {
  let resolve!: (value: number) => void
  let firstSignal: AbortSignal | undefined
  const load = vi.fn().mockImplementationOnce((signal: AbortSignal) => {
    firstSignal = signal
    return new Promise<number>(done => { resolve = done })
  }).mockResolvedValue(1)
  const { result, rerender } = renderHook(({ userId }: { userId: string | null }) => useNotificationPolling({ userId, load }), { initialProps: { userId: '1' as string | null } })
  await act(async () => { rerender({ userId: '2' }) })
  expect(firstSignal?.aborted).toBe(true)
  await act(async () => { resolve(99) })
  expect(result.current.unreadCount).toBe(1)
  await act(async () => { rerender({ userId: null }) })
  await act(async () => { await vi.advanceTimersByTimeAsync(120_000) })
  expect(result.current.unreadCount).toBeNull()
  expect(load).toHaveBeenCalledTimes(2)
})
