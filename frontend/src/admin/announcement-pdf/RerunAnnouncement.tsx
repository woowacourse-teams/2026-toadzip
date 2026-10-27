import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { rerunAnnouncement } from '../ingest/supplyMatchingApi'
import type { JsonWorkspace } from '../announcement-json/api'
import styles from './PdfCorrection.module.css'

type Outcome = { ok: true } | { ok: false; message: string }

/**
 * 보정 결과 화면에서 이 공고만 다시 정제해 오류가 풀렸는지 확인한다. 마이홈 원천이 있는 공고만 정제할 수 있다.
 * auto면 화면이 열리자마자 한 번 정제해, 보정 뒤 실패 기록이 미해결로 남지 않게 한다.
 */
export function RerunAnnouncement({ workspace, failureReturn, auto = false }: { workspace: JsonWorkspace; failureReturn: string | null; auto?: boolean }) {
  const [running, setRunning] = useState(false)
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const started = useRef(false)
  const identifier = workspace.detail.sourceIdentifier
  const refinable = Boolean(identifier) && (workspace.supply.rowSources ?? []).some(source => source.category === 'MYHOME_ANNOUNCEMENT')
  const run = () => { if (identifier) void rerun(identifier, setRunning, setOutcome) }
  // 결과 화면에 들어올 때 한 번만 실행한다.
  useEffect(() => {
    if (!auto || !refinable || !identifier || started.current) return
    started.current = true
    void rerun(identifier, setRunning, setOutcome)
  }, [auto, refinable, identifier])
  if (!refinable || !identifier) return null
  const failures = failureReturn ?? '/admin/failures?domain=announcement'
  // LH 보강에서 막히면 서버가 이 공고의 실패를 새로 기록하므로 원래 보던 오류 대신 이 공고의 오류 목록으로 안내한다.
  const announcementFailures = `/admin/failures?domain=announcement&keyword=${encodeURIComponent(identifier)}`
  return <section className={styles.rerun} aria-labelledby="rerun-title">
    <h3 id="rerun-title">{auto ? '다시 정제 결과' : '이 공고만 다시 정제'}</h3>
    <p className={styles.hint}>{auto ? '보정한 값으로 이 공고를 바로 다시 정제해 실패 기록을 정리합니다.' : '저장한 연결로 이 공고만 정제합니다.'} 하나라도 실패하면 정제 결과는 저장하지 않으며, LH 보강에서 막히면 그 원인을 오류 목록에 남깁니다.</p>
    {running && <p role="status" aria-busy="true">이 공고를 다시 정제하는 중…</p>}
    {outcome?.ok && <p role="status">정제를 마쳤습니다. 이 공고의 실패 기록은 해결됨으로 바뀌었습니다. <Link to={failures}>오류 목록에서 확인</Link></p>}
    {outcome && !outcome.ok && <div role="alert" className={styles.error}><p>아직 남은 실패가 있습니다. {outcome.message}</p>
      {/LH 보강 실패/.test(outcome.message)
        ? <p>PDF 보정만으로는 LH 공급 원본과 단지가 연결되지 않습니다. <Link className="admin-primary" to={`/admin/failures/lh-links/${encodeURIComponent(identifier)}`}>LH 행 연결로 처리</Link> <Link to={announcementFailures}>이 공고의 오류 목록 보기</Link></p>
        : <p><Link className="admin-primary" to={announcementFailures}>이 공고의 오류 목록에서 처리</Link></p>}</div>}
    {!running && <Button data-design-system className={styles.secondaryButton} onClick={run}>{outcome || auto ? '다시 정제' : '이 공고만 다시 정제'}</Button>}
  </section>
}

async function rerun(identifier: string, setRunning: (running: boolean) => void, setOutcome: (outcome: Outcome | null) => void) {
  setRunning(true); setOutcome(null)
  try { await rerunAnnouncement(identifier); setOutcome({ ok: true }) }
  catch (cause) { setOutcome({ ok: false, message: cause instanceof Error ? cause.message : '정제를 실행하지 못했습니다.' }) }
  finally { setRunning(false) }
}
