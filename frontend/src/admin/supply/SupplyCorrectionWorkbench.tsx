import { Fragment, useEffect, useId, useState } from 'react'
import { Link } from 'react-router'
import { ManagementError } from '../management/api'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { PdfCorrectionWorkspace } from '../announcement-pdf/PdfCorrectionPage'
import { forgetApply, pendingApply, rememberApply } from './supplyApplyRecovery'
import { SupplyReviewPanel, type ReviewEvidence } from './SupplyReviewPanel'
import { changedReviewTargets } from './changedReviewTargets'
import { SupplyRowDraft } from './SupplyRowDraft'
import { rebaseCorrection, resolveConflict, type CorrectionConflict } from './rebaseCorrection'
import { applySupply, blankRow, loadSupplyWorkspace, previewSupply, type CorrectionRow, type SupplyCorrectionInput, type NewSupplyGroup, type SupplyPreview, type SupplyWorkspace } from './supplyCorrectionApi'
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
  const [pdfOpen, setPdfOpen] = useState(false)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [newGroups, setNewGroups] = useState<NewSupplyGroup[]>([])
  const [reviewDirty, setReviewDirty] = useState(false)
  const [appliedWarnings, setAppliedWarnings] = useState<string[]>([])
  const [applied, setApplied] = useState<{ changeId: number; evidence: ReviewEvidence; changed: string[] } | null>(null)
  const rowsDirty = workspace !== null && (JSON.stringify(rows) !== JSON.stringify(workspace.rows) || newGroups.length > 0 || conflicts.length > 0)
  const blockerId = useId()
  const previewBlocker = stale ? '저장값이 바뀌었습니다. ‘입력을 유지하고 최신값 비교’로 내 입력과 최신값을 맞춘 뒤 다시 저장하세요.'
    : conflicts.length > 0 ? `겹치는 변경 ${conflicts.length}개에서 사용할 값을 먼저 고르세요.`
      : !rowsDirty ? '아직 변경한 공급정보가 없습니다.' : ''
  const dirty = rowsDirty || reviewDirty || pdfOpen || uncertain || reason.length > 0 || page.length > 0 || url !== sourceUrl
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
          setPage(pending.input.sourcePage ?? ''); setNewGroups(pending.input.newGroups ?? []); setUncertain(true)
          setNotice('응답을 확인하지 못한 적용 작업이 있습니다. 같은 작업으로 결과를 먼저 확인하세요.')
        }
      }
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '공급정보를 불러오지 못했습니다.') })
    return () => controller.abort()
  }, [id, attempt])
  function edit(update: (rows: CorrectionRow[]) => CorrectionRow[]) { setRows(update); setPreview(null); setNotice('') }
  async function inspect() {
    if (!workspace || busy || uncertain || previewBlocker || !reason.trim() || !url.trim()) return
    setBusy(true); setError(''); setNotice('')
    const input: SupplyCorrectionInput = { operationId: crypto.randomUUID(), snapshotToken: workspace.snapshotToken, reason: reason.trim(), sourceUrl: url.trim(), sourcePage: page.trim() || null, rows, newGroups }
    try {
      const result = await previewSupply(id, input)
      if (newGroups.length && JSON.stringify(result.newGroups) !== JSON.stringify(newGroups)) throw new Error('서버가 공통 모집 묶음을 확인하지 못했습니다. 지원하는 서버로 갱신한 뒤 다시 저장하세요.')
      const checked = { input, result }; setPreview(checked); setStale(false)
      await apply(checked)
    }
    catch (cause) { setError(cause instanceof Error ? cause.message : '공급정보를 확인하지 못했습니다.'); setStale(cause instanceof ManagementError && cause.status === 409) }
    finally { setBusy(false) }
  }
  async function apply(checked = preview) {
    if (!checked) return
    const preview = checked
    setBusy(true); setError('')
    try { rememberApply(id, preview) }
    catch { setError('재확인할 작업을 이 탭에 보관하지 못해 적용하지 않았습니다. 브라우저 저장 공간을 확인하세요.'); setBusy(false); return }
    try {
      const result = await applySupply(id, preview.input, preview.result.confirmationToken)
      forgetApply(id)
      const changed = changedReviewTargets(preview.result.before, result.workspace.rows)
      setWorkspace(result.workspace); setRows(result.workspace.rows); setAppliedWarnings(preview.result.warnings); setPreview(null)
      setApplied({ changeId: result.changeId, changed, evidence: { note: preview.input.reason, sourceUrl: preview.input.sourceUrl, sourcePage: preview.input.sourcePage } }); setNewGroups([])
      setReason(''); setPage(''); setStale(false); setUncertain(false); onChanged()
      setNotice(result.alreadyApplied ? '이미 적용한 작업입니다. 현재 저장값을 불러왔습니다.' : '공급정보를 저장했습니다.')
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
      setNotice('내가 수정하지 않은 항목은 최신값으로 갱신했습니다. 겹치는 변경은 값을 선택한 뒤 다시 저장하세요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '최신값을 불러오지 못했습니다.') }
    finally { setBusy(false) }
  }
  if (!workspace) return <div>{error ? <p role="alert">{error} <button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button></p> : <p role="status">공급정보와 원천을 불러오는 중…</p>}</div>
  if (pdfOpen) return <PdfCorrectionWorkspace id={id} supplyDraft={{ rows, onCancel: () => setPdfOpen(false), onFill: input => {
    edit(() => input.rows); setNewGroups(input.newGroups ?? []); setReason(input.reason); setUrl(input.sourceUrl); setPage(input.sourcePage ?? '')
    setExpanded(null); setPdfOpen(false); setNotice('PDF에서 확인한 값을 편집표에 채웠습니다. 필요한 항목을 수정하고 저장하세요.')
  } }} />
  function addRow() {
    const row = blankRow(rows.length)
    edit(current => [...current, row]); setExpanded(row.key)
  }
  return <section className={styles.workspace} aria-label="공급정보 편집">
    <div className={styles.toolbar}><p>단지·주택형별 모집수와 임대조건을 확인하고 채우세요.</p><div className={styles.actions}>
      <button type="button" disabled={busy || uncertain || newGroups.length > 0} onClick={() => setPdfOpen(true)}>PDF로 채우기</button>
      <button type="button" disabled={busy || uncertain} onClick={addRow}>직접 추가</button>
    </div></div>
    {notice && <p role="status">{notice}</p>}
    <form onSubmit={event => { event.preventDefault(); void inspect() }}>
      <fieldset disabled={busy || uncertain} className={styles.editor}><legend className={styles.srOnly}>공고별 공급정보 편집</legend>
        <div className={styles.tableScroll}><table aria-label="공급정보 표"><thead><tr><th scope="col">단지</th><th scope="col">주택형</th><th scope="col">모집수</th><th scope="col">임대조건</th><th scope="col">확인할 항목</th><th scope="col">편집</th></tr></thead><tbody>
          {!rows.length && <tr><td colSpan={6}><div className={styles.empty}><strong>등록된 공급정보가 없습니다.</strong><p>PDF로 채우거나 직접 추가해 첫 공급행을 만드세요.</p></div></td></tr>}
          {rows.map((row, index) => {
            const group = newGroups.find(group => group.rowKeys.includes(row.key))
            const targets = row.targets.filter(target => !target.excluded)
            const gaps = row.excluded ? ['제외 예정'] : [!row.housingComplexId && '단지 연결', !row.housingTypeId && !row.newHousingType && '주택형 연결', !targets.length && '임대조건 없음'].filter(Boolean)
            return <Fragment key={row.key}><tr data-excluded={row.excluded || undefined}>
              <th scope="row">{row.complexName || '새 공급행'}</th><td>{row.typeName || '미입력'}</td>
              <td>{group || row.groupId ? `공통 ${group?.sharedHouseholdCount ?? row.sharedHouseholdCount ?? '미확인'}` : row.householdCount ?? '미확인'}</td>
              <td>{targets.length ? `${targets.length}개` : '없음'}</td><td>{gaps.join(' · ') || '—'}</td>
              <td><button type="button" aria-label={`${row.complexName || '새 공급행'} ${row.typeName || ''} 편집`} aria-expanded={expanded === row.key} onClick={() => setExpanded(expanded === row.key ? null : row.key)}>{expanded === row.key ? '닫기' : '편집'}</button></td>
            </tr>{expanded === row.key && <tr><td colSpan={6} className={styles.expandedCell}><SupplyRowDraft value={row} inline sharedCount={group?.sharedHouseholdCount} first={index === 0} last={index === rows.length - 1}
              onChange={next => edit(current => current.map(item => item.key === next.key ? next : item))}
              onRemove={() => { edit(current => current.filter(item => item.key !== row.key)); setExpanded(null) }}
              onMove={delta => edit(current => { const next = [...current]; [next[index], next[index + delta]] = [next[index + delta], next[index]]; return next.map((item, order) => ({ ...item, displayOrder: order })) })} /></td></tr>}</Fragment>
          })}
        </tbody></table></div>
        {newGroups.length > 0 && <p className={styles.hint}>공통 모집 묶음은 모집수를 한 번만 저장합니다. PDF를 다시 가져오려면 먼저 현재 변경을 저장하거나 취소하세요.</p>}
        {rowsDirty && <>
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
        </>}
        <div className={styles.actions}><button className="admin-primary" type="submit" disabled={!!previewBlocker} aria-describedby={previewBlocker ? blockerId : undefined}>{busy ? '저장 중…' : '공급정보 저장'}</button>
          {rowsDirty && <button type="button" onClick={() => { if (window.confirm('저장하지 않은 공급정보 변경을 취소할까요?')) { setRows(workspace.rows); setNewGroups([]); setReason(''); setPage(''); setUrl(sourceUrl); setConflicts([]); setPreview(null); setExpanded(null); setNotice('') } }}>변경 취소</button>}
          {stale && <button type="button" onClick={() => void refreshKeepingDraft()}>입력을 유지하고 최신값 비교</button>}</div>
        {previewBlocker && <p id={blockerId} className={styles.hint}>{previewBlocker}</p>}
      </fieldset>
    </form>
    {error && <p role="alert" className="registration-error">{error}</p>}
    {uncertain && <button type="button" className="admin-primary" disabled={busy} onClick={() => void apply()}>같은 작업으로 적용 결과 확인</button>}
    {appliedWarnings.length > 0 && <details><summary>저장 후 확인할 항목 {appliedWarnings.length}개</summary><ul>{appliedWarnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></details>}
    <SupplyReviewPanel key={applied ? `applied:${applied.changeId}` : 'review'} id={id} workspace={workspace} sourceUrl={sourceUrl} disabled={rowsDirty || busy || uncertain}
      onBusyChange={setBusy} onDirtyChange={setReviewDirty} evidence={applied?.evidence ?? null} pending={applied?.changed ?? []} />
    <nav className={styles.actions} aria-label="공급정보 확인"><Link to={`/admin/announcements/${id}?section=history`}>출처·수정 이력</Link><Link to={`/explore?announcementId=${id}`} target="_blank" rel="noreferrer">사용자 공고 화면 확인 ↗</Link></nav>
  </section>
}
