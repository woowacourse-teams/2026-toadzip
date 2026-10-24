import { useState } from 'react'
import { Link } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { rerunAnnouncement } from '../ingest/supplyMatchingApi'
import type { JsonWorkspace } from '../announcement-json/api'
import styles from './PdfCorrection.module.css'

type Outcome = { ok: true } | { ok: false; message: string }

/** 보정 결과 화면에서 이 공고만 다시 정제해 오류가 풀렸는지 바로 확인한다. 마이홈 원천이 있는 공고만 정제할 수 있다. */
export function RerunAnnouncement({ workspace, failureReturn }: { workspace: JsonWorkspace; failureReturn: string | null }) {
  const [running, setRunning] = useState(false)
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const identifier = workspace.detail.sourceIdentifier
  if (!identifier || !(workspace.supply.rowSources ?? []).some(source => source.category === 'MYHOME_ANNOUNCEMENT')) return null
  async function run() {
    setRunning(true); setOutcome(null)
    try { await rerunAnnouncement(identifier); setOutcome({ ok: true }) }
    catch (cause) { setOutcome({ ok: false, message: cause instanceof Error ? cause.message : '정제를 실행하지 못했습니다.' }) }
    finally { setRunning(false) }
  }
  const failures = failureReturn ?? '/admin/failures?domain=announcement'
  // LH 보강에서 막히면 서버가 이 공고의 실패를 새로 기록하므로 원래 보던 오류 대신 이 공고의 오류 목록으로 안내한다.
  const announcementFailures = `/admin/failures?domain=announcement&keyword=${encodeURIComponent(identifier)}`
  return <section className={styles.rerun} aria-labelledby="rerun-title">
    <h3 id="rerun-title">이 공고만 다시 정제</h3>
    <p className={styles.hint}>저장한 연결로 이 공고만 정제합니다. 하나라도 실패하면 아무것도 저장하지 않으며, LH 보강에서 막히면 그 원인을 오류 목록에 남깁니다.</p>
    <Button data-design-system className={styles.secondaryButton} disabled={running} aria-busy={running || undefined} onClick={() => void run()}>
      {running ? '정제하는 중…' : '이 공고만 다시 정제'}
    </Button>
    {outcome?.ok && <p role="status">정제를 마쳤습니다. 이 공고의 오류는 해결됨으로 바뀌었습니다. <Link to={failures}>오류 목록에서 확인</Link></p>}
    {outcome && !outcome.ok && <p role="alert" className={styles.error}>{outcome.message} <Link to={announcementFailures}>이 공고의 오류 목록 보기</Link></p>}
  </section>
}
