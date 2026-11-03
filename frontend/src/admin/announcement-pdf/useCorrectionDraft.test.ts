import { act, renderHook } from '@testing-library/react'
import { expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ save: vi.fn(), remove: vi.fn() }))
vi.mock('./correctionDraftApi', async original => ({ ...await original<typeof import('./correctionDraftApi')>(), saveCorrectionDraft: mocks.save, deleteCorrectionDraft: mocks.remove }))

import type { DraftPayload } from './correctionDraftApi'
import { useCorrectionDraft } from './useCorrectionDraft'

const input = { baseSnapshotToken: 'current', payload: { version: 1 } as unknown as DraftPayload }

it('저장 중에 지우면 그 저장이 끝나기를 기다려 저장이 만든 revision으로 지운다', async () => {
  let finish = (_: { revision: number; updatedAt: string }) => {}
  mocks.save.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  mocks.remove.mockResolvedValue(undefined)
  const { result } = renderHook(() => useCorrectionDraft('29', true))
  act(() => { result.current.adopt(3) })
  act(() => { result.current.schedule(input) })
  let saving: Promise<void> | undefined
  act(() => { saving = result.current.saveNow() })
  expect(mocks.save).toHaveBeenCalledWith('29', { ...input, expectedRevision: 3 })

  let removed: Promise<void> | undefined
  act(() => { removed = result.current.remove() })
  await Promise.resolve()
  expect(mocks.remove).not.toHaveBeenCalled()

  await act(async () => { finish({ revision: 4, updatedAt: '2026-11-03T00:00:00Z' }); await saving; await removed })
  expect(mocks.remove).toHaveBeenCalledExactlyOnceWith('29', 4)
  expect(mocks.save).toHaveBeenCalledTimes(1)
  expect(result.current.status).toEqual({ state: 'idle' })
})
