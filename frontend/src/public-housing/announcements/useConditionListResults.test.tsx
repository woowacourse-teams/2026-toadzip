import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ConditionsContext, type ConditionsContextValue } from '../../user/conditions/ConditionsContext'
import type { AnnouncementRef, ConditionsRepository, ListConditionResult } from '../../user/conditions/conditionsModel'
import type { AnnouncementListItem } from '../model/publicHousing'
import { useConditionListResults } from './useConditionListResults'

const item = (announcementId: string, rentalType = 'HAPPY_HOUSING'): AnnouncementListItem => ({ announcementId, rentalType, title: `${announcementId} 공고`, applicationStatus: 'APPLYING' } as AnnouncementListItem)

function value(load: ConditionsRepository['loadListResults'], extra: Partial<ConditionsContextValue> = {}): ConditionsContextValue {
  return {
    mode: 'member', hasProfile: true, memberDraft: null, revision: 1, lastSave: null, stored: null,
    repository: { loadListResults: load } as ConditionsRepository,
    openInput: vi.fn(), restartInput: vi.fn(), openNudge: vi.fn(), clearProfile: vi.fn(), notify: vi.fn(), ...extra,
  }
}

const result = (announcementId: string, eligibleInRegion: boolean): ListConditionResult => ({
  announcementId, availability: 'AVAILABLE', freshness: 'CURRENT', missingInputs: [], eligibleInRegion, best: { label: '청년', status: eligibleInRegion ? 'ELIGIBLE' : 'UNDETERMINED' },
})

describe('useConditionListResults', () => {
  it('maps list items to rental type labels and keeps matched ids', async () => {
    const load = vi.fn(async (refs: readonly AnnouncementRef[]) => new Map(refs.map((ref) => [ref.announcementId, result(ref.announcementId, ref.announcementId === '1')])))
    const wrapper = ({ children }: { children: ReactNode }) => <ConditionsContext value={value(load)}>{children}</ConditionsContext>
    const items = [item('1'), item('2', 'PERMANENT_RENTAL')]
    const { result: hook } = renderHook(() => useConditionListResults(items), { wrapper })
    await waitFor(() => expect(hook.current.status).toBe('ready'))
    expect(load.mock.calls[0][0].map((ref) => ref.rentalType)).toEqual(['행복주택', '영구임대'])
    expect([...hook.current.matchedIds]).toEqual(['1'])
  })

  it('recomputes list results when the loaded announcements change', async () => {
    const load = vi.fn(async (refs: readonly AnnouncementRef[]) => new Map(refs.map((ref) => [ref.announcementId, result(ref.announcementId, true)])))
    let current = value(load)
    const wrapper = ({ children }: { children: ReactNode }) => <ConditionsContext value={current}>{children}</ConditionsContext>
    const { result: hook, rerender } = renderHook(({ items }) => useConditionListResults(items), { wrapper, initialProps: { items: [item('1')] } })
    await waitFor(() => expect(hook.current.matchedIds.size).toBe(1))
    rerender({ items: [item('1'), item('2')] })
    await waitFor(() => expect(hook.current.matchedIds.size).toBe(2))
    expect(load.mock.calls[1][0].map((ref) => ref.announcementId)).toEqual(['2'])
    current = { ...current, revision: 2 }
    rerender({ items: [item('1'), item('2')] })
    expect(hook.current.status).toBe('loading')
    expect(hook.current.results.size).toBe(0)
    await waitFor(() => expect(load).toHaveBeenCalledTimes(3))
    expect(load.mock.calls[2][0].map((ref) => ref.announcementId)).toEqual(['1', '2'])
  })

  it('counts only matches among the current items and reports loading until new items are compared', async () => {
    let resolve: (value: Map<string, ListConditionResult>) => void = () => {}
    const load = vi.fn()
      .mockResolvedValueOnce(new Map([['1', result('1', true)], ['2', result('2', true)]]))
      .mockImplementationOnce(() => new Promise((done) => { resolve = done }))
    const wrapper = ({ children }: { children: ReactNode }) => <ConditionsContext value={value(load)}>{children}</ConditionsContext>
    const { result: hook, rerender } = renderHook(({ items }) => useConditionListResults(items), { wrapper, initialProps: { items: [item('1'), item('2')] } })
    await waitFor(() => expect(hook.current.status).toBe('ready'))
    rerender({ items: [item('2'), item('3')] })
    expect(hook.current.status).toBe('loading')
    expect([...hook.current.matchedIds]).toEqual(['2'])
    expect(load.mock.calls[1][0].map((ref: AnnouncementRef) => ref.announcementId)).toEqual(['3'])
    resolve(new Map([['3', result('3', true)]]))
    await waitFor(() => expect(hook.current.status).toBe('ready'))
    expect([...hook.current.matchedIds]).toEqual(['2', '3'])
    expect([...hook.current.results.keys()]).toEqual(['2', '3'])
  })

  it('does not ask again for completed results and polls only rechecking items until they finish', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const load = vi.fn()
        .mockResolvedValueOnce(new Map([['1', result('1', true)], ['2', { ...result('2', false), freshness: 'RECHECKING' }]]))
        .mockResolvedValueOnce(new Map([['3', result('3', false)]]))
        .mockResolvedValueOnce(new Map([['2', result('2', true)]]))
      const wrapper = ({ children }: { children: ReactNode }) => <ConditionsContext value={value(load)}>{children}</ConditionsContext>
      const { result: hook, rerender } = renderHook(({ items }) => useConditionListResults(items), { wrapper, initialProps: { items: [item('1'), item('2')] } })
      await waitFor(() => expect(hook.current.rechecking).toBe(true))
      rerender({ items: [item('1'), item('2'), item('3')] })
      await waitFor(() => expect(load).toHaveBeenCalledTimes(2))
      await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
      await waitFor(() => expect(hook.current.rechecking).toBe(false))
      expect(load.mock.calls.map((call) => call[0].map((ref: AnnouncementRef) => ref.announcementId))).toEqual([['1', '2'], ['3'], ['2']])
      expect([...hook.current.matchedIds]).toEqual(['1', '2'])
      await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
      expect(load).toHaveBeenCalledTimes(3)
    } finally {
      vi.useRealTimers()
    }
  })

  it('asks again for the same items when the list is requested with new filters', async () => {
    const load = vi.fn(async (refs: readonly AnnouncementRef[]) => new Map(refs.map((ref) => [ref.announcementId, result(ref.announcementId, true)])))
    const wrapper = ({ children }: { children: ReactNode }) => <ConditionsContext value={value(load)}>{children}</ConditionsContext>
    const items = [item('1')]
    const { result: hook, rerender } = renderHook(({ listKey }) => useConditionListResults(items, null, listKey), { wrapper, initialProps: { listKey: 'a' } })
    await waitFor(() => expect(hook.current.status).toBe('ready'))
    rerender({ listKey: 'a' })
    rerender({ listKey: 'b' })
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2))
    expect(load.mock.calls[1][0].map((ref) => ref.announcementId)).toEqual(['1'])
  })

  it('keeps loaded results on failure and retries only the items it could not load', async () => {
    const load = vi.fn()
      .mockResolvedValueOnce(new Map([['1', result('1', true)]]))
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(new Map([['2', result('2', true)]]))
    const wrapper = ({ children }: { children: ReactNode }) => <ConditionsContext value={value(load)}>{children}</ConditionsContext>
    const { result: hook, rerender } = renderHook(({ items }) => useConditionListResults(items), { wrapper, initialProps: { items: [item('1')] } })
    await waitFor(() => expect(hook.current.status).toBe('ready'))
    rerender({ items: [item('1'), item('2')] })
    await waitFor(() => expect(hook.current.status).toBe('error'))
    expect(hook.current.results.get('1')).toEqual(result('1', true))
    act(() => hook.current.retry())
    await waitFor(() => expect(hook.current.status).toBe('ready'))
    expect(load.mock.calls.map((call) => call[0].map((ref: AnnouncementRef) => ref.announcementId))).toEqual([['1'], ['2'], ['2']])
  })

  it('is ready with no matches when there are no items', () => {
    const load = vi.fn()
    const wrapper = ({ children }: { children: ReactNode }) => <ConditionsContext value={value(load)}>{children}</ConditionsContext>
    const { result: hook } = renderHook(() => useConditionListResults([]), { wrapper })
    expect(hook.current.status).toBe('ready')
    expect(hook.current.matchedIds.size).toBe(0)
  })

  it('does nothing without saved conditions', () => {
    const load = vi.fn()
    const wrapper = ({ children }: { children: ReactNode }) => <ConditionsContext value={value(load, { hasProfile: false })}>{children}</ConditionsContext>
    const { result: hook } = renderHook(() => useConditionListResults([item('1')]), { wrapper })
    expect(hook.current.status).toBe('idle')
    expect(load).not.toHaveBeenCalled()
  })
})
