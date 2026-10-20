import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { ManagementError } from '../management/api'
import { announcementSections, display } from '../management/fields'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { AnnouncementImportGuide } from '../registration/AnnouncementImportGuide'
import { StoredDataTable } from '../shared/StoredDataTable'
import { SupplyRowDraft } from '../supply/SupplyRowDraft'
import { SupplyCorrectionPreview } from '../supply/SupplyCorrectionPreview'
import type { CorrectionRow } from '../supply/supplyCorrectionApi'
import { applyJsonUpdate, loadJsonWorkspace, previewJsonUpdate, type ChildChange, type ExtractedAnnouncement, type JsonChange, type JsonObject, type JsonPreview, type JsonUpdateInput, type JsonWorkspace } from './api'
import { childSelections, confirmedSupplyRow, defaultChildDestination, defaultRowDestination, defaultTargetDestinations, isUnresolved, mergeAnnouncementRows, metadataFields, parseAnnouncementJson } from './importValues'
import styles from './AnnouncementJsonCorrection.module.css'

export function AnnouncementJsonCorrectionPanel({ id, onChanged, onBusyChange, onDirtyChange }: {
  id: string; onChanged: () => void; onBusyChange: (busy: boolean) => void; onDirtyChange: (dirty: boolean) => void
}) {
  const [workspace, setWorkspace] = useState<JsonWorkspace | null>(null)
  const [text, setText] = useState(''); const [document, setDocument] = useState<ExtractedAnnouncement | null>(null)
  const [fields, setFields] = useState<string[]>([])
  const [destinations, setDestinations] = useState<string[]>([]); const [targetDestinations, setTargetDestinations] = useState<string[][]>([])
  const [eventDestinations, setEventDestinations] = useState<string[]>([]); const [attachmentDestinations, setAttachmentDestinations] = useState<string[]>([])
  const [rows, setRows] = useState<CorrectionRow[]>([]); const [rowsImported, setRowsImported] = useState(false)
  const [reason, setReason] = useState(''); const [sourceUrl, setSourceUrl] = useState('')
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const [attempt, setAttempt] = useState(0); const [uncertain, setUncertain] = useState(false)
  const [preview, setPreview] = useState<{ input: JsonUpdateInput; result: JsonPreview } | null>(null)
  const dirty = text.trim().length > 0 || uncertain
  useUnsavedChanges(dirty)
  useEffect(() => { onBusyChange(busy); return () => onBusyChange(false) }, [busy, onBusyChange])
  useEffect(() => { onDirtyChange(dirty); return () => onDirtyChange(false) }, [dirty, onDirtyChange])
  useEffect(() => {
    const controller = new AbortController(); setError('')
    void loadJsonWorkspace(id, controller.signal).then(value => {
      if (!controller.signal.aborted) { setWorkspace(value); setRows(value.supply.rows); setSourceUrl(String(value.detail.data.originalUrl ?? '')) }
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '공고를 불러오지 못했습니다.') })
    return () => controller.abort()
  }, [id, attempt])
  function change() { setPreview(null); setNotice(''); setError('') }
  function input(value: string) { setText(value); setDocument(null); setRowsImported(false); change() }
  function inspectDocument() {
    if (!workspace) return
    change()
    try {
      const value = parseAnnouncementJson(text)
      const mappings = value.supplyRows.map((_item, index) => defaultRowDestination(confirmedSupplyRow(value, index), workspace.supply.rows))
      setDocument(value); setRows(workspace.supply.rows); setRowsImported(false); setDestinations(mappings)
      setTargetDestinations(value.supplyRows.map((_item, index) => defaultTargetDestinations(confirmedSupplyRow(value, index), workspace.supply.rows.find(row => row.key === mappings[index]))))
      setFields(metadataFields(value, workspace).filter(field => field.available && field.changed).map(field => field.name))
      setEventDestinations(value.schedules.map((item, index) => isUnresolved(value, `schedules[${index}]`, true) ? '' : defaultChildDestination(item, workspace.events, 'schedule')))
      const currentAttachments = (workspace.detail.attachments ?? []).map(item => ({ id: item.attachmentId, data: { ...item } }))
      setAttachmentDestinations(value.attachments.map((item, index) => isUnresolved(value, `attachments[${index}]`, true) ? '' : defaultChildDestination(item, currentAttachments, 'attachment')))
      setSourceUrl(typeof value.source.originalUrl === 'string' ? value.source.originalUrl : String(workspace.detail.data.originalUrl ?? ''))
    } catch (cause) { setError(cause instanceof Error ? cause.message : '공고 JSON 형식을 확인하세요.') }
  }
  function importRows() {
    if (!document || !workspace) return
    change()
    try { setRows(mergeAnnouncementRows(workspace.supply.rows, document, destinations, targetDestinations)); setRowsImported(true); setNotice('선택한 공급정보를 변경안으로 가져왔습니다. 연결과 조건을 확인하세요.') }
    catch (cause) { setError(cause instanceof Error ? cause.message : '공급정보 대응을 확인하세요.') }
  }
  async function inspect() {
    if (!workspace || !document) return
    change(); setBusy(true)
    const input: JsonUpdateInput = { snapshotToken: workspace.snapshotToken, document, fields,
      schedules: childSelections(eventDestinations), attachments: childSelections(attachmentDestinations),
      correction: { operationId: crypto.randomUUID(), snapshotToken: workspace.supply.snapshotToken, rows: rowsImported ? rows : workspace.supply.rows,
        reason: reason.trim(), sourceUrl: sourceUrl.trim(), sourcePage: null } }
    try { setPreview({ input, result: await previewJsonUpdate(id, input) }) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '변경 미리보기를 만들지 못했습니다.') }
    finally { setBusy(false) }
  }
  async function apply() {
    if (!preview) return
    setBusy(true); setError('')
    try {
      const saved = await applyJsonUpdate(id, preview.input, preview.result.confirmationToken)
      setWorkspace(saved); setRows(saved.supply.rows); setText(''); setDocument(null); setPreview(null); setReason('')
      setNotice('현재 공고를 갱신했습니다. 변경 이력과 공개 결과를 확인하세요.'); onChanged()
    } catch (cause) {
      const rejected = cause instanceof ManagementError && cause.status >= 400 && cause.status < 500
      setUncertain(!rejected)
      setError(rejected ? cause.message : '저장 결과를 확인하지 못했습니다. 중복 적용하지 말고 최신 저장값을 확인하세요.')
      setPreview(null)
    } finally { setBusy(false) }
  }
  async function reload() {
    setBusy(true); setError('')
    try {
      const latest = await loadJsonWorkspace(id)
      setWorkspace(latest); setRows(latest.supply.rows); setDocument(null); setPreview(null); setUncertain(false); setRowsImported(false)
      setNotice('최신 저장값을 불러왔습니다. JSON 입력은 유지했습니다. 항목 확인부터 다시 비교하세요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '최신값을 불러오지 못했습니다.') }
    finally { setBusy(false) }
  }
  if (!workspace) return <section>{error ? <p role="alert">{error} <button type="button" onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button></p> : <p role="status">현재 공고와 공급정보를 불러오는 중…</p>}</section>
  return <section className={styles.panel} aria-label="공고 JSON 갱신">
    <header><h2>JSON으로 갱신</h2><p><strong>현재 공고 #{id} · {workspace.detail.summary.name}</strong>를 갱신합니다. 신규 등록용과 같은 JSON으로 추출값과 현재값을 비교하세요.</p></header>
    <AnnouncementImportGuide updating />
    {notice && <p role="status">{notice}</p>}
    {error && <p role="alert" className="registration-error">{error}</p>}
    <fieldset disabled={busy || uncertain} className={styles.input}>
      <legend>1. 추출한 공고 JSON</legend>
      <label>공고 갱신 JSON 파일<input type="file" accept=".json,application/json" onChange={event => {
        const file = event.target.files?.[0]; if (!file) return
        if (!/\.json$/i.test(file.name) || file.size > 1_000_000) { setError('1MB 이하의 UTF-8 .json 파일을 선택하세요.'); return }
        setBusy(true); void file.text().then(input).catch(() => setError('파일을 읽지 못했습니다.')).finally(() => setBusy(false))
        event.target.value = ''
      }} /></label>
      <label>공고 갱신 JSON<textarea rows={10} value={text} spellCheck={false} onChange={event => input(event.target.value)} /></label>
      <button type="button" disabled={!text.trim()} onClick={inspectDocument}>현재 공고와 JSON 비교</button>
    </fieldset>
    {document && <>
      <fieldset disabled={busy || uncertain} className={styles.input}><legend>2. 적용할 항목 확인</legend>
        <p>확인 가능한 변경은 미리 선택합니다. null·누락·미확인 항목은 기존값을 유지합니다. 같은 이름의 여러 후보는 직접 선택하세요.</p>
        {document.unresolvedFields.length > 0 && <StoredDataTable data={document.unresolvedFields} label="추출 과정에서 확인하지 못한 항목" readable />}
        <div className={styles.tableScroll} tabIndex={0} role="region" aria-label="기본정보 변경 비교"><table><caption>기본정보·접수처</caption><thead><tr><th scope="col">적용</th><th scope="col">항목</th><th scope="col">현재값</th><th scope="col">추출값</th></tr></thead><tbody>
          {metadataFields(document, workspace).map(field => <tr key={field.name}>
            <td><input aria-label={`${field.label} 적용`} type="checkbox" disabled={!field.available || !field.changed} checked={fields.includes(field.name)} onChange={event => { setFields(current => event.target.checked ? [...current, field.name] : current.filter(item => item !== field.name)); change() }} /></td>
            <th scope="row">{field.label}{field.locked && <small>확인된 일정은 접수 일정에서 수정</small>}{field.unresolved && <small>추출 근거 미확인</small>}</th><td>{display(field.before)}</td><td>{typeof field.value === 'string' ? display(field.value) : '미확인'}</td>
          </tr>)}
        </tbody></table></div>
        <ChildMappings title="일반 일정" values={document.schedules} current={workspace.events} destinations={eventDestinations} onChange={values => { setEventDestinations(values); change() }} />
        <p>일반 일정은 공고문에 적힌 행사 일정입니다. 단지·순위·조건별로 확인된 접수 일정은 그대로 유지합니다.</p>
        <ChildMappings title="첨부파일" values={document.attachments} current={(workspace.detail.attachments ?? []).map(item => ({ id: item.attachmentId, data: { ...item } }))} destinations={attachmentDestinations} onChange={values => { setAttachmentDestinations(values); change() }} />
        <p>선택하지 않은 기존 일정·첨부파일은 삭제하지 않습니다. 파일 URL을 저장하며 PDF 파일을 업로드하지 않습니다.</p>
        <h3>공급행·대상 조건 대응</h3>
        {document.supplyRows.length === 0 && <p>JSON에 공급행이 없습니다. 현재 공급정보를 유지합니다.</p>}
        {document.supplyRows.map((row, index) => <details key={index} open={!destinations[index]}><summary>입력 {index + 1} · {String((row.complexReference as JsonObject | undefined)?.sourceComplexName ?? '단지 미확인')} · {String(row.sourceHousingTypeName ?? '주택형 미확인')}{!destinations[index] ? ' · 대응 확인 필요' : ''}</summary>
          <label>공급행 {index + 1} 적용 대상<select value={destinations[index]} onChange={event => {
            const value = event.target.value; setDestinations(current => current.map((item, i) => i === index ? value : item))
            setTargetDestinations(current => current.map((item, i) => i === index ? defaultTargetDestinations(confirmedSupplyRow(document, index), workspace.supply.rows.find(existing => existing.key === value)) : item)); setRowsImported(false); change()
          }}><option value="">가져오지 않음 · 대응 확인 필요</option><option value="new">새 공급행으로 추가</option>{workspace.supply.rows.map(existing => <option key={existing.key} value={existing.key}>{existing.complexName} · {existing.typeName} · #{existing.id}{existing.excluded ? ' · 제외 상태' : ''}</option>)}</select></label>
          <StoredDataTable data={row} label={`추출 공급행 ${index + 1}`} readable />
          {destinations[index] && (Array.isArray(row.targets) ? row.targets : []).map((target: JsonObject, position: number) => <label key={position}>입력 {index + 1} 조건 {position + 1} · {String(target.target ?? '대상 미확인')} · {String(target.supplyRank ?? '순위 미확인')}
            <select aria-label={`입력 ${index + 1} 조건 ${position + 1} 적용 대상`} disabled={isUnresolved(document, `supplyRows[${index}].targets[${position}]`)} value={targetDestinations[index]?.[position] ?? ''} onChange={event => { setTargetDestinations(current => current.map((items, i) => i === index ? items.map((item, j) => j === position ? event.target.value : item) : items)); setRowsImported(false); change() }}>
              <option value="">가져오지 않음 · 대응 확인 필요</option><option value="new">새 조건으로 추가</option>{workspace.supply.rows.find(existing => existing.key === destinations[index])?.targets.map(existing => <option key={existing.key} value={existing.id ?? ''}>{existing.target} · {existing.supplyRank ?? '순위 미확인'} · #{existing.id}</option>)}
            </select></label>)}
        </details>)}
        <button type="button" disabled={!destinations.some(Boolean)} onClick={importRows}>선택한 공급정보를 변경안으로 가져오기</button>
        <button type="button" onClick={() => { setDestinations(current => current.map(() => '')); setRowsImported(false); change() }}>이번에는 공급정보 유지</button>
        {destinations.some(Boolean) && !rowsImported && <p>공급정보는 위 버튼으로 변경안에 가져와야 적용 대상에 포함됩니다.</p>}
      </fieldset>
      {rowsImported && <fieldset disabled={busy || uncertain}><legend>공급정보 변경안·단지 연결</legend><p>JSON에서 확인한 값만 가져왔습니다. 기존 조건과 공통 모집수는 유지합니다. 새 공급행의 단지·주택형 연결을 확인하세요.</p>
        {rows.map((row, index) => <SupplyRowDraft key={row.key} value={row} first={index === 0} last={index === rows.length - 1}
          onChange={next => { setRows(current => current.map(item => item.key === next.key ? next : item)); change() }}
          onRemove={() => { setRows(current => current.filter(item => item.key !== row.key)); change() }}
          onMove={delta => { setRows(current => { const next = [...current]; [next[index], next[index + delta]] = [next[index + delta], next[index]]; return next.map((item, order) => ({ ...item, displayOrder: order })) }); change() }} />)}
      </fieldset>}
      <form onSubmit={event => { event.preventDefault(); void inspect() }}><fieldset disabled={busy || uncertain} className={styles.input}><legend>3. 근거 확인과 변경 미리보기</legend>
        <label>갱신 이유<textarea required maxLength={2000} rows={3} value={reason} onChange={event => { setReason(event.target.value); change() }} /></label>
        <label>공식 근거 URL<input required type="url" maxLength={2048} value={sourceUrl} onChange={event => { setSourceUrl(event.target.value); change() }} /></label>
        <button className="admin-primary" disabled={destinations.some(Boolean) && !rowsImported} type="submit">전체 변경 미리보기</button>
      </fieldset></form>
    </>}
    {preview && <section className={styles.input} aria-label="공고 전체 변경 미리보기"><h3>확인 후 현재 공고에 적용</h3>
      <p>기본정보 {preview.result.fields.length}개 · 일반 일정 {preview.result.schedules.length}개 · 첨부 {preview.result.attachments.length}개{preview.result.supply ? ' · 공급정보 변경 포함' : ' · 공급정보 유지'}</p>
      {preview.result.fields.length > 0 && <ChangesTable changes={preview.result.fields} title="기본정보 변경 전후" />}
      <ChildChanges changes={preview.result.schedules} title="일반 일정" />
      <ChildChanges changes={preview.result.attachments} title="첨부파일" />
      {preview.result.supply && <SupplyCorrectionPreview value={preview.result.supply} />}
      <ul>{preview.result.warnings.map(item => <li key={item}>{item}</li>)}</ul>
      <button className="admin-primary" type="button" disabled={busy || uncertain} onClick={() => void apply()}>{busy ? '적용 중…' : '확인한 변경으로 현재 공고 갱신'}</button>
    </section>}
    <div className={styles.actions}><button type="button" disabled={busy} onClick={() => void reload()}>입력을 유지하고 최신 저장값 확인</button><Link to={`/explore?announcementId=${id}`} target="_blank" rel="noreferrer">공개 공고 확인 ↗</Link></div>
  </section>
}

const fieldNames = Object.fromEntries(announcementSections.flatMap(section => section.fields).map(field => [field.name, field.label]))
const childFieldNames: Record<string, string> = { scheduleType: '일정 유형', name: '이름', startAt: '시작 시각', endAt: '종료 시각', fileName: '파일명', fileType: '첨부 종류', fileUrl: '파일 URL' }
const childValues: Record<string, string> = { APPLICATION: '접수', DOCUMENT_SUBMISSION: '서류 제출', WINNER_ANNOUNCEMENT: '당첨 발표', CONTRACT: '계약', MOVE_IN: '입주', ANNOUNCEMENT: '원공고', CORRECTION: '정정', CANCELLATION: '취소', REFERENCE: '참고' }
function changeValue(value: unknown) {
  if (typeof value === 'string') return childValues[value] ?? display(value)
  if (typeof value === 'number' || typeof value === 'boolean' || value === null) return display(value)
  return '미확인'
}
function ChangesTable({ title, changes }: { title: string; changes: JsonChange[] }) {
  return <div className={styles.tableScroll} tabIndex={0} role="region" aria-label={title}><table><caption>{title}</caption>
    <thead><tr><th scope="col">항목</th><th scope="col">현재값</th><th scope="col">적용할 값</th></tr></thead>
    <tbody>{changes.map(change => <tr key={change.field}><th scope="row">{fieldNames[change.field] ?? childFieldNames[change.field] ?? change.field}</th><td>{changeValue(change.before)}</td><td>{changeValue(change.after)}</td></tr>)}</tbody>
  </table></div>
}
function ChildChanges({ title, changes }: { title: string; changes: ChildChange[] }) {
  return <>{changes.map(change => <ChangesTable key={change.inputIndex} title={`${title} 입력 ${change.inputIndex + 1} · ${change.existingId === null ? '새 항목 추가' : `기존 #${change.existingId} 갱신`}`}
    changes={Object.entries(change.after).filter(([field, value]) => JSON.stringify(value) !== JSON.stringify(change.before?.[field])).map(([field, after]) => ({ field, before: change.before?.[field] ?? null, after }))} />)}</>
}

function ChildMappings({ title, values, current, destinations, onChange }: { title: string; values: JsonObject[]; current: { id: number; data: JsonObject }[]; destinations: string[]; onChange: (values: string[]) => void }) {
  return <section><h3>{title}</h3>{values.length === 0 ? <p>추출 항목이 없습니다. 현재 {title}을 유지합니다.</p> : values.map((value, index) => <details key={index}><summary>입력 {index + 1} · {String(value.name ?? value.fileName ?? '이름 미확인')} · {destinations[index] === 'new' ? '추가 예정' : destinations[index] ? '기존 항목 갱신' : '대응 확인 필요'}</summary>
    <StoredDataTable data={value} label={`${title} 입력 ${index + 1}`} readable />
    <label>{title} {index + 1} 적용 대상<select value={destinations[index]} onChange={event => onChange(destinations.map((item, i) => i === index ? event.target.value : item))}>
      <option value="">가져오지 않음 · 대응 확인 필요</option><option value="new">새 항목으로 추가</option>{current.map(item => <option key={item.id} value={item.id}>{String(item.data.name ?? item.data.fileName)} · #{item.id}</option>)}
    </select></label>
    {destinations[index] && destinations[index] !== 'new' && <StoredDataTable data={current.find(item => String(item.id) === destinations[index])?.data} label={`${title} 현재값 ${index + 1}`} readable />}
  </details>)}</section>
}
