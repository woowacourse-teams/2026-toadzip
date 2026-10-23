import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { getLhSupplyLinks, releaseLhSupplyLink, saveLhSupplyLinks,
  type LhLinkMyHomeRow, type LhLinkSupplyRow, type LhSupplyLinkView } from './supplyMatchingApi'
import styles from './AnnouncementSupplyMatchingForm.module.css'

/** 단지명 표기가 달라 자동으로 붙지 않는 LH 공급 원본 행을 마이홈 원천행에 근거와 함께 연결한다. */
export function LhSupplyLinkEditor({ identifier, disabled = false, onBusy }: {
  identifier: string; disabled?: boolean; onBusy?: (busy: boolean) => void
}) {
  const [view, setView] = useState<LhSupplyLinkView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [revision, setRevision] = useState(0)
  const [myhomeRow, setMyhomeRow] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [sourceUrl, setSourceUrl] = useState('')
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)
  const pending = useRef(false)
  const alive = useRef(false)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setView(null); setSelected([])
    void getLhSupplyLinks(identifier, controller.signal).then(result => {
      if (!controller.signal.aborted) setView(result)
    }).catch(cause => { if (!controller.signal.aborted) setError(message(cause)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [identifier, revision])

  async function run(action: (current: LhSupplyLinkView) => Promise<LhSupplyLinkView>, done: string) {
    if (pending.current || disabled || !view) return
    pending.current = true; setSaving(true); onBusy?.(true); setError(''); setNotice('')
    try {
      const result = await action(view)
      if (alive.current) { setView(result); setSelected([]); setNotice(done) }
    } catch (cause) { if (alive.current) setError(message(cause)) }
    finally { pending.current = false; if (alive.current) { setSaving(false); onBusy?.(false) } }
  }

  function save() {
    if (!myhomeRow || selected.length === 0) { setError('연결할 마이홈 원천행과 LH 공급행을 선택해 주세요.'); return }
    if (!reason.trim() || !/^https?:\/\//i.test(sourceUrl.trim())) { setError('공식 출처 URL과 확인 근거를 입력해 주세요.'); return }
    void run(current => saveLhSupplyLinks(identifier, current, myhomeRow, selected, sourceUrl.trim(), reason.trim()),
      'LH 공급 연결을 저장했습니다. 다음 정제와 LH 보강부터 이 연결을 사용합니다.')
  }

  function release(row: LhLinkSupplyRow) {
    if (!reason.trim()) { setError('연결을 해제할 이유를 확인 근거에 입력해 주세요.'); return }
    if (!window.confirm('저장한 LH 공급 연결을 해제할까요? 이미 등록한 공급행은 유지되며 다음 정제부터 자동 연결 규칙을 사용합니다.')) return
    void run(current => releaseLhSupplyLink(identifier, current, row.rowIdentifier, reason.trim()),
      'LH 공급 연결을 해제했습니다. 다음 정제부터 자동 연결 규칙을 사용합니다.')
  }

  const busy = disabled || saving
  return <section className={styles.workspace} aria-label="LH 공급 원본 연결">
    <header className={styles.heading}>
      <div><span className={styles.eyebrow}>공고 {identifier}</span><h4>LH 공급 원본 연결</h4></div>
      <button className={styles.secondary} type="button" disabled={loading || busy}
        onClick={() => { setNotice(''); setRevision(value => value + 1) }}>연결 정보 새로고침</button>
    </header>
    <p className={styles.description}>LH 단지명 표기가 달라 자동으로 붙지 않는 공급행을 공고문으로 확인해 마이홈 원천행에 연결하세요.
      저장한 연결은 단지명 비교보다 먼저 쓰이며, 어느 쪽 원천이 바뀌면 다시 확인해야 합니다.</p>
    {loading && <p role="status">LH 공급 연결 정보를 불러오는 중…</p>}
    {error && <p role="alert">{error}</p>}
    {notice && <p className={styles.notice} role="status">{notice}</p>}
    {view && <>
      <fieldset className={styles.card} disabled={busy}>
        <legend>LH 공급 원본 행</legend>
        {view.lhRows.map(row => <div key={row.rowIdentifier} className={styles.saved}>
          <label className={styles.remember}>
            <input type="checkbox" checked={selected.includes(row.rowIdentifier)} onChange={event => setSelected(previous =>
              event.target.checked ? [...previous, row.rowIdentifier] : previous.filter(value => value !== row.rowIdentifier))} />
            <strong>{row.complexLabel ?? '단지명 없음'} · {row.typeName ?? '주택형 없음'}</strong>
          </label>
          <p>전용 {row.exclusiveArea ?? '미제공'} · 공급 {row.supplyArea ?? '미제공'} · 모집 {row.suppliedUnitCount ?? '미제공'}</p>
          <LinkStatus row={row} rows={view.myhomeRows} />
          {row.savedLink && <>
            <p>근거: {row.savedLink.reason} · <a href={row.savedLink.sourceUrl} target="_blank" rel="noreferrer">공식 근거 보기</a></p>
            <p>기록: {row.savedLink.actor} · {new Date(row.savedLink.updatedAt).toLocaleString('ko-KR')}</p>
            <button className={styles.secondary} type="button" onClick={() => release(row)}>저장한 연결 해제</button>
          </>}
        </div>)}
      </fieldset>
      <fieldset className={styles.evidence} disabled={busy}>
        <legend>연결할 마이홈 원천행과 근거</legend>
        <label>마이홈 원천행<select value={myhomeRow} onChange={event => setMyhomeRow(event.target.value)}>
          <option value="">원천행을 선택해 주세요</option>
          {view.myhomeRows.map(row => <option key={row.rowIdentifier} value={row.rowIdentifier} disabled={row.splitCorrected}>
            {myhomeLabel(row)}{row.splitCorrected ? ' (복수 공급행 보정 행은 연결할 수 없음)' : ''}
          </option>)}
        </select></label>
        <label>공식 출처 URL<input type="url" value={sourceUrl} maxLength={2048} onChange={event => setSourceUrl(event.target.value)} /></label>
        <label>확인 근거<textarea value={reason} maxLength={2000} rows={3} placeholder="공고문 쪽수와 단지 대응 근거 또는 연결 해제 이유"
          onChange={event => setReason(event.target.value)} /></label>
      </fieldset>
      <footer className={styles.footer}>
        <p>저장·해제는 다음 정제와 LH 보강부터 반영됩니다. 이미 등록한 공급행은 바로 바꾸지 않습니다.</p>
        <Button disabled={busy || !myhomeRow || selected.length === 0} onClick={save}>
          {saving ? '저장 중…' : '선택한 LH 행 연결 저장'}</Button>
      </footer>
    </>}
  </section>
}

function LinkStatus({ row, rows }: { row: LhLinkSupplyRow; rows: LhLinkMyHomeRow[] }) {
  if (row.savedLink?.reviewRequired) {
    return <p className={styles.warning}>저장한 연결 재확인 필요: 원천이 바뀌어 연결을 쓰지 않습니다. 확인 후 다시 저장해 주세요.</p>
  }
  if (row.savedLink) return <p className={styles.notice}>저장한 연결 사용 중 → {linkedLabel(rows, row.savedLink.myhomeRowIdentifier)}</p>
  if (row.linkedRowIdentifier) return <p className={styles.notice}>자동 연결 → {linkedLabel(rows, row.linkedRowIdentifier)}</p>
  return <p className={styles.warning}>연결된 마이홈 원천행 없음</p>
}

function linkedLabel(rows: LhLinkMyHomeRow[], identifier: string): string {
  const row = rows.find(value => value.rowIdentifier === identifier)
  return row ? myhomeLabel(row) : '현재 원천에 없는 행'
}
function myhomeLabel(row: LhLinkMyHomeRow): string {
  return `${row.complexName ?? '단지명 없음'} · ${row.housingTypeName ?? '유형 없음'} · 모집 ${row.supplyHouseholdCount ?? '미제공'}`
}
function message(cause: unknown): string { return cause instanceof Error ? cause.message : 'LH 공급 연결을 처리하지 못했습니다.' }
