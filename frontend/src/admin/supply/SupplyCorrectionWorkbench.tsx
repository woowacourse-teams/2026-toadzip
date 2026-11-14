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
import { applySupply, loadSupplyWorkspace, previewSupply, type SupplyCorrectionInput, type SupplyPreview, type SupplyWorkspace } from './supplyCorrectionApi'
import { cellsOf, housingTypeCount, leaseOf, ownCount, sharedQuotas, toSheetInput, type SheetHousingType, type SupplySheet } from './supplySheet'
import { addHousingType, cellValues, emptySheet, moveHousingType, removeHousingType, wholeTypeQuota } from './sheetEdits'
import { AnnouncementComplexLinks } from './AnnouncementComplexLinks'
import styles from './SupplyCorrection.module.css'

// 다시 조회하면 풀리는 충돌만 '최신값 비교'로 안내한다. 일정 참조 같은 규칙 위반은 서버 문구를 그대로 보여 준다.
function isStaleConflict(cause: unknown) { return cause instanceof ManagementError && cause.code === 'ADMIN_DATA_STALE' }

/** 표의 '확인할 항목'. 공개 결과에 남을 주택형에서 연결·이름·수량이 빠진 것을 알린다. */
function gaps(sheet: SupplySheet, type: SheetHousingType) {
  const cells = cellsOf(sheet, type.key)
  return [!type.housingComplexId && '단지 연결', !type.housingTypeId && !type.newHousingType && '주택형 연결', !type.name.trim() && '주택형 표기 없음',
    !cells.length && '공급대상 없음', cells.some(cell => !cellValues(sheet, cell).label.trim()) && '공급대상 이름 없음',
    cells.length > 0 && !cells.some(cell => leaseOf(sheet, cell.key)) && '임대조건 없음',
    // 주택형 모집수는 그 안 묶음의 합이라 대상별 모집 수와 함께 있으면 두 번 센다.
    wholeTypeQuota(sheet, type.key) && cells.length > 1 && cells.some(cell => ownCount(sheet, cell.key, 'WINNER') !== null) && '모집수 중복 확인',
  ].filter((gap): gap is string => Boolean(gap))
}
function blocker(sheet: SupplySheet) {
  if (sheet.housingTypes.some(type => !type.name.trim()) || sheet.targets.some(target => !target.label.trim())) return '이름이 빈 주택형이나 공급대상이 있습니다. 표기를 먼저 채우세요.'
  if (sheet.housingTypes.some(type => type.newHousingType && (!type.newHousingType.name.trim() || !(type.newHousingType.exclusiveArea > 0)))) return '새로 만들 주택형의 이름과 전용면적을 채우세요.'
  return ''
}

export function SupplyCorrectionWorkbench({ id, sourceUrl, onChanged, onBusyChange, onDirtyChange }: {
  id: string; sourceUrl: string; onChanged: () => void; onBusyChange: (value: boolean) => void; onDirtyChange: (value: boolean) => void
}) {
  const [workspace, setWorkspace] = useState<SupplyWorkspace | null>(null)
  const [sheet, setSheet] = useState<SupplySheet>(emptySheet)
  const [reason, setReason] = useState(''); const [url, setUrl] = useState(sourceUrl); const [page, setPage] = useState('')
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const [attempt, setAttempt] = useState(0); const [stale, setStale] = useState(false)
  const [conflicts, setConflicts] = useState<CorrectionConflict[]>([])
  const [preview, setPreview] = useState<{ input: SupplyCorrectionInput; result: SupplyPreview } | null>(null)
  const [uncertain, setUncertain] = useState(false)
  const [pdfOpen, setPdfOpen] = useState(false)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [reviewDirty, setReviewDirty] = useState(false)
  const [appliedWarnings, setAppliedWarnings] = useState<string[]>([])
  const [applied, setApplied] = useState<{ changeId: number; evidence: ReviewEvidence; changed: string[] } | null>(null)
  const sheetDirty = workspace !== null && (JSON.stringify(sheet) !== JSON.stringify(workspace.sheet) || conflicts.length > 0)
  const blockerId = useId()
  const previewBlocker = stale ? '저장하는 사이 등록값이나 원천이 바뀌어 저장하지 않았습니다. 내 입력은 그대로 있으니 ‘입력을 유지하고 최신값 비교’로 맞춘 뒤 다시 저장하세요.'
    : conflicts.length > 0 ? `겹치는 변경 ${conflicts.length}개에서 사용할 값을 먼저 고르세요.`
      : !sheetDirty ? '아직 변경한 공급정보가 없습니다.' : blocker(sheet)
  const dirty = sheetDirty || reviewDirty || pdfOpen || uncertain || reason.length > 0 || page.length > 0 || url !== sourceUrl
  useUnsavedChanges(dirty)
  useEffect(() => { onBusyChange(busy); return () => onBusyChange(false) }, [busy, onBusyChange])
  useEffect(() => { onDirtyChange(dirty); return () => onDirtyChange(false) }, [dirty, onDirtyChange])
  useEffect(() => {
    const controller = new AbortController(); setError('')
    void loadSupplyWorkspace(id, controller.signal).then(value => {
      if (!controller.signal.aborted) {
        setWorkspace(value); setSheet(value.sheet)
        const pending = pendingApply(id)
        if (pending) {
          setPreview(pending); setSheet(pending.result.after); setReason(pending.input.reason); setUrl(pending.input.sourceUrl)
          setPage(pending.input.sourcePage ?? ''); setUncertain(true)
          setNotice('응답을 확인하지 못한 적용 작업이 있습니다. 같은 작업으로 결과를 먼저 확인하세요.')
        }
      }
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '공급정보를 불러오지 못했습니다.') })
    return () => controller.abort()
  }, [id, attempt])
  function edit(next: SupplySheet) { setSheet(next); setPreview(null); setNotice('') }
  async function inspect() {
    if (!workspace || busy || uncertain || previewBlocker || !reason.trim() || !url.trim()) return
    setBusy(true); setError(''); setNotice('')
    const input: SupplyCorrectionInput = { operationId: crypto.randomUUID(), snapshotToken: workspace.snapshotToken, reason: reason.trim(),
      sourceUrl: url.trim(), sourcePage: page.trim() || null, sheet: toSheetInput(sheet) }
    try {
      const result = await previewSupply(id, input)
      const checked = { input, result }; setPreview(checked); setStale(false)
      await apply(checked)
    }
    catch (cause) { setError(cause instanceof Error ? cause.message : '공급정보를 확인하지 못했습니다.'); setStale(isStaleConflict(cause)) }
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
      const changed = changedReviewTargets(preview.result.before, result.workspace.sheet)
      setWorkspace(result.workspace); setSheet(result.workspace.sheet); setAppliedWarnings(preview.result.warnings); setPreview(null)
      setApplied({ changeId: result.changeId, changed, evidence: { note: preview.input.reason, sourceUrl: preview.input.sourceUrl, sourcePage: preview.input.sourcePage } })
      setReason(''); setPage(''); setStale(false); setUncertain(false); setExpanded(null); onChanged()
      setNotice(result.alreadyApplied ? '이미 적용한 작업입니다. 현재 저장값을 불러왔습니다.' : '공급정보를 저장했습니다.')
    } catch (cause) {
      const rejected = cause instanceof ManagementError && cause.status >= 400 && cause.status < 500
      if (rejected) forgetApply(id)
      setUncertain(!rejected)
      setError(rejected ? cause.message : '적용 결과를 확인하지 못했습니다. 중복 생성을 막기 위해 입력을 잠갔습니다. 같은 작업으로 결과를 다시 확인하세요.')
      setStale(isStaleConflict(cause))
    }
    finally { setBusy(false) }
  }
  async function refreshKeepingDraft() {
    if (!workspace) return
    setBusy(true); setError('')
    try {
      const latest = await loadSupplyWorkspace(id)
      const merged = rebaseCorrection(workspace.sheet, sheet, latest.sheet)
      setSheet(merged.sheet); setConflicts(merged.conflicts); setWorkspace(latest); setPreview(null); setStale(false)
      setNotice('내가 수정하지 않은 항목은 최신값으로 갱신했습니다. 겹치는 변경은 값을 선택한 뒤 다시 저장하세요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '최신값을 불러오지 못했습니다.') }
    finally { setBusy(false) }
  }
  if (!workspace) return <div>{error ? <p role="alert">{error} <button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button></p> : <p role="status">공급정보와 원천을 불러오는 중…</p>}</div>
  if (pdfOpen) return <section aria-label="PDF로 공급표 채우기">
    <div className={styles.actions}><button type="button" onClick={() => setPdfOpen(false)}>PDF로 채우기 닫기</button></div>
    <PdfCorrectionWorkspace id={id} supplyDraft={(next: SupplySheet) => {
      // PDF 공급표가 편집표 전체를 대신한다. 저장된 주택형 중 PDF에 없는 것은 저장하면 지워지므로 알려 둔다.
      const dropped = workspace.sheet.housingTypes.filter(type => !next.housingTypes.some(item => item.key === type.key)).length
      edit(next); setExpanded(null); setPdfOpen(false)
      setNotice(`PDF에서 확인한 공급표를 편집표에 채웠습니다.${dropped ? ` PDF에 없는 저장된 주택형 ${dropped}개는 저장하면 지워집니다.` : ''} 필요한 항목을 고치고 근거를 적어 저장하세요.`)
    }} />
  </section>
  const types = [...sheet.housingTypes].sort((a, b) => a.displayOrder - b.displayOrder)
  function addType() {
    const complex = workspace!.announcementComplexes[0]
    const added = addHousingType(sheet, complex ? { announcementComplexId: complex.id, complexName: complex.name,
      housingComplexId: complex.housingComplexId, housingComplexName: complex.housingComplexName } : {})
    edit(added.sheet); setExpanded(added.key)
  }
  function removeType(type: SheetHousingType) {
    if (type.id !== null && !window.confirm(`'${type.name || '이름 없는 주택형'}'과 그 공급대상·수량·임대조건을 지울까요? 저장하면 되돌릴 수 없고 변경 이력에만 남습니다.`)) return
    edit(removeHousingType(sheet, type.key)); if (expanded === type.key) setExpanded(null)
  }
  return <section className={styles.workspace} aria-label="공급정보 편집">
    <div className={styles.toolbar}><p>단지·주택형별 모집수와 임대조건을 확인하고 채우세요.</p><div className={styles.actions}>
      <button type="button" disabled={busy || uncertain} onClick={() => setPdfOpen(true)}>PDF로 채우기</button>
      <button type="button" disabled={busy || uncertain} onClick={addType}>직접 추가</button>
    </div></div>
    {notice && <p role="status">{notice}</p>}
    {workspace.warnings.length > 0 && <ul aria-label="공급정보 확인 안내">{workspace.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul>}
    <AnnouncementComplexLinks id={id} workspace={workspace} disabled={sheetDirty || busy || uncertain} onBusyChange={setBusy}
      onLinked={(next, message) => { setWorkspace(next); setSheet(next.sheet); setPreview(null); setNotice(message); onChanged() }} />
    {workspace.sourceReference.length > 0 && <details className={styles.row}><summary>LH·GH 원천 참고 {workspace.sourceReference.length}개</summary><div className={styles.reviewBody}>
      <p className={styles.hint}>LH·GH가 보낸 공급 원본입니다. 공급표 모집수를 검산할 때만 참고하고 저장하지 않습니다.</p>
      <div className={styles.tableScroll}><table aria-label="LH·GH 원천 참고 표"><thead><tr><th scope="col">단지</th><th scope="col">주택형</th><th scope="col">전용면적 (㎡)</th><th scope="col">공급 세대수</th></tr></thead><tbody>
        {workspace.sourceReference.map((item, index) => <tr key={index}><th scope="row">{item.complexLabel || '미확인'}</th><td>{item.typeName || '미확인'}</td><td>{item.exclusiveArea ?? '미확인'}</td><td>{item.householdCount ?? '미확인'}</td></tr>)}
      </tbody></table></div></div></details>}
    <form onSubmit={event => { event.preventDefault(); void inspect() }}>
      <fieldset disabled={busy || uncertain} className={styles.editor}><legend className={styles.srOnly}>공고별 공급정보 편집</legend>
        <div className={styles.tableScroll}><table aria-label="공급정보 표"><thead><tr><th scope="col">단지</th><th scope="col">주택형</th><th scope="col">모집수</th><th scope="col">임대조건</th><th scope="col">확인할 항목</th><th scope="col">편집</th></tr></thead><tbody>
          {!types.length && <tr><td colSpan={6}><div className={styles.empty}><strong>등록된 공급정보가 없습니다.</strong><p>PDF로 채우거나 직접 추가해 첫 주택형을 만드세요.</p></div></td></tr>}
          {types.map((type, index) => {
            const cells = cellsOf(sheet, type.key)
            const across = sharedQuotas(sheet, type.key).filter(item => item.acrossTypes && item.quota.kind === 'WINNER')
            const count = housingTypeCount(sheet, type.key)
            const name = `${type.housingComplexName || type.complexName || '새 주택형'} ${type.name}`.trim()
            return <Fragment key={type.key}><tr>
              <th scope="row" title={type.housingComplexName && type.complexName && type.housingComplexName !== type.complexName ? `공고 표기: ${type.complexName}` : undefined}>{type.housingComplexName || type.complexName || '새 주택형'}</th><td>{type.name || '미입력'}</td>
              <td>{[count ?? (across.length ? null : '미확인'), ...across.map(item => `함께 ${item.quota.count}`)].filter(item => item !== null).join(' · ')}</td>
              <td>{cells.filter(cell => leaseOf(sheet, cell.key)).length ? `${cells.filter(cell => leaseOf(sheet, cell.key)).length}개` : '없음'}</td><td>{gaps(sheet, type).join(' · ') || '—'}</td>
              <td><div className={styles.rowActions}><button type="button" aria-label={`${name} 편집`} aria-expanded={expanded === type.key} onClick={() => setExpanded(expanded === type.key ? null : type.key)}>{expanded === type.key ? '닫기' : '편집'}</button>
                <button type="button" aria-label={`${name} 지우기`} onClick={() => removeType(type)}>지우기</button></div></td>
            </tr>{expanded === type.key && <tr><td colSpan={6} className={styles.expandedCell}><SupplyRowDraft sheet={sheet} typeKey={type.key} complexes={workspace.announcementComplexes}
              first={index === 0} last={index === types.length - 1} onChange={edit} onRemove={() => removeType(type)}
              onMove={delta => edit(moveHousingType(sheet, type.key, delta))} /></td></tr>}</Fragment>
          })}
        </tbody></table></div>
        {sheetDirty && <>
        <fieldset className={styles.evidence}><legend>공식 근거와 변경 이유</legend>
          <label>보정 이유<textarea required maxLength={2000} rows={3} value={reason} onChange={event => { setReason(event.target.value); setPreview(null) }} /></label>
          <label>공식 근거 URL<input required type="url" maxLength={2048} value={url} onChange={event => { setUrl(event.target.value); setPreview(null) }} /></label>
          <label>페이지·표 위치 (알 때만)<input maxLength={100} value={page} onChange={event => { setPage(event.target.value); setPreview(null) }} /></label>
        </fieldset>
        {conflicts.length > 0 && <section><h3>겹치는 변경을 선택하세요</h3>{conflicts.map(conflict => <div key={conflict.id} className={styles.conflict}>
          <p>{conflict.scope} · {conflict.field}</p>
          <p>현재 저장값: {conflict.current} / 내 입력: {conflict.mine}</p>
          <button type="button" onClick={() => { setConflicts(current => current.filter(item => item !== conflict)) }}>{conflict.missing ? '이 항목 입력 버리기' : '현재 저장값 사용'}</button>
          {conflict.keepMine && <button type="button" onClick={() => { setSheet(current => resolveConflict(current, conflict, true)); setConflicts(current => current.filter(item => item !== conflict)) }}>{conflict.missing ? '내 입력으로 다시 만들기' : '내 입력 사용'}</button>}
        </div>)}</section>}
        </>}
        <div className={styles.actions}><button className="admin-primary" type="submit" disabled={!!previewBlocker} aria-describedby={previewBlocker ? blockerId : undefined}>{busy ? '저장 중…' : '공급정보 저장'}</button>
          {sheetDirty && <button type="button" onClick={() => { if (window.confirm('저장하지 않은 공급정보 변경을 취소할까요?')) { setSheet(workspace.sheet); setReason(''); setPage(''); setUrl(sourceUrl); setConflicts([]); setPreview(null); setExpanded(null); setNotice('') } }}>변경 취소</button>}
          {stale && <button type="button" onClick={() => void refreshKeepingDraft()}>입력을 유지하고 최신값 비교</button>}</div>
        {previewBlocker && <p id={blockerId} role={stale ? 'alert' : undefined} className={stale ? styles.stale : styles.hint}>{previewBlocker}</p>}
      </fieldset>
    </form>
    {/* 다시 조회하면 풀리는 충돌은 저장 버튼 아래 안내 하나로만 알린다. */}
    {error && !stale && <p role="alert" className="registration-error">{error}</p>}
    {uncertain && <button type="button" className="admin-primary" disabled={busy} onClick={() => void apply()}>같은 작업으로 적용 결과 확인</button>}
    {appliedWarnings.length > 0 && <details><summary>저장 후 확인할 항목 {appliedWarnings.length}개</summary><ul>{appliedWarnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></details>}
    <SupplyReviewPanel key={applied ? `applied:${applied.changeId}` : 'review'} id={id} workspace={workspace} sourceUrl={sourceUrl} disabled={sheetDirty || busy || uncertain}
      onBusyChange={setBusy} onDirtyChange={setReviewDirty} evidence={applied?.evidence ?? null} pending={applied?.changed ?? []} />
    <nav className={styles.actions} aria-label="공급정보 확인"><Link to={`/admin/announcements/${id}?section=history`}>출처·수정 이력</Link><Link to={`/explore?announcementId=${id}`} target="_blank" rel="noreferrer">사용자 공고 화면 확인 ↗</Link></nav>
  </section>
}
