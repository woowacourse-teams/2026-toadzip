import { useEffect, useState } from 'react'
import { isRecord } from '../management/managementContract'
import { StoredDataTable } from '../shared/StoredDataTable'
import { isSourceCategory } from '../ingest/sourceApi'
import { sourceFieldLabel } from '../ingest/sourcePresentation'
import { loadSupplyReviews, reviewFieldLabels, saveSupplyReview, type ReviewSourceScope, type SupplyReviewOverview } from './supplyReviewApi'
import type { SupplyWorkspace } from './supplyCorrectionApi'
import styles from './SupplyCorrection.module.css'

const metadata = new Set(['id', 'source_id', 'collected_at', 'source_order', 'collection_record_id', 'consecutive_miss_count', 'last_seen_run_id', 'last_seen_at'])
const statusLabels: Record<string, string> = { VERIFIED: '선택 범위 검토 완료', ON_HOLD: '판단 보류', STALE: '재검토 필요' }
export type ReviewEvidence = { note: string; sourceUrl: string; sourcePage: string | null }
export function SupplyReviewPanel({ id, workspace, sourceUrl, disabled, onBusyChange, onDirtyChange, evidence = null, pending = [], onReviewed }: {
  id: string; workspace: SupplyWorkspace; sourceUrl: string; disabled: boolean; onBusyChange: (value: boolean) => void; onDirtyChange?: (value: boolean) => void
  /** 방금 적용한 보정의 근거. 검토 폼의 기본값으로 쓰며 미저장 입력으로 보지 않는다. */
  evidence?: ReviewEvidence | null
  /** 방금 적용한 보정이 바꾼 행·조건 키. 순서대로 대상으로 고르며, 확인한 항목은 사람이 직접 체크한다. */
  pending?: string[]
  /** 바뀐 항목을 모두 기록했거나, 바뀐 항목 없이 기록을 저장했을 때 호출한다. */
  onReviewed?: () => void
}) {
  const [remaining, setRemaining] = useState(pending)
  const [baseline, setBaseline] = useState(() => evidence
    ? { note: evidence.note, url: evidence.sourceUrl, page: evidence.sourcePage ?? '' } : { note: '', url: sourceUrl, page: '' })
  const [overview, setOverview] = useState<SupplyReviewOverview | null>(null)
  const [target, setTarget] = useState(''); const [fields, setFields] = useState<string[]>([])
  const [scopes, setScopes] = useState<ReviewSourceScope[]>([])
  const [outcome, setOutcome] = useState<'VERIFIED' | 'ON_HOLD'>('VERIFIED')
  const [note, setNote] = useState(baseline.note); const [url, setUrl] = useState(baseline.url); const [page, setPage] = useState(baseline.page)
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const [attempt, setAttempt] = useState(0)
  const queue = overview ? remaining.filter(key => key in overview.values) : []
  const firstPending = queue[0] ?? ''
  useEffect(() => { if (firstPending) setTarget(current => current || firstPending) }, [firstPending])
  useEffect(() => {
    const controller = new AbortController()
    void loadSupplyReviews(id, controller.signal).then(value => { if (!controller.signal.aborted) { setOverview(value); setError('') } })
      .catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '검토 기록을 불러오지 못했습니다.') })
    return () => controller.abort()
  }, [id, workspace.snapshotToken, attempt])
  useEffect(() => {
    onDirtyChange?.(note !== baseline.note || page !== baseline.page || url !== baseline.url)
    return () => onDirtyChange?.(false)
  }, [note, page, url, baseline, onDirtyChange])
  function targetName(key: string) {
    for (const row of workspace.rows) {
      if (row.key === key) return `${row.complexName} · ${row.typeName}`
      const condition = row.targets.find(item => item.key === key)
      if (condition) return `${row.typeName} · ${condition.target} ${condition.supplyRank ?? ''}`
    }
    return `사라진 대상 (${key})`
  }
  async function submit() {
    setBusy(true); onBusyChange(true); setError(''); setNotice('')
    try {
      const saved = await saveSupplyReview(id, { snapshotToken: workspace.snapshotToken, target, fields, outcome,
        note: note.trim(), sourceUrl: url.trim(), sourcePage: page.trim() || null, sources: scopes })
      const next = remaining.filter(key => key !== target); const nextQueue = next.filter(key => key in saved.values)
      setOverview(saved); setRemaining(next)
      if (nextQueue.length) {
        // 같은 보정의 다음 바뀐 항목으로 넘어간다. 근거는 유지하고 확인 항목은 다시 고르게 한다.
        setTarget(nextQueue[0]); setFields([]); setScopes([])
        setNotice('선택한 범위의 검토 기록을 저장했습니다. 다음 바뀐 항목에서 실제로 확인한 항목을 고르세요.')
        return
      }
      setNotice('선택한 범위의 검토 기록을 저장했습니다. 다른 항목의 검토 상태는 확정하지 않습니다.')
      if (queue.length) { setTarget(''); setFields([]); setScopes([]) }
      setBaseline({ note: '', url: sourceUrl, page: '' }); setNote(''); setPage(''); setUrl(sourceUrl); onReviewed?.()
    } catch (cause) { setError(cause instanceof Error ? cause.message : '검토 기록을 저장하지 못했습니다.') }
    finally { setBusy(false); onBusyChange(false) }
  }
  function sourceField(category: string, sourceKey: string, field: string, checked: boolean) {
    setScopes(current => {
      const scope = current.find(item => item.category === category && item.sourceKey === sourceKey)
      const selected = checked ? [...(scope?.fields ?? []), field] : (scope?.fields ?? []).filter(item => item !== field)
      return [...current.filter(item => item !== scope), ...(selected.length ? [{ category, sourceKey, fields: selected }] : [])]
    })
  }
  return <details className={`${styles.row} ${styles.review}`} open={!!evidence || undefined}><summary>저장된 공급정보 검토·보류</summary>
    <p>저장 결과와 공식 자료를 확인한 항목만 기록하세요. 같은 행·조건의 새 기록은 선택한 범위의 최신 판단이 되고, 이전 기록은 아래 이력에 남습니다.</p>
    {evidence && baseline.note === evidence.note && <p className={styles.hint}>방금 적용한 보정의 근거를 채웠습니다. 실제로 확인한 행·항목만 골라 기록하세요.</p>}
    {disabled && <p>먼저 작성한 보정을 적용하고 저장 결과를 확인하세요.</p>}
    {error && <p role="alert">{error} <button type="button" onClick={() => setAttempt(value => value + 1)}>기록 다시 조회</button></p>}
    {notice && <p role="status">{notice}</p>}
    {overview && <form onSubmit={event => { event.preventDefault(); void submit() }}><fieldset disabled={disabled || busy} className={styles.editor}>
      <legend>확인 범위와 근거</legend>
      <label>검토할 행·조건<select required value={target} onChange={event => { setTarget(event.target.value); setFields([]); setScopes([]) }}><option value="">대상 선택</option>
        {queue.length ? <><optgroup label={`방금 바꾼 항목 ${queue.length}개`}>{queue.map(key => <option key={key} value={key}>{targetName(key)}</option>)}</optgroup>
          {Object.keys(overview.values).some(key => !queue.includes(key)) && <optgroup label="다른 행·조건">{Object.keys(overview.values).filter(key => !queue.includes(key)).map(key => <option key={key} value={key}>{targetName(key)}</option>)}</optgroup>}</>
          : Object.keys(overview.values).map(key => <option key={key} value={key}>{targetName(key)}</option>)}</select></label>
      {queue.length > 0 && <p className={styles.hint}>방금 바꾼 항목 {queue.length}개가 남았습니다.</p>}
      {target && overview.values[target] && <fieldset><legend>실제로 확인한 항목</legend>{Object.keys(overview.values[target]).map(field => <label key={field}>
        <input type="checkbox" checked={fields.includes(field)} onChange={event => setFields(current => event.target.checked ? [...current, field] : current.filter(item => item !== field))} />{reviewFieldLabels[field] ?? field}
      </label>)}<StoredDataTable data={Object.fromEntries(fields.map(field => [reviewFieldLabels[field] ?? field, overview.values[target][field]]))} label="선택 범위의 저장값" /></fieldset>}
      <details><summary>변경을 추적할 원천 항목 선택 (선택사항)</summary>
        <p>검토 근거로 사용한 항목만 선택하세요. 선택한 값의 변경·소실은 재검토로 표시합니다. 수집 시각만 바뀌면 유지합니다.</p>
        {workspace.sources.map(source => <fieldset key={`${source.category}:${source.sourceKey}`}><legend>{source.category} · {source.sourceKey}</legend>
          {[...new Set(source.rows.flatMap(row => isRecord(row.raw) ? Object.keys(row.raw) : []))].filter(field => !metadata.has(field)).map(field => <label key={field}>
            <input type="checkbox" checked={scopes.some(scope => scope.category === source.category && scope.sourceKey === source.sourceKey && scope.fields.includes(field))}
              onChange={event => sourceField(source.category, source.sourceKey, field, event.target.checked)} />{isSourceCategory(source.category) ? sourceFieldLabel(source.category, field) : field}
          </label>)}</fieldset>)}
      </details>
      {!scopes.length && <p>원천 항목을 선택하지 않으면 저장값의 변경만 추적합니다. 외부 원문 자체의 변경 여부는 자동으로 알 수 없습니다.</p>}
      <label>검토 결과<select value={outcome} onChange={event => setOutcome(event.target.value as typeof outcome)}><option value="VERIFIED">선택 범위 검토 완료</option><option value="ON_HOLD">판단 보류</option></select></label>
      <label>확인 근거·보류 사유<textarea required maxLength={2000} value={note} onChange={event => setNote(event.target.value)} /></label>
      <label>검토 근거 URL<input required type="url" maxLength={2048} value={url} onChange={event => setUrl(event.target.value)} /></label>
      <label>검토 페이지·표 위치<input maxLength={100} value={page} onChange={event => setPage(event.target.value)} /></label>
      <button type="submit" disabled={!fields.length || !target}>선택 범위 검토 기록</button>
    </fieldset></form>}
    {overview && <section aria-label="공급정보 검토 이력"><h3>최근 검토 기록</h3>{!overview.records.length && <p>검토 기록이 없습니다.</p>}
      {overview.records.map(record => <details key={record.id} open={record.latest && record.status !== 'VERIFIED'}><summary>{targetName(record.target)} · {record.latest ? statusLabels[record.status] : '이전 기록'} · {new Date(record.reviewedAt).toLocaleString('ko-KR')}</summary>
        <p>{record.note} · 기록자 {record.actor}</p><p>확인 범위: {Object.keys(record.checkedValues).map(field => reviewFieldLabels[field] ?? field).join(', ')}</p>
        <a href={/^https?:\/\//.test(record.sourceUrl) ? record.sourceUrl : undefined} target="_blank" rel="noreferrer">공식 근거 ↗ {record.sourcePage}</a>
        <StoredDataTable data={Object.fromEntries(Object.entries(record.checkedValues).map(([field, value]) => [reviewFieldLabels[field] ?? field, value]))} label="검토 당시 값" />
        {record.status === 'STALE' && <><StoredDataTable data={record.currentValues && Object.fromEntries(Object.keys(record.checkedValues).map(field => [reviewFieldLabels[field] ?? field, record.currentValues?.[field]]))} label="현재 선택 범위 값" />
          <StoredDataTable data={record.evidence} label="확인한 원천 항목" /><StoredDataTable data={record.currentEvidence} label="현재 원천 항목" /></>}
      </details>)}<p>최근 100개 기록을 표시합니다. 보정 이력은 ‘출처·수정 이력’에서도 확인할 수 있습니다.</p></section>}
  </details>
}
