import { useEffect, useRef, useState } from 'react'
import styles from './IngestFailurePanel.module.css'

export function FailureRetryButton({label, disabled, onRetry}: {
  label: string; disabled: boolean; onRetry: () => void
}) {
  const [confirming, setConfirming] = useState(false)
  const action = useRef<HTMLButtonElement>(null)
  const previousConfirmation = useRef(confirming)
  useEffect(() => {
    if (previousConfirmation.current === confirming) return
    previousConfirmation.current = confirming
    action.current?.focus()
  }, [confirming])
  if (!confirming) return <button ref={action} type="button" disabled={disabled}
    onClick={() => setConfirming(true)}>{label} 정제 다시 실행</button>
  return <div className={styles.executionScope} role="group" aria-label="정제 실행 범위 확인">
    <strong>저장된 {label} 원천 전체를 정제합니다.</strong>
    <p>선택한 오류 외의 데이터도 처리합니다. 외부 원천을 새로 수집하지 않으며, 실행 완료 후 이 오류의 결과를 따로 확인해야 합니다.</p>
    <div className={styles.workflow}>
      <button ref={action} type="button" disabled={disabled} onClick={() => { setConfirming(false); onRetry() }}>전체 {label} 정제 실행</button>
      <button type="button" onClick={() => setConfirming(false)}>취소</button>
    </div>
  </div>
}
