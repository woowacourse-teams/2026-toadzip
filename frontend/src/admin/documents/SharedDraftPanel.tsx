import { useEffect, useRef, useState } from 'react'
import { ManagementError } from '../management/api'
import type { ManagementResource } from '../management/managementContract'
import { archiveDocument } from './documentArchiveApi'
import { loadSharedDraft, saveSharedDraft, type SharedDraft, type SharedDraftInput } from './sharedDraftApi'
import styles from './DocumentReview.module.css'

export function SharedDraftPanel({ resource, id, target, input, file, disabled, onRestore, onBusyChange }: {
  resource: ManagementResource; id: number; target: string; input: SharedDraftInput | null; file: File | null; disabled: boolean;
  onRestore: (draft: SharedDraft, signal: AbortSignal) => Promise<void>; onBusyChange: (busy: boolean) => void
}) {
  const [draft, setDraft] = useState<SharedDraft | null>(null)
  const [revision, setRevision] = useState(0); const [conflict, setConflict] = useState(false)
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => { request.current?.abort(); onBusyChange(false) }, [onBusyChange])
  async function run(action: 'load' | 'save' | 'restore') {
    if (busy || disabled || action === 'save' && !input) return
    const controller = new AbortController(); request.current = controller
    setBusy(true); onBusyChange(true); setError(''); setNotice('')
    try {
      if (action === 'load') {
        const next = await loadSharedDraft(resource, id, target, controller.signal)
        if (controller.signal.aborted) return
        setDraft(next); setRevision(next?.revision ?? 0); setConflict(false)
        setNotice(next ? '최신 공유 초안을 확인했습니다. 현재 입력은 유지됩니다. 내용을 비교한 뒤 복원하거나 저장하세요.' : '이 대상의 공유 초안이 없습니다.')
      }
      if (action === 'save' && file && input) {
        if (conflict) return
        const sha = await archiveDocument(resource, id, file, input.sourceUrl, controller.signal)
        if (sha !== input.sha256) throw new Error('선택한 원본 해시가 달라 공유 초안을 저장하지 않았습니다.')
        const saved = await saveSharedDraft(resource, id, input, revision, controller.signal)
        if (controller.signal.aborted) return
        setDraft(saved); setRevision(saved.revision); setNotice('공유 초안을 서버에 저장했습니다. 등록값 수정과 원문 확인 선택은 저장하지 않았습니다.')
      }
      if (action === 'restore' && draft) {
        await onRestore(draft, controller.signal)
        if (!controller.signal.aborted) setNotice('공유 원본·입력을 복원했습니다. OCR과 수정안 검증·원문 확인을 다시 수행하세요.')
      }
    } catch (cause) {
      if (controller.signal.aborted) return
      setError(cause instanceof Error ? cause.message : '공유 초안을 처리하지 못했습니다.')
      if (cause instanceof ManagementError && cause.status === 409) setConflict(true)
    } finally { if (!controller.signal.aborted) { setBusy(false); onBusyChange(false) } }
  }
  return <details className={styles.card}><summary>다른 관리자와 공유하는 작업 초안</summary>
    <p>PDF 원본·추출 쪽수·추가된 수정안·메모를 서버에 보관합니다. 수기 입력은 먼저 수정안에 추가하세요. 다른 관리자와 수정이 겹치면 덮어쓰지 않습니다.</p>
    <div className={styles.actions}><button disabled={disabled || busy} onClick={() => void run('load')}>최신 공유 초안 확인</button>
      <button disabled={disabled || busy || !input || !file || conflict} onClick={() => void run('save')}>현재 작업을 서버 초안으로 저장</button>
      <button disabled={disabled || busy || !draft} onClick={() => void run('restore')}>공유 초안 복원</button></div>
    {draft ? <p>revision {draft.revision} · {draft.actor} · {new Date(draft.updatedAt).toLocaleString('ko-KR')} · DB 버전 {draft.documentVersion}<br />{draft.note}</p> : null}
    {busy ? <p role="status">공유 초안 처리 중…</p> : null}{error ? <p role="alert">{error}</p> : null}{notice ? <p role="status">{notice}</p> : null}
  </details>
}
