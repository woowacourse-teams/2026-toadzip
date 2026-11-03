import { useCallback, useEffect, useRef, useState } from 'react'
import { ManagementError } from '../management/api'
import { deleteCorrectionDraft, draftConflictCode, saveCorrectionDraft, type DraftSaveInput } from './correctionDraftApi'

export type DraftStatus = { state: 'idle' } | { state: 'pending' } | { state: 'saving' } | { state: 'saved'; at: string }
  | { state: 'failed'; message: string } | { state: 'conflict' }
export const draftDelay = 1500

/**
 * 공고 보정 초안의 자동·수동 임시저장. 마지막 편집 뒤 draftDelay가 지나면 저장하고, 저장 중에 또 바뀌면 끝난 뒤 이어서 저장한다.
 * 실패해도 화면 입력은 그대로 두고 다시 시도할 수 있다. 다른 탭이 더 최신 초안을 저장했으면(409) 멈추고 덮어쓰지 않는다.
 */
export function useCorrectionDraft(id: string, enabled: boolean) {
  const [status, setStatus] = useState<DraftStatus>({ state: 'idle' })
  const revision = useRef<number | null>(null)
  const latest = useRef<Omit<DraftSaveInput, 'expectedRevision'> | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const running = useRef(false), again = useRef(false), stopped = useRef(false)

  const flush = useCallback(async () => {
    if (timer.current) { clearTimeout(timer.current); timer.current = null }
    if (!latest.current || stopped.current) return
    if (running.current) { again.current = true; return }
    running.current = true; setStatus({ state: 'saving' })
    const input = latest.current
    try {
      const saved = await saveCorrectionDraft(id, { ...input, expectedRevision: revision.current })
      revision.current = saved.revision
      if (latest.current === input) setStatus({ state: 'saved', at: saved.updatedAt })
      else again.current = true
    } catch (cause) {
      if (cause instanceof ManagementError && cause.status === 409 && cause.code === draftConflictCode) { stopped.current = true; setStatus({ state: 'conflict' }) }
      else setStatus({ state: 'failed', message: cause instanceof Error ? cause.message : '임시저장하지 못했습니다.' })
      again.current = false
    } finally {
      running.current = false
      if (again.current && !stopped.current) { again.current = false; void flush() }
    }
  }, [id])

  /** 편집 상태를 받아 두고 draftDelay 뒤 저장한다. */
  const schedule = useCallback((input: Omit<DraftSaveInput, 'expectedRevision'>) => {
    latest.current = input
    if (!enabled || stopped.current) return
    setStatus(previous => previous.state === 'saving' ? previous : { state: 'pending' })
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => void flush(), draftDelay)
  }, [enabled, flush])

  /** 서버에서 읽은 초안을 이어서 쓰거나(revision) 초안 없이 새로 시작한다(null). 충돌로 멈춘 저장을 다시 연다. */
  const adopt = useCallback((next: number | null) => { revision.current = next; stopped.current = false; setStatus({ state: 'idle' }) }, [])
  /** 적용 성공 뒤나 폐기할 때 지운다. 지운 뒤 같은 화면의 편집은 첫 저장으로 다시 저장한다. */
  const remove = useCallback(async (expected = revision.current) => {
    if (timer.current) { clearTimeout(timer.current); timer.current = null }
    latest.current = null
    if (expected !== null) await deleteCorrectionDraft(id, expected)
    revision.current = null; setStatus({ state: 'idle' })
  }, [id])
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current) }, [])
  return { status, schedule, saveNow: flush, adopt, remove }
}
