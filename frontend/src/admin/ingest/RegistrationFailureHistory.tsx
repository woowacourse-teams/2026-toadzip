import { useEffect, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { getRegistrationFailures, type RegistrationFailures } from './api'
import styles from './RegistrationRecovery.module.css'

export function RegistrationFailureHistory({ identifier }: { identifier: string }) {
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<RegistrationFailures | null>(null)
  const [error, setError] = useState('')
  const [reload, setReload] = useState(0)
  useEffect(() => {
    let active = true
    setResult(null); setError('')
    void getRegistrationFailures(identifier, page).then(value => { if (active) setResult(value) })
      .catch((cause: unknown) => { if (active) setError(cause instanceof Error ? cause.message : '실패 이력을 조회하지 못했습니다.') })
    return () => { active = false }
  }, [identifier, page, reload])
  return <section className={styles.history} aria-label="보류·실패 이력">
    <h4>보류·실패 이력</h4>
    <p>재처리 중 보완이 필요해 보류된 경우와 저장 실패를 기록합니다.</p>
    {!result && !error && <p role="status">실패 이력을 불러오고 있습니다.</p>}
    {error && <div role="alert"><p>{error}</p><Button data-design-system variant="secondary" onClick={() => setReload(value => value + 1)}>실패 이력 다시 조회</Button></div>}
    {result && <>
      {result.failures.length === 0 ? <p>기록된 보류·실패 이력이 없습니다. 이전에 발생한 보완 필요 상태는 기록되지 않았을 수 있습니다.</p> : <ul>{result.failures.map((failure, index) => <li key={index}>
        <time dateTime={failure.failedAt}>{new Date(failure.failedAt).toLocaleString('ko-KR')}</time> · {failure.actor ?? '이전 기록'}
        <p>{failure.detail}</p>
      </li>)}</ul>}
      {result.totalPages > 1 && <nav className={styles.actions} aria-label="등록 실패 이력 페이지">
        <Button data-design-system variant="secondary" disabled={page === 0} onClick={() => setPage(value => value - 1)}>이전 실패 이력</Button>
        <span>{page + 1} / {result.totalPages}</span>
        <Button data-design-system variant="secondary" disabled={page + 1 >= result.totalPages} onClick={() => setPage(value => value + 1)}>다음 실패 이력</Button>
      </nav>}
    </>}
  </section>
}
