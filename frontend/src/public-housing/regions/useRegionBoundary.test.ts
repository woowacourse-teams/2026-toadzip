import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RegionBoundary } from './regionBoundary.ts'
import type { RegionBoundaryRepository } from './regionBoundaryRepository.ts'
import { useRegionBoundary } from './useRegionBoundary.ts'

const first: RegionBoundary = { regionCode: '41110', version: 'test', polygons: [] }
const second: RegionBoundary = { regionCode: '41111', version: 'test', polygons: [] }

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

describe('useRegionBoundary', () => {
  it('aborts replaced requests and ignores late results even if the transport ignores abort', async () => {
    const pending = deferred<RegionBoundary>()
    const find = vi.fn<RegionBoundaryRepository['find']>().mockReturnValueOnce(pending.promise).mockResolvedValue(second)
    const repository = { find }
    const { result, rerender } = renderHook(({ code }) => useRegionBoundary(code, repository), { initialProps: { code: '41110' as string | null } })
    const oldSignal = find.mock.calls[0][1]
    rerender({ code: '41111' })
    await waitFor(() => expect(result.current.boundary).toEqual(second))
    expect(oldSignal.aborted).toBe(true)
    await act(async () => pending.resolve(first))
    expect(result.current.boundary).toEqual(second)
    rerender({ code: null })
    expect(result.current.boundary).toBeNull()
    expect(result.current.status).toBe('idle')
  })
  it('clears old geometry while replacing, exposes failure and retries the selected region', async () => {
    const pending = deferred<RegionBoundary>()
    const find = vi.fn<RegionBoundaryRepository['find']>().mockResolvedValueOnce(first).mockReturnValueOnce(pending.promise).mockResolvedValueOnce(second)
    const repository = { find }
    const { result, rerender } = renderHook(({ code }) => useRegionBoundary(code, repository), { initialProps: { code: '41110' } })
    await waitFor(() => expect(result.current.boundary).toEqual(first))
    rerender({ code: '41111' })
    expect(result.current.boundary).toBeNull()
    await act(async () => pending.reject(new Error('network failed')))
    expect(result.current.status).toBe('error')
    act(() => result.current.retry())
    await waitFor(() => expect(result.current.boundary).toEqual(second))
    expect(result.current.status).toBe('ready')
  })
})
