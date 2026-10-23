import { useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { getLhSupplyLinks, releaseLhSupplyLink, saveLhSupplyLinks,
  type LhLinkMyHomeRow, type LhLinkSupplyRow, type LhSupplyLinkView } from './supplyMatchingApi'
import styles from './AnnouncementSupplyMatchingForm.module.css'

// 결과 안내는 버튼을 누른 자리에 보여 준다. 저장은 폼 아래, 해제는 해당 LH 행이다.
type Feedback = { at: string; kind: 'notice' | 'error'; text: string }
const FORM = 'form'

/** 단지명 표기가 달라 자동으로 붙지 않는 LH 공급 원본 행을 마이홈 원천행에 근거와 함께 연결한다. */
export function LhSupplyLinkEditor({ identifier, disabled = false, onBusy }: {
  identifier: string; disabled?: boolean; onBusy?: (busy: boolean) => void
}) {
  const [view, setView] = useState<LhSupplyLinkView | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  const [revision, setRevision] = useState(0)
  const [myhomeRow, setMyhomeRow] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [sourceUrl, setSourceUrl] = useState('')
  const [reason, setReason] = useState('')
  const [releasing, setReleasing] = useState<string | null>(null)
  const [releaseReason, setReleaseReason] = useState('')
  const [saving, setSaving] = useState(false)
  const pending = useRef(false)
  const alive = useRef(false)
  const form = useRef<HTMLFieldSetElement>(null)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setLoadError(''); setView(null); setSelected([]); setReleasing(null)
    void getLhSupplyLinks(identifier, controller.signal).then(result => {
      if (!controller.signal.aborted) setView(result)
    }).catch(cause => { if (!controller.signal.aborted) setLoadError(message(cause)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [identifier, revision])

  async function run(at: string, action: (current: LhSupplyLinkView) => Promise<LhSupplyLinkView>, done: string) {
    if (pending.current || disabled || !view) return
    pending.current = true; setSaving(true); onBusy?.(true); setFeedback(null)
    try {
      const result = await action(view)
      if (alive.current) {
        setView(result); setSelected([]); setReleasing(null); setReleaseReason('')
        setFeedback({ at, kind: 'notice', text: done })
      }
    } catch (cause) { if (alive.current) setFeedback({ at, kind: 'error', text: message(cause) }) }
    finally { pending.current = false; if (alive.current) { setSaving(false); onBusy?.(false) } }
  }

  function save() {
    if (!myhomeRow || selected.length === 0) {
      setFeedback({ at: FORM, kind: 'error', text: '연결할 마이홈 원천행과 LH 공급행을 선택해 주세요.' }); return
    }
    if (!reason.trim() || !/^https?:\/\//i.test(sourceUrl.trim())) {
      setFeedback({ at: FORM, kind: 'error', text: '공식 출처 URL과 확인 근거를 입력해 주세요.' }); return
    }
    void run(FORM, current => saveLhSupplyLinks(identifier, current, myhomeRow, selected, sourceUrl.trim(), reason.trim()),
      'LH 공급 연결을 저장했습니다. 위의 공고 정제 다시 실행으로 반영해 주세요.')
  }

  function release(row: LhLinkSupplyRow) {
    if (!releaseReason.trim()) {
      setFeedback({ at: row.rowIdentifier, kind: 'error', text: '연결을 해제할 이유를 입력해 주세요.' }); return
    }
    void run(row.rowIdentifier, current => releaseLhSupplyLink(identifier, current, row.rowIdentifier, releaseReason.trim()),
      'LH 공급 연결을 해제했습니다. 다음 정제부터 자동 연결 규칙을 사용합니다.')
  }

  // 재확인 행을 다시 저장할 수 있게 이전 연결과 근거 URL을 폼에 채운다.
  function prepareReconfirm(row: LhLinkSupplyRow) {
    if (!row.savedLink) return
    setSelected([row.rowIdentifier]); setMyhomeRow(row.savedLink.myhomeRowIdentifier)
    setSourceUrl(row.savedLink.sourceUrl); setReason(''); setFeedback(null)
    form.current?.scrollIntoView?.({ block: 'start', behavior: 'smooth' })
  }

  const busy = disabled || saving
  return <section className={styles.workspace} aria-label="LH 공급 원본 연결">
    <header className={styles.heading}>
      <div><span className={styles.eyebrow}>공고 {identifier}</span><h4>LH 공급 원본 연결</h4></div>
      <button className={styles.secondary} type="button" disabled={loading || busy}
        onClick={() => { setFeedback(null); setRevision(value => value + 1) }}>연결 정보 새로고침</button>
    </header>
    <p className={styles.description}>LH 단지명 표기가 달라 자동으로 붙지 않는 공급행을 공고문으로 확인해 마이홈 원천행에 연결하세요.
      저장한 연결은 단지명 비교보다 먼저 쓰이며, 어느 쪽 원천이 바뀌면 다시 확인해야 합니다.</p>
    {loading && <p role="status">LH 공급 연결 정보를 불러오는 중…</p>}
    {loadError && <p role="alert">{loadError}</p>}
    {view && <>
      <Summary rows={view.lhRows} />
      <fieldset className={styles.card} disabled={busy}>
        <legend>LH 공급 원본 행</legend>
        {sortByAttention(view.lhRows).map(row => <div key={row.rowIdentifier} className={styles.saved}>
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
            {row.savedLink.reviewRequired && <button className={styles.secondary} type="button"
              onClick={() => prepareReconfirm(row)}>이 연결 다시 확인해 저장</button>}
            {releasing === row.rowIdentifier
              ? <div className={styles.evidence}>
                <label>해제 이유<textarea value={releaseReason} maxLength={2000} rows={2}
                  placeholder="잘못 연결한 근거나 연결이 더는 필요 없는 이유" onChange={event => setReleaseReason(event.target.value)} /></label>
                <p>해제해도 이미 등록한 공급행은 유지되며, 다음 정제부터 자동 연결 규칙을 사용합니다.</p>
                <div className={styles.heading}>
                  <button className={styles.secondary} type="button" onClick={() => { setReleasing(null); setFeedback(null) }}>취소</button>
                  <button className={styles.secondary} type="button" onClick={() => release(row)}>해제</button>
                </div>
              </div>
              : <button className={styles.secondary} type="button"
                onClick={() => { setReleasing(row.rowIdentifier); setReleaseReason(''); setFeedback(null) }}>저장한 연결 해제</button>}
          </>}
          <FeedbackMessage feedback={feedback} at={row.rowIdentifier} />
        </div>)}
      </fieldset>
      <fieldset ref={form} className={styles.evidence} disabled={busy}>
        <legend>연결할 마이홈 원천행과 근거</legend>
        <label>마이홈 원천행<select value={myhomeRow} onChange={event => setMyhomeRow(event.target.value)}>
          <option value="">원천행을 선택해 주세요</option>
          {view.myhomeRows.map(row => <option key={row.rowIdentifier} value={row.rowIdentifier} disabled={row.splitCorrected}>
            {myhomeLabel(row)}{row.splitCorrected ? ' (복수 공급행 보정 행은 연결할 수 없음)' : ''}
          </option>)}
        </select></label>
        <label>공식 출처 URL<input type="url" value={sourceUrl} maxLength={2048} onChange={event => setSourceUrl(event.target.value)} /></label>
        <label>확인 근거<textarea value={reason} maxLength={2000} rows={3} placeholder="공고문 쪽수와 단지 대응 근거"
          onChange={event => setReason(event.target.value)} /></label>
      </fieldset>
      <footer className={styles.footer}>
        <p>저장·해제는 다음 정제와 LH 보강부터 반영됩니다. 이미 등록한 공급행은 바로 바꾸지 않습니다.</p>
        <Button disabled={busy || !myhomeRow || selected.length === 0} onClick={save}>
          {saving ? '저장 중…' : `선택한 LH 행 ${selected.length}개 연결 저장`}</Button>
      </footer>
      <FeedbackMessage feedback={feedback} at={FORM} />
    </>}
  </section>
}

function FeedbackMessage({ feedback, at }: { feedback: Feedback | null; at: string }) {
  const element = useRef<HTMLParagraphElement>(null)
  const shown = feedback?.at === at ? feedback : null
  // 해제한 행은 정렬 순서가 바뀌어 다른 위치로 이동하므로 안내가 화면 안에 들어오게 한다.
  useEffect(() => { if (shown) element.current?.scrollIntoView?.({ block: 'center', behavior: 'smooth' }) }, [shown])
  if (!shown) return null
  if (shown.kind === 'error') return <p ref={element} role="alert">{shown.text}</p>
  return <p ref={element} className={styles.notice} role="status">{shown.text}</p>
}

function Summary({ rows }: { rows: LhLinkSupplyRow[] }) {
  const review = rows.filter(row => row.savedLink?.reviewRequired).length
  const unlinked = rows.filter(row => attention(row) === 1).length
  if (review === 0 && unlinked === 0) {
    return <p className={styles.notice}>LH 공급행 {rows.length}개가 모두 마이홈 원천행에 연결되어 있습니다. 연결할 행이 없습니다.</p>
  }
  return <p className={styles.warning}>
    확인할 LH 공급행: 재확인 필요 {review}개 · 연결 없음 {unlinked}개 (전체 {rows.length}개). 확인할 행을 위에 먼저 보여 줍니다.
  </p>
}

// 재확인 필요 → 연결 없음 → 저장 연결 → 자동 연결 순으로 처리할 행을 먼저 보여 준다.
function attention(row: LhLinkSupplyRow): number {
  if (row.savedLink?.reviewRequired) return 0
  if (!row.savedLink && !row.linkedRowIdentifier) return 1
  if (row.savedLink) return 2
  return 3
}
function sortByAttention(rows: LhLinkSupplyRow[]): LhLinkSupplyRow[] {
  return rows.map((row, index) => ({ row, index }))
    .sort((left, right) => attention(left.row) - attention(right.row) || left.index - right.index)
    .map(value => value.row)
}

function LinkStatus({ row, rows }: { row: LhLinkSupplyRow; rows: LhLinkMyHomeRow[] }) {
  if (row.savedLink?.reviewRequired) {
    return <p className={styles.warning}>
      저장한 연결 재확인 필요: 저장한 뒤 LH 행이나 마이홈 원천행의 단지명·주택형·면적·모집수가 바뀌어 이 연결을 쓰지 않습니다.
      이전 연결 → {linkedLabel(rows, row.savedLink.myhomeRowIdentifier)}
    </p>
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
