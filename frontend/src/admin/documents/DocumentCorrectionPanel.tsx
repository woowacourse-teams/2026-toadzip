import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { display, labels, valueAt } from '../management/fields'
import { ManagementError, requestManagementApi } from '../management/api'
import { parseManagementDetail, type ManagementDetailData, type ManagementResource } from '../management/managementContract'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { agentPacket, correctionBody, downloadText, parseReviewResult, reviewTarget, safeDocumentUrl, targetChoices, type ReviewResult } from './documentReview'
import { openPdf, type ExtractedDocument, type PdfSession } from './pdfExtraction'
import styles from './DocumentReview.module.css'

const PdfPreview = lazy(() => import('../../public-housing/components/PdfDocumentPreview'))

export function DocumentCorrectionPanel({ resource, detail, onSaved, onBusyChange, onReload }: {
  resource: ManagementResource; detail: ManagementDetailData; onSaved: (detail: ManagementDetailData) => void
  onBusyChange: (busy: boolean) => void; onReload: () => void
}) {
  const [selection, setSelection] = useState('basic')
  const target = reviewTarget(resource, detail, selection)
  const [session, setSession] = useState<PdfSession | null>(null)
  const [previewUrl, setPreviewUrl] = useState('')
  const [preview, setPreview] = useState(false)
  const [pages, setPages] = useState('1')
  const [sourceUrl, setSourceUrl] = useState(typeof detail.data.originalUrl === 'string' ? detail.data.originalUrl : '')
  const [extracted, setExtracted] = useState<ExtractedDocument | null>(null)
  const [json, setJson] = useState('')
  const [review, setReview] = useState<ReviewResult | null>(null)
  const [checked, setChecked] = useState<string[]>([])
  const [note, setNote] = useState('')
  const [method, setMethod] = useState<'manual' | 'agent'>('manual')
  const [manualDirty, setManualDirty] = useState(false)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  const request = useRef<AbortController | null>(null)
  const sessionRef = useRef<PdfSession | null>(null)
  const previewRef = useRef('')
  const mounted = useRef(true)
  useEffect(() => { onBusyChange(Boolean(busy)) }, [busy, onBusyChange])
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false; request.current?.abort()
      void sessionRef.current?.destroy().catch(() => {})
      if (previewRef.current) URL.revokeObjectURL(previewRef.current)
    }
  }, [])
  useUnsavedChanges(Boolean(json.trim() || review?.proposals.length || manualDirty || note.trim()))

  function clearReview() { setReview(null); setChecked([]); setJson(''); setError('') }
  async function selectFile(file: File) {
    request.current?.abort(); void sessionRef.current?.destroy().catch(() => {})
    if (previewRef.current) URL.revokeObjectURL(previewRef.current)
    sessionRef.current = null; previewRef.current = ''; setSession(null); setPreviewUrl(''); setPreview(false)
    setExtracted(null); clearReview(); setBusy('PDF 준비 중…')
    const controller = new AbortController(); request.current = controller
    try {
      const opened = await openPdf(file, controller.signal)
      if (controller.signal.aborted || !mounted.current) { await opened.destroy(); return }
      sessionRef.current = opened; setSession(opened); setPages('1')
      const url = URL.createObjectURL(file); previewRef.current = url; setPreviewUrl(url)
    } catch (cause) {
      if (!controller.signal.aborted && mounted.current) setError(cause instanceof Error ? cause.message : 'PDF를 읽지 못했습니다.')
    } finally { if (!controller.signal.aborted && mounted.current) setBusy('') }
  }
  async function extract() {
    if (!session || busy) return
    clearReview(); setExtracted(null); setBusy('페이지 추출 중…')
    const controller = new AbortController(); request.current = controller
    try {
      const document = await session.extract(pages, controller.signal, (done, total) => {
        if (!controller.signal.aborted && mounted.current) setBusy(`페이지 추출 중… ${done}/${total}`)
      })
      if (!controller.signal.aborted && mounted.current) setExtracted(document)
    } catch (cause) {
      if (!controller.signal.aborted && mounted.current) setError(cause instanceof Error ? cause.message : '추출하지 못했습니다.')
    } finally { if (!controller.signal.aborted && mounted.current) setBusy('') }
  }
  function validate(text = json) {
    if (!extracted) return
    try { const result = parseReviewResult(text, target, extracted); setReview(result); setChecked([]); setError('') }
    catch (cause) { setReview(null); setChecked([]); setError(cause instanceof Error ? cause.message : '결과를 확인하지 못했습니다.') }
  }
  async function resultFile(file: File) {
    clearReview()
    if (file.size > 1000000) { setError('결과 JSON은 1MB 이하여야 합니다.'); return }
    setBusy('결과 파일 읽는 중…')
    try {
      const text = await file.text()
      if (mounted.current) { setJson(text); validate(text) }
    } catch { if (mounted.current) setError('결과 파일을 읽지 못했습니다.') }
    finally { if (mounted.current) setBusy('') }
  }
  async function save() {
    if (!extracted || !review || busy || conflict || detail.summary.deleted) return
    let body
    try { body = correctionBody(target, review.proposals.filter(item => checked.includes(item.field)), extracted, sourceUrl, note) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '확인 내용을 입력해 주세요.'); return }
    setBusy('확인한 항목 저장 중…'); setError('')
    try {
      const next = parseManagementDetail(await requestManagementApi(target.endpoint, 'PUT', body))
      if (mounted.current) { clearReview(); onSaved(next) }
    } catch (cause) {
      if (!mounted.current) return
      setError(cause instanceof Error ? cause.message : '저장하지 못했습니다.')
      if (cause instanceof ManagementError && cause.status === 409) setConflict(true)
    } finally { if (mounted.current) setBusy('') }
  }
  const missing = target.fields.filter(field => valueAt(target.current, field.name) === null || valueAt(target.current, field.name) === undefined)
  return <section className={`${styles.panel} ph-no-capture`} aria-label="PDF 근거로 데이터 보완" aria-busy={Boolean(busy)}>
    <header className={styles.pageHeading}><span className={styles.eyebrow}>공식 자료로 정보 보완</span><h2>PDF 근거로 데이터 보완</h2><p>자료를 읽고 수정안을 만든 뒤, 확인한 항목만 저장하세요.</p></header>
    <ol className={styles.workflow} aria-label="PDF 보완 순서">{['자료 선택', '근거 확인', '수정안 작성', '확인·저장'].map((label, index) => <li key={label} aria-current={index === (!extracted ? 0 : review ? 3 : 2) ? 'step' : undefined}><b>{index + 1}</b>{label}</li>)}</ol>
    <details className={styles.help}><summary>어떤 정보를 PDF로 보완할 수 있나요?</summary><p>세대수·시설 정보·임대조건을 보완할 수 있습니다. 주소는 PNU·좌표를 함께 확인해 기본정보에서, 확인된 접수 일정은 접수 일정 관리에서 수정하세요.</p></details>
    {detail.summary.deleted ? <p role="alert">휴지통에서 복구한 뒤 수정할 수 있습니다.</p> : null}
    {conflict ? <div className={styles.warning}><p>현재 데이터가 바뀌었습니다. 결과를 내려받아 보관하고 새로 조회한 자료로 다시 검토하세요.</p>
      <button type="button" disabled={Boolean(busy)} onClick={onReload}>현재 데이터 다시 조회</button></div> : null}
    <details className={styles.sourceFold} open={!extracted}><summary>{extracted ? `${extracted.fileName} · ${extracted.pages.length}쪽 추출 완료 · 자료 다시 선택` : '1. 공식 자료 선택'}</summary>
    <fieldset className={`${styles.inputs} ${styles.card}`} disabled={Boolean(busy) || detail.summary.deleted || conflict}>
      <legend>1. 대상과 공식 자료 선택</legend>
      <div className={styles.documentSourceGrid}><label>보완 대상<select value={selection} onChange={event => { setSelection(event.target.value); clearReview() }}>
        {targetChoices(resource, detail).map(choice => <option key={choice.key} value={choice.key}>{choice.label}</option>)}
      </select></label>
      <label>공식 출처 URL<input type="url" maxLength={2048} value={sourceUrl} placeholder="https://"
        onChange={event => { setSourceUrl(event.target.value); setReview(null); setChecked([]) }} /></label></div>
      <p className={styles.hint}>{missing.length ? `미확인 항목: ${missing.map(field => field.label).join(', ')}` : '빈값이 없는 항목도 실제와 다른지 원문과 대조할 수 있습니다.'}</p>
      <label className={styles.upload}>공식 PDF 파일<input type="file" accept=".pdf,application/pdf" onChange={event => {
        const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; if (file) void selectFile(file)
      }} /></label>
      <p className={styles.hint}>25MB 이하. 파일은 브라우저에서 처리하며 에이전트 자료 다운로드 전에는 외부로 전송하지 않습니다.</p>
      {session ? <><p>{session.fileName} · 전체 {session.totalPages}쪽</p>
        <div className={styles.sourceActions}><label>추출할 쪽수<input value={pages} maxLength={500} onChange={event => { setPages(event.target.value); setExtracted(null); clearReview() }} placeholder="예: 1-3, 7" /></label>
        <Button data-design-system onClick={() => void extract()}>선택 페이지 추출</Button>
        {!extracted ? <button type="button" onClick={() => setPreview(value => !value)} aria-expanded={preview}>{preview ? '원문 접기' : 'PDF 원문 보기'}</button> : null}</div>
      </> : null}
    </fieldset></details>
    {busy ? <div role="status"><p>{busy}</p>{busy.startsWith('페이지 추출') ? <button type="button" onClick={() => {
      request.current?.abort(); setBusy(''); setError('추출을 취소했습니다. 필요한 쪽수를 다시 선택할 수 있습니다.')
    }}>추출 취소</button> : null}</div> : null}
    {preview && previewUrl ? <div className={styles.preview}><Suspense fallback={<p role="status">원문 준비 중…</p>}><PdfPreview url={previewUrl} name={session?.fileName ?? '공식 PDF'} /></Suspense></div> : null}
    {extracted ? <div className={styles.reviewLayout}>
      <section className={`${styles.card} ${styles.reference}`}><h3>2. 추출 근거 확인</h3>
        <p>선택한 {extracted.pages.length}쪽 · 텍스트 {extracted.pages.reduce((sum, page) => sum + page.text.length, 0).toLocaleString()}자</p>
        {extracted.pages.map((page, index) => <details key={page.page} open={index === 0}><summary>{page.page}쪽 {page.requiresOcr ? '· OCR 필요' : '· 추출 텍스트'}</summary>
          {page.requiresOcr ? <p className={styles.warning}>읽을 수 있는 텍스트가 없습니다. OCR 후 PDF를 다시 선택하거나 기존 편집 화면에서 원문을 확인하고 수기로 수정하세요.</p>
            : <pre className={styles.text}>{page.text}</pre>}</details>)}
        <p className={styles.hint}>표의 열·단위·각주는 원문과 함께 확인하세요. 파일이 바뀌면 새로 선택해 다시 추출합니다.</p>
        <button type="button" onClick={() => setPreview(value => !value)} aria-expanded={preview}>{preview ? '원문 접기' : 'PDF 원문 보기'}</button>
        <Button data-design-system disabled={Boolean(busy) || !safeDocumentUrl(sourceUrl)} onClick={() => {
          try { downloadText(agentPacket(target, extracted, sourceUrl), `document-review-${detail.summary.id}.json`) }
          catch (cause) { setError(cause instanceof Error ? cause.message : '자료를 만들지 못했습니다.') }
        }}>에이전트 자료 다운로드</Button>
        <p className={styles.hint}>다운로드한 파일을 사용하는 에이전트에 전달하세요. 현재값·허용 항목·페이지 근거·응답 형식이 포함됩니다.</p>
      </section>
      <section className={styles.card}><h3>3. 수정안 가져오기 또는 수기 입력</h3>
        <div className={styles.modeSwitch} aria-label="수정안 작성 방식"><button type="button" aria-pressed={method === 'manual'} disabled={Boolean(busy) || conflict || detail.summary.deleted} onClick={() => setMethod('manual')}>직접 입력</button><button type="button" aria-pressed={method === 'agent'} disabled={Boolean(busy) || conflict || detail.summary.deleted} onClick={() => setMethod('agent')}>에이전트 결과 가져오기</button></div>
        {method === 'agent' ? <fieldset className={styles.inputs} disabled={Boolean(busy) || conflict || detail.summary.deleted}>
          <legend className={styles.srOnly}>에이전트 결과</legend>
          <p className={styles.hint}>에이전트 자료를 전달한 뒤, 받은 결과 파일을 선택하거나 아래에 붙여넣으세요.</p>
          <label className={styles.upload}>에이전트 결과 JSON 파일<input type="file" accept=".json,application/json" onChange={event => {
            const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; if (file) void resultFile(file)
          }} /></label>
          <label>에이전트 결과 JSON<textarea rows={4} value={json} spellCheck={false} onChange={event => {
            setJson(event.target.value); setReview(null); setChecked([]); setError('')
          }} /></label>
          <button type="button" disabled={!json.trim()} onClick={() => validate()}>결과 검증</button>
        </fieldset> : null}
        <div hidden={method !== 'manual'}><ManualProposal key={target.key} target={target} document={extracted} onDirtyChange={setManualDirty} disabled={Boolean(busy) || conflict || detail.summary.deleted} onAdded={proposal => {
          const proposals = [...(review?.proposals.filter(item => item.field !== proposal.field) ?? []), proposal]
          const unresolved = review?.unresolved.filter(item => item.field !== proposal.field) ?? []
          const next = JSON.stringify({ schemaVersion: 'document-review/v1', targetKey: target.key, version: target.version,
            sha256: extracted.sha256, proposals, unresolved }, null, 2)
          setJson(next); validate(next)
        }} /></div>
      </section>
      {review ? <section className={`${styles.card} ${styles.review}`} aria-label="수정안 검토"><header className={styles.cardHeading}><h3>4. 원문과 대조 후 선택 반영</h3><span className={styles.badge}>{checked.length}/{review.proposals.length}개 확인</span></header>
        {review.unresolved.length ? <div className={styles.warning}><strong>보류 항목</strong><ul>{review.unresolved.map((item, index) => <li key={index}>{item.field}: {item.reason}</li>)}</ul></div> : null}
        {!review.proposals.length ? <p>반영할 확정 수정안이 없습니다.</p> : <p>원문에서 값·단위·적용 대상을 확인한 항목만 선택하세요.</p>}
        {review.proposals.map(proposal => {
          const field = target.fields.find(field => field.name === proposal.field)
          return <article key={proposal.field} className={styles.proposal}>
            <label><input type="checkbox" disabled={Boolean(busy) || conflict || detail.summary.deleted} checked={checked.includes(proposal.field)}
              onChange={event => setChecked(current => event.target.checked ? [...current, proposal.field] : current.filter(item => item !== proposal.field))} />
              {field?.label ?? proposal.field} 원문 확인</label>
            <dl><div><dt>현재값</dt><dd>{display(valueAt(target.current, proposal.field))}</dd></div><div><dt>수정안</dt><dd>{display(proposal.value)} · {proposal.unit}</dd></div></dl>
            <p>{proposal.scope} · PDF {proposal.page}쪽</p><blockquote>{proposal.quote}</blockquote><p>{proposal.reason}</p>
          </article>
        })}
        <label className={styles.note}>확인 메모<textarea rows={3} maxLength={2000} value={note} disabled={Boolean(busy) || conflict || detail.summary.deleted}
          onChange={event => setNote(event.target.value)} placeholder="값·단위·적용 범위와 정정 여부를 확인한 내용" /></label>
        <div className={`${styles.actions} ${styles.saveActions}`}><p className={styles.hint}>{!checked.length ? '원문에서 확인한 항목을 선택하세요.' : !note.trim() ? '확인 메모를 작성하면 저장할 수 있습니다.' : '선택한 항목과 PDF 근거가 수정 이력에 저장됩니다.'}</p><Button data-design-system disabled={!checked.length || !note.trim() || !safeDocumentUrl(sourceUrl) || Boolean(busy) || conflict || detail.summary.deleted}
          onClick={() => void save()}>확인한 {checked.length}개 항목 반영</Button>
          <button type="button" disabled={Boolean(busy)} onClick={() => downloadText(json, `document-review-result-${detail.summary.id}.json`)}>검토 결과 다운로드</button></div>
      </section> : null}
    </div> : null}
    {error ? <p className={styles.error} role="alert">{error}</p> : null}
  </section>
}

function ManualProposal({ target, document, disabled, onAdded, onDirtyChange }: {
  target: ReturnType<typeof reviewTarget>; document: ExtractedDocument; disabled: boolean
  onAdded: (proposal: ReviewResult['proposals'][number]) => void; onDirtyChange: (dirty: boolean) => void
}) {
  const [fieldName, setFieldName] = useState(target.fields[0]?.name ?? '')
  const [value, setValue] = useState(''); const [page, setPage] = useState('')
  const [quote, setQuote] = useState(''); const [reason, setReason] = useState(''); const [error, setError] = useState('')
  const field = target.fields.find(item => item.name === fieldName)
  useEffect(() => { onDirtyChange(Boolean(value || quote || reason)); return () => onDirtyChange(false) }, [value, quote, reason, onDirtyChange])
  return <section className={styles.manual}><p className={styles.hint}>원문을 확인하며 수기 수정안 추가</p>
    <form className={styles.inputs} onSubmit={event => {
      event.preventDefault()
      if (!field || disabled) return
      try {
        const proposed: unknown = field.type === 'number' || field.boolean ? JSON.parse(value) as unknown : value
        const parsed = parseReviewResult(JSON.stringify({ schemaVersion: 'document-review/v1', targetKey: target.key,
          version: target.version, sha256: document.sha256, proposals: [{ field: field.name, value: proposed,
            page: Number(page), quote, unit: field.unit, scope: field.scope, reason }], unresolved: [] }), target, document)
        onAdded(parsed.proposals[0]); setError(''); setValue(''); setQuote(''); setReason('')
      } catch (cause) { setError(cause instanceof Error ? cause.message : '수기 입력을 확인해 주세요.') }
    }}><fieldset disabled={disabled}><legend>수기 수정안</legend>
      <label>수정 항목<select value={fieldName} onChange={event => { setFieldName(event.target.value); setValue(''); setError('') }}>
        {target.fields.map(item => <option key={item.name} value={item.name}>{item.label}</option>)}
      </select></label>
      <label>수정할 값{field?.boolean || field?.options ? <select required value={value} onChange={event => setValue(event.target.value)}><option value="">선택해 주세요</option>{(field.boolean ? ['true', 'false'] : field.options ?? []).map(option => <option key={option} value={option}>{labels[option] ?? option}</option>)}</select>
        : <input required type={field?.type === 'number' ? 'number' : field?.type === 'date' ? 'date' : 'text'} inputMode={field?.money ? 'numeric' : undefined} min={field?.min} max={field?.max} step={field?.step ?? '1'} value={value} maxLength={255} onChange={event => setValue(event.target.value)} />}</label>
      <p className={styles.hint}>단위: {field?.unit} · 범위: {field?.scope}</p>
      <label>근거 쪽수<select required value={page} onChange={event => setPage(event.target.value)}><option value="">선택</option>
        {document.pages.filter(item => !item.requiresOcr).map(item => <option key={item.page} value={item.page}>{item.page}쪽</option>)}</select></label>
      <label>원문 인용<textarea required maxLength={2000} rows={3} value={quote} onChange={event => setQuote(event.target.value)} /></label>
      <label>변경 이유<input required maxLength={1000} value={reason} onChange={event => setReason(event.target.value)} /></label>
      <button type="submit">수기 수정안 추가</button>
    </fieldset>{error ? <p role="alert" className={styles.error}>{error}</p> : null}</form>
  </section>
}
