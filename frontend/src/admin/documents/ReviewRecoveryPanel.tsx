import { useEffect, useState } from 'react'
import { readRecoveryInputs, saveRecoveryInput, removeRecoveryInput, type RecoveryInput } from './reviewBrowserStore'
import styles from './DocumentReview.module.css'

export function ReviewRecoveryPanel({ context, json, note, revision, disabled, onRestore }: {
  context: string; json: string; note: string; revision: number; disabled: boolean;
  onRestore: (input: RecoveryInput) => void
}) {
  const [inputs, setInputs] = useState<RecoveryInput[]>([])
  const [selected, setSelected] = useState(''); const [error, setError] = useState(''); const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    let active = true
    setInputs([]); setSelected('')
    void readRecoveryInputs(context).then(rows => { if (active) { setInputs(rows); setSelected(rows[0]?.id ?? ''); setError('') } })
      .catch(cause => { if (active) setError(cause instanceof Error ? cause.message : '복구 기록을 읽지 못했습니다.') })
    return () => { active = false }
  }, [context, revision, refresh])
  return <details className={styles.card}><summary>새로고침 후에도 남는 입력 복구 기록</summary>
    <p>같은 대상·DB 버전·PDF의 최근 10개 입력을 이 브라우저에 보관합니다. 원본 PDF와 원문 확인 선택은 포함하지 않습니다. 원본을 다시 열어 추출하면 기록을 복원할 수 있습니다.</p>
    <button disabled={disabled || (!json.trim() && !note.trim())} onClick={() => {
      void saveRecoveryInput({ context, json, note }).then(() => { setRefresh(value => value + 1); setError('') }).catch(cause => setError(cause instanceof Error ? cause.message : '복구 기록 보관 실패'))
    }}>현재 입력 복구 기록 보관</button>
    <label>복원할 입력<select disabled={disabled || !inputs.length} value={selected} onChange={event => setSelected(event.target.value)}>
      {!inputs.length ? <option value="">이 PDF에 보관한 기록 없음</option> : inputs.map((input, index) => <option key={input.id} value={input.id}>{index + 1}. {new Date(input.updatedAt).toLocaleString('ko-KR')} · {input.note.slice(0, 40) || '메모 없음'}</option>)}
    </select></label>
    <button disabled={disabled || !selected} onClick={() => { const input = inputs.find(row => row.id === selected); if (input) onRestore(input) }}>선택한 입력 복원</button>
    <button disabled={disabled || !selected} onClick={() => {
      if (!window.confirm('선택한 복구 기록을 삭제할까요? 이 기록은 되돌릴 수 없습니다. 현재 입력과 다른 기록은 유지합니다.')) return
      void removeRecoveryInput(selected, context).then(() => setRefresh(value => value + 1)).catch(cause => setError(cause instanceof Error ? cause.message : '복구 기록 삭제 실패'))
    }}>선택한 복구 기록 삭제</button>
    {error ? <p role="alert">{error}</p> : null}
  </details>
}
