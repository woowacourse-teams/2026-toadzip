import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { ManagementError } from '../management/api'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { StoredDataTable } from '../shared/StoredDataTable'
import { forgetApply, pendingApply, rememberApply } from './supplyApplyRecovery'
import { SupplyImportPanel } from './SupplyImportPanel'
import { SupplyReviewPanel } from './SupplyReviewPanel'
import { SupplyRowDraft } from './SupplyRowDraft'
import { SupplyCorrectionPreview } from './SupplyCorrectionPreview'
import { rebaseCorrection, resolveConflict, type CorrectionConflict } from './rebaseCorrection'
import { applySupply, blankRow, loadSupplyWorkspace, previewSupply, type CorrectionRow, type SupplyCorrectionInput, type SupplyPreview, type SupplyWorkspace } from './supplyCorrectionApi'
import styles from './SupplyCorrection.module.css'

export function SupplyCorrectionWorkbench({ id, sourceUrl, onChanged, onBusyChange, onDirtyChange }: {
  id: string; sourceUrl: string; onChanged: () => void; onBusyChange: (value: boolean) => void; onDirtyChange: (value: boolean) => void
}) {
  const [workspace, setWorkspace] = useState<SupplyWorkspace | null>(null)
  const [rows, setRows] = useState<CorrectionRow[]>([])
  const [reason, setReason] = useState(''); const [url, setUrl] = useState(sourceUrl); const [page, setPage] = useState('')
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const [attempt, setAttempt] = useState(0); const [stale, setStale] = useState(false)
  const [conflicts, setConflicts] = useState<CorrectionConflict[]>([])
  const [preview, setPreview] = useState<{ input: SupplyCorrectionInput; result: SupplyPreview } | null>(null)
  const [uncertain, setUncertain] = useState(false)
  const [importDirty, setImportDirty] = useState(false)
  const [reviewDirty, setReviewDirty] = useState(false)
  const [appliedWarnings, setAppliedWarnings] = useState<string[]>([])
  const rowsDirty = workspace !== null && JSON.stringify(rows) !== JSON.stringify(workspace.rows)
  const dirty = rowsDirty || reviewDirty || importDirty || uncertain || reason.length > 0 || page.length > 0 || url !== sourceUrl
  useUnsavedChanges(dirty)
  useEffect(() => { onBusyChange(busy); return () => onBusyChange(false) }, [busy, onBusyChange])
  useEffect(() => { onDirtyChange(dirty); return () => onDirtyChange(false) }, [dirty, onDirtyChange])
  useEffect(() => {
    const controller = new AbortController(); setError('')
    void loadSupplyWorkspace(id, controller.signal).then(value => {
      if (!controller.signal.aborted) {
        setWorkspace(value); setRows(value.rows)
        const pending = pendingApply(id)
        if (pending) {
          setPreview(pending); setRows(pending.input.rows); setReason(pending.input.reason); setUrl(pending.input.sourceUrl)
          setPage(pending.input.sourcePage ?? ''); setUncertain(true)
          setNotice('응답을 확인하지 못한 적용 작업이 있습니다. 같은 작업으로 결과를 먼저 확인하세요.')
        }
      }
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '공급정보를 불러오지 못했습니다.') })
    return () => controller.abort()
  }, [id, attempt])
  function edit(update: (rows: CorrectionRow[]) => CorrectionRow[]) { setRows(update); setPreview(null); setNotice('') }
  async function inspect() {
    if (!workspace || conflicts.length) return
    setBusy(true); setError(''); setNotice('')
    const input: SupplyCorrectionInput = { operationId: crypto.randomUUID(), snapshotToken: workspace.snapshotToken, reason: reason.trim(), sourceUrl: url.trim(), sourcePage: page.trim() || null, rows }
    try { setPreview({ input, result: await previewSupply(id, input) }); setStale(false) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '미리보기를 만들지 못했습니다.'); setStale(cause instanceof ManagementError && cause.status === 409) }
    finally { setBusy(false) }
  }
  async function apply() {
    if (!preview) return
    setBusy(true); setError('')
    try { rememberApply(id, preview) }
    catch { setError('재확인할 작업을 이 탭에 보관하지 못해 적용하지 않았습니다. 브라우저 저장 공간을 확인하세요.'); setBusy(false); return }
    try {
      const result = await applySupply(id, preview.input, preview.result.confirmationToken)
      forgetApply(id)
      setWorkspace(result.workspace); setRows(result.workspace.rows); setAppliedWarnings(preview.result.warnings); setPreview(null)
      setReason(''); setPage(''); setStale(false); setUncertain(false); onChanged()
      setNotice(result.alreadyApplied ? '이미 적용한 작업입니다. 현재 저장값을 불러왔습니다.' : '보정을 저장했습니다. 공개 결과와 남은 항목을 확인하세요.')
    } catch (cause) {
      const rejected = cause instanceof ManagementError && cause.status >= 400 && cause.status < 500
      if (rejected) forgetApply(id)
      setUncertain(!rejected)
      setError(rejected ? cause.message : '적용 결과를 확인하지 못했습니다. 중복 생성을 막기 위해 입력을 잠갔습니다. 같은 작업으로 결과를 다시 확인하세요.')
      setStale(cause instanceof ManagementError && cause.status === 409)
    }
    finally { setBusy(false) }
  }
  async function refreshKeepingDraft() {
    if (!workspace) return
    setBusy(true); setError('')
    try {
      const latest = await loadSupplyWorkspace(id)
      const merged = rebaseCorrection(workspace.rows, rows, latest.rows)
      setRows(merged.rows); setConflicts(merged.conflicts); setWorkspace(latest); setPreview(null); setStale(false)
      setNotice('내가 수정하지 않은 항목은 최신값으로 갱신했습니다. 겹치는 변경은 값을 선택한 뒤 다시 미리보기 하세요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '최신값을 불러오지 못했습니다.') }
    finally { setBusy(false) }
  }
  if (!workspace) return <div>{error ? <p role="alert">{error} <button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button></p> : <p role="status">공급정보와 원천을 불러오는 중…</p>}</div>
  return <section className={styles.workspace} aria-label="공급정보 보정 작업실">
    <p>필요한 행을 펼쳐 변경안을 작성하세요. 행·대상·연결·새 주택형을 확인한 뒤 한 번에 적용합니다.</p>
    <details className={styles.sources}><summary>비교 근거: 저장된 원천 {workspace.sources.length}개</summary>
      {workspace.sources.length === 0 && <p>연결된 원천이 없습니다. 공식 자료를 직접 확인하고 아래에 근거를 기록하세요.</p>}
      {workspace.sources.map((source, index) => <details key={`${source.category}:${source.sourceKey}:${index}`}><summary>{source.category} · {source.sourceKey}</summary>
        {!source.rows.length && <p>현재 원천을 찾을 수 없습니다.</p>}{source.rows.map((item, index) => <div key={index}><p>수집 시각: {item.collectedAt ? new Date(item.collectedAt).toLocaleString('ko-KR') : '알 수 없음'}</p><StoredDataTable data={item.raw} label={`수집 원천 ${index + 1}`} /></div>)}</details>)}
    </details>
    {(workspace.sourceWarnings?.length ?? 0) > 0 && <section aria-label="원천 재확인"><h3>원천 재확인</h3><ul>{workspace.sourceWarnings?.map(warning => <li key={warning}>{warning}</li>)}</ul></section>}
    {notice && <p role="status">{notice}</p>}
    {appliedWarnings.length > 0 && <details open><summary>남은 확인 항목 {appliedWarnings.length}개</summary><ul>{appliedWarnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></details>}
    <SupplyImportPanel onDirtyChange={setImportDirty} rows={rows} disabled={busy || uncertain} onMerge={next => edit(() => next)} />
    <form onSubmit={event => { event.preventDefault(); void inspect() }}>
      <fieldset disabled={busy || uncertain} className={styles.editor}><legend className={styles.srOnly}>공고별 공급정보 변경안</legend>
        {rows.map((row, index) => <SupplyRowDraft key={row.key} value={row} first={index === 0} last={index === rows.length - 1}
          onChange={next => edit(current => current.map(item => item.key === next.key ? next : item))}
          onRemove={() => edit(current => current.filter(item => item.key !== row.key))}
          onMove={delta => edit(current => { const next = [...current]; [next[index], next[index + delta]] = [next[index + delta], next[index]]; return next.map((item, order) => ({ ...item, displayOrder: order })) })} />)}
        <button type="button" onClick={() => edit(current => [...current, blankRow(current.length)])}>누락 공급행 추가</button>
        <fieldset className={styles.evidence}><legend>공식 근거와 변경 이유</legend>
          <label>보정 이유<textarea required maxLength={2000} rows={3} value={reason} onChange={event => { setReason(event.target.value); setPreview(null) }} /></label>
          <label>공식 근거 URL<input required type="url" maxLength={2048} value={url} onChange={event => { setUrl(event.target.value); setPreview(null) }} /></label>
          <label>페이지·표 위치 (알 때만)<input maxLength={100} value={page} onChange={event => { setPage(event.target.value); setPreview(null) }} /></label>
        </fieldset>
        {conflicts.length > 0 && <section><h3>겹치는 변경을 선택하세요</h3>{conflicts.map((conflict, index) => <div key={`${conflict.rowKey}:${conflict.targetKey}:${conflict.field}`} className={styles.conflict}>
          <p>{rows.find(row => row.key === conflict.rowKey)?.typeName} · {conflict.targetKey ? '대상 조건 · ' : ''}{conflict.field === '__missing' ? '서버에서 사라진 항목' : conflict.field}</p>
          <p>현재 저장값: {JSON.stringify(conflict.current) ?? '미확인'} / 내 입력: {JSON.stringify(conflict.mine) ?? '미확인'}</p>
          <button type="button" onClick={() => { setRows(current => resolveConflict(current, conflict, false)); setConflicts(current => current.filter((_, i) => i !== index)) }}>{conflict.field === '__missing' ? '이 항목 입력 버리기' : '현재 저장값 사용'}</button>
          <button type="button" onClick={() => { setRows(current => resolveConflict(current, conflict, true)); setConflicts(current => current.filter((_, i) => i !== index)) }}>{conflict.field === '__missing' ? '새 항목으로 입력 복사' : '내 입력 사용'}</button>
        </div>)}</section>}
        <div className={styles.actions}><button className="admin-primary" type="submit" disabled={!rowsDirty || conflicts.length > 0 || stale}>변경 미리보기</button>
          <button type="button" onClick={() => void refreshKeepingDraft()}>입력을 유지하고 최신값 비교</button></div>
      </fieldset>
    </form>
    {error && <p role="alert" className="registration-error">{error}</p>}
    {preview && <><SupplyCorrectionPreview value={preview.result} /><button type="button" className="admin-primary" disabled={busy || stale} onClick={() => void apply()}>{busy ? '적용 중…' : uncertain ? '같은 작업으로 적용 결과 확인' : '확인한 보정 일괄 적용'}</button></>}
    <SupplyReviewPanel id={id} workspace={workspace} sourceUrl={sourceUrl} disabled={rowsDirty || busy || uncertain} onBusyChange={setBusy} onDirtyChange={setReviewDirty} />
    <nav className={styles.actions} aria-label="보정 결과 확인"><Link to={`/explore?announcementId=${id}`} target="_blank" rel="noreferrer">사용자 공고 화면 확인 ↗</Link>
      <p>검토는 위의 ‘저장된 공급정보 검토·보류’에서 기록하고, 보정 근거는 ‘출처·수정 이력’에서 확인하세요.</p></nav>
  </section>
}
