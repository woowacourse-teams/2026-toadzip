import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import { display, labels, valueAt } from '../management/fields'
import { ManagementError, requestManagementApi } from '../management/api'
import { isRecord, parseManagementDetail, type ManagementDetailData, type ManagementResource } from '../management/managementContract'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { agentPacket, correctionBody, downloadText, parseReviewResult, reviewTarget, safeDocumentUrl, targetChoices, type ReviewResult } from './documentReview'
import { openPdf, type ExtractedDocument, type PdfSession } from './pdfExtraction'
import styles from './DocumentReview.module.css'
import { readReviewDraft, removeReviewDraft, saveReviewDraft } from './reviewDraftStore'
import { DocumentArchivePanel } from './DocumentArchivePanel'
import { PdfTablePanel } from './PdfTablePanel'
import { SharedDraftPanel } from './SharedDraftPanel'
import { sharedDraftFile, type SharedDraft } from './sharedDraftApi'
import { FolderExchangePanel } from './FolderExchangePanel'
import { IncomingReviewPanel } from './IncomingReviewPanel'

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
  const [incomingReview, setIncomingReview] = useState<ReviewResult | null>(null)
  const [resultBackup, setResultBackup] = useState<{ context: string; json: string; note: string } | null>(null)
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
  const originalFile = useRef<File | null>(null)
  const [draftNotice, setDraftNotice] = useState('')
  const [archiveBusy, setArchiveBusy] = useState(false)
  const [sharedBusy, setSharedBusy] = useState(false)
  const [exchangeBusy, setExchangeBusy] = useState(false)
  const [phase, setPhase] = useState<'source' | 'draft' | 'confirm'>('source')
  const [toolsOpen, setToolsOpen] = useState(false)
  const stepNav = useRef<HTMLOListElement>(null)
  const previousPhase = useRef(phase)
  useEffect(() => {
    if (previousPhase.current === phase) return
    previousPhase.current = phase
    stepNav.current?.querySelector<HTMLButtonElement>('[aria-current="step"]')?.focus()
  }, [phase])
  const workspaceBusy = Boolean(busy) || sharedBusy || exchangeBusy || Boolean(incomingReview)
  const resultContext = `${target.key}:${target.version}:${extracted?.sha256 ?? ''}`
  useEffect(() => { onBusyChange(Boolean(busy) || archiveBusy || sharedBusy || exchangeBusy || Boolean(incomingReview)) }, [busy, archiveBusy, sharedBusy, exchangeBusy, incomingReview, onBusyChange])
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false; request.current?.abort()
      void sessionRef.current?.destroy().catch(() => {})
      if (previewRef.current) URL.revokeObjectURL(previewRef.current)
    }
  }, [])
  useUnsavedChanges(Boolean(json.trim() || review?.proposals.length || manualDirty || note.trim()))

  function clearReview() { setReview(null); setIncomingReview(null); setResultBackup(null); setChecked([]); setJson(''); setError('') }
  async function selectFile(file: File) {
    request.current?.abort(); void sessionRef.current?.destroy().catch(() => {})
    if (previewRef.current) URL.revokeObjectURL(previewRef.current)
    sessionRef.current = null; previewRef.current = ''; setSession(null); setPreviewUrl(''); setPreview(false)
    setExtracted(null); clearReview(); setPhase('source'); setBusy('PDF 준비 중…')
    const controller = new AbortController(); request.current = controller
    try {
      const opened = await openPdf(file, controller.signal)
      if (controller.signal.aborted || !mounted.current) { await opened.destroy(); return }
      sessionRef.current = opened; originalFile.current = file; setSession(opened); setPages('1')
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
      if (!controller.signal.aborted && mounted.current) { setExtracted(document); setPhase('draft') }
    } catch (cause) {
      if (!controller.signal.aborted && mounted.current) setError(cause instanceof Error ? cause.message : '추출하지 못했습니다.')
    } finally { if (!controller.signal.aborted && mounted.current) setBusy('') }
  }
  function validate(text = json) {
    if (!extracted) return
    try { const result = parseReviewResult(text, target, extracted); setReview(result); setChecked([]); setError(''); setPhase('confirm') }
    catch (cause) { setReview(null); setChecked([]); setError(cause instanceof Error ? cause.message : '결과를 확인하지 못했습니다.') }
  }
  function applyIncoming(result: ReviewResult) {
    if (!extracted) return
    const text = JSON.stringify({ schemaVersion: 'document-review/v1', targetKey: target.key,
      version: target.version, sha256: extracted.sha256, ...result }, null, 2)
    try {
      const validated = parseReviewResult(text, target, extracted)
      if (json.trim() && json !== text) setResultBackup({ context: resultContext, json, note })
      setJson(text); setReview(validated); setChecked([]); setIncomingReview(null); setMethod('agent'); setPhase('confirm'); setError('')
      setDraftNotice(json.trim() && json !== text ? '가져온 결과를 반영했습니다. 이전 입력은 이 화면에서 복원할 수 있습니다. 원문 확인과 DB 저장은 별도로 진행하세요.' : '가져온 결과를 반영했습니다. 원문 확인과 DB 저장은 별도로 진행하세요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '수정안을 반영하지 못했습니다.') }
  }
  function importAgentResult(text: string) {
    if (!extracted) return
    try {
      const incoming = parseReviewResult(text, target, extracted)
      if (review?.proposals.length) { setIncomingReview(incoming); setPhase('confirm'); setError('') }
      else applyIncoming(incoming)
    } catch (cause) { setError(cause instanceof Error ? cause.message : '결과를 확인하지 못했습니다.') }
  }
  function restorePreviousResult() {
    if (!resultBackup || resultBackup.context !== resultContext || !extracted) return
    setJson(resultBackup.json); setNote(resultBackup.note); setChecked([]); setIncomingReview(null); setResultBackup(null); setError('')
    try { setReview(parseReviewResult(resultBackup.json, target, extracted)); setPhase('confirm') }
    catch { setReview(null); setPhase('draft') }
    setDraftNotice('가져오기 전 입력을 복원했습니다. 수정안 검증과 원문 확인을 다시 진행하세요.')
  }
  function addProposal(proposal: ReviewResult['proposals'][number]) {
    if (!extracted) return
    const proposals = [...(review?.proposals.filter(item => item.field !== proposal.field) ?? []), proposal]
    const unresolved = review?.unresolved.filter(item => item.field !== proposal.field) ?? []
    const next = JSON.stringify({ schemaVersion: 'document-review/v1', targetKey: target.key, version: target.version,
      sha256: extracted.sha256, proposals, unresolved }, null, 2)
    setJson(next); validate(next)
  }
  async function restoreShared(draft: SharedDraft, signal: AbortSignal) {
    let opened: PdfSession | null = null
    try {
      const file = await sharedDraftFile(resource, detail.summary.id, draft, signal)
      opened = await openPdf(file, signal)
      if (opened.sha256 !== draft.sha256) throw new Error('공유 원본의 해시가 다릅니다. 원본을 확인해 주세요.')
      const document = await opened.extract(draft.pages, signal)
      if (signal.aborted || !mounted.current) return
      void sessionRef.current?.destroy().catch(() => {})
      if (previewRef.current) URL.revokeObjectURL(previewRef.current)
      const url = URL.createObjectURL(file); previewRef.current = url; setPreviewUrl(url)
      originalFile.current = file; sessionRef.current = opened; setSession(opened); opened = null
      setPages(draft.pages); setSourceUrl(draft.sourceUrl); setExtracted(document); setJson(draft.resultJson)
      setNote(draft.note); setReview(null); setIncomingReview(null); setResultBackup(null); setChecked([]); setConflict(false); setError(''); setMethod('agent')
      setPhase('draft'); setToolsOpen(false)
      setDraftNotice(draft.documentVersion === target.version ? '공유 입력을 복원했습니다. 수정안 검증과 원문 확인을 다시 수행하세요.' : '공유 작업 이후 DB 버전이 바뀌었습니다. 원본·입력만 복원했으며 최신 값으로 수정안을 다시 작성해야 합니다.')
    } finally { await opened?.destroy().catch(() => {}) }
  }
  async function preserveWork() {
    if (!session || !originalFile.current) return
    setBusy('작업 보관 중…'); setError('')
    try {
      await saveReviewDraft({ key: target.key, resource, id: detail.summary.id, selection, version: target.version,
        file: originalFile.current, fileName: session.fileName, sha256: session.sha256, pages, sourceUrl, json, note, updatedAt: new Date().toISOString() })
      if (mounted.current) setDraftNotice('원본 PDF와 입력 내용을 현재 브라우저에 보관했습니다. DB 수정은 저장하지 않았습니다.')
    } catch (cause) { if (mounted.current) setError(cause instanceof Error ? cause.message : '작업을 보관하지 못했습니다.') }
    finally { if (mounted.current) setBusy('') }
  }
  async function resumeWork() {
    if ((json.trim() || note.trim() || manualDirty) && !window.confirm('현재 입력을 보관 작업으로 바꿀까요?')) return
    request.current?.abort(); const controller = new AbortController(); request.current = controller
    setBusy('보관 작업 복원 중…'); setError('')
    let pendingSession: PdfSession | null = null
    try {
      const draft = await readReviewDraft(target.key)
      if (!draft) throw new Error('이 대상에 보관한 작업이 없습니다.')
      if (draft.key !== target.key || draft.resource !== resource || draft.id !== detail.summary.id || draft.selection !== selection) throw new Error('현재 대상과 보관 작업이 다릅니다.')
      controller.signal.throwIfAborted()
      const file = new File([draft.file], draft.fileName, { type: 'application/pdf' })
      const opened = await openPdf(file, controller.signal)
        pendingSession = opened
        if (opened.sha256 !== draft.sha256) throw new Error('보관 원본의 해시가 다릅니다. 원본 PDF를 다시 선택해 주세요.')
      const document = await opened.extract(draft.pages, controller.signal)
        if (controller.signal.aborted || !mounted.current) return
      await sessionRef.current?.destroy().catch(() => {})
      if (previewRef.current) URL.revokeObjectURL(previewRef.current)
      const url = URL.createObjectURL(file); previewRef.current = url; setPreviewUrl(url); setPreview(false)
      sessionRef.current = opened; originalFile.current = file; setSession(opened); setPages(draft.pages)
        pendingSession = null
      setExtracted(document); setSourceUrl(draft.sourceUrl); setJson(draft.json); setNote(draft.note); setReview(null); setIncomingReview(null); setResultBackup(null); setChecked([]); setConflict(false)
      setMethod(draft.json ? 'agent' : 'manual')
      setPhase('draft'); setToolsOpen(false)
      setDraftNotice(draft.version === target.version ? '작업을 복원했습니다. OCR 자료는 다시 OCR 처리하고 수정안을 검증한 뒤 원문과 대조해 선택하세요.' : '작업 이후 DB 버전이 바뀌었습니다. 원본·입력만 복원했습니다. 최신 값으로 수정안을 다시 작성해 주세요.')
    } catch (cause) { if (!controller.signal.aborted && mounted.current) setError(cause instanceof Error ? cause.message : '보관 작업을 읽지 못했습니다.') }
      finally { await pendingSession?.destroy().catch(() => {}); if (!controller.signal.aborted && mounted.current) setBusy('') }
  }
  async function runOcr() {
    if (!session?.ocr || busy) return
    request.current?.abort(); const controller = new AbortController(); request.current = controller
    clearReview(); setBusy('OCR 준비 중…')
    try {
      const document = await session.ocr(pages, controller.signal, message => { if (mounted.current && !controller.signal.aborted) setBusy(message) })
      if (mounted.current && !controller.signal.aborted) setExtracted(document)
    } catch (cause) { if (mounted.current && !controller.signal.aborted) setError(cause instanceof Error ? cause.message : 'OCR을 처리하지 못했습니다.') }
    finally { if (mounted.current && !controller.signal.aborted) setBusy('') }
  }
  async function resultFile(file: File) {
    setError('')
    if (file.size > 1000000) { setError('결과 JSON은 1MB 이하여야 합니다.'); return }
    setBusy('결과 파일 읽는 중…')
    try {
      let text = await file.text()
      const envelope: unknown = JSON.parse(text) as unknown
      if (isRecord(envelope) && envelope.schemaVersion === 'document-exchange/v1' && isRecord(envelope.result)) {
        text = JSON.stringify(envelope.result)
      }
      if (mounted.current) importAgentResult(text)
    } catch { if (mounted.current) setError('결과 파일을 읽지 못했습니다.') }
    finally { if (mounted.current) setBusy('') }
  }
  async function save() {
    if (!extracted || !review || workspaceBusy || conflict || detail.summary.deleted) return
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
    <header className={styles.pageHeading}><span className={styles.eyebrow}>공식 PDF로 정보 보완</span><h2>자료에서 확인한 값만 반영하세요</h2><p>PDF 선택부터 최종 확인까지, 세 단계로 진행합니다.</p></header>
    <ol ref={stepNav} className={`${styles.workflow} ${styles.stepNav}`} aria-label="PDF 보완 순서">{([['source', '자료 선택', '공식 PDF와 출처'], ['draft', '근거·수정안', '원문과 나란히 확인'], ['confirm', '확인·저장', '선택한 항목만 반영']] as const).map(([key, label, help], index) => <li key={key}><button type="button" aria-current={phase === key ? 'step' : undefined} disabled={workspaceBusy || archiveBusy || (key !== 'source' && !extracted) || (key === 'confirm' && !review)} onClick={() => setPhase(key)}><b>{index + 1}</b><span><strong>{label}</strong><small>{help}</small></span></button></li>)}</ol>
    <details className={styles.help}><summary>어떤 정보를 PDF로 보완할 수 있나요?</summary><p>세대수·시설 정보·임대조건을 보완할 수 있습니다. 주소는 PNU·좌표를 함께 확인해 기본정보에서, 확인된 접수 일정은 접수 일정 관리에서 수정하세요.</p></details>
    {detail.summary.deleted ? <p role="alert">휴지통에서 복구한 뒤 수정할 수 있습니다.</p> : null}
    {conflict ? <div className={styles.warning}><p>현재 데이터가 바뀌었습니다. 결과를 내려받아 보관하고 새로 조회한 자료로 다시 검토하세요.</p>
      <button type="button" disabled={Boolean(busy)} onClick={onReload}>현재 데이터 다시 조회</button></div> : null}
    {phase !== 'source' && extracted ? <div className={styles.documentContext}><div><strong>{extracted.fileName}</strong><span>{target.label} · {extracted.pages.length}쪽</span></div><button type="button" disabled={workspaceBusy} onClick={() => setPhase('source')}>자료 다시 선택</button></div> : null}
    <div hidden={phase !== 'source'} className={styles.sourceStage}>
    <fieldset className={`${styles.inputs} ${styles.card}`} disabled={workspaceBusy || detail.summary.deleted || conflict}>
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
    </fieldset><aside className={styles.sourceGuide}><span className={styles.eyebrow}>시작하기 전에</span><h3>어떤 자료가 필요한가요?</h3><p>공식 기관이 게시한 PDF와 원문 URL을 준비하세요. 필요한 쪽만 추출하면 더 빠르게 확인할 수 있습니다.</p><ol><li>보완할 단지·주택형·공급대상을 선택합니다.</li><li>원문 URL과 PDF 파일을 넣습니다.</li><li>쪽수를 정하고 ‘선택 페이지 추출’을 누릅니다.</li></ol><button type="button" onClick={() => setToolsOpen(value => !value)}>이전 작업·공유 원본에서 시작</button></aside></div>
    <details className={styles.workTools} open={toolsOpen} onToggle={event => setToolsOpen(event.currentTarget.open)}><summary>작업 보관·이어하기 <span>공유 초안 · 원본 보관함 · 이 브라우저의 작업</span></summary><div className={styles.toolsGrid}>
    <DocumentArchivePanel resource={resource} id={detail.summary.id} file={originalFile.current} sourceUrl={sourceUrl} onBusyChange={setArchiveBusy} />
    <SharedDraftPanel key={target.key} resource={resource} id={detail.summary.id} target={selection} file={originalFile.current}
      disabled={Boolean(busy) || archiveBusy || exchangeBusy || conflict || detail.summary.deleted} onBusyChange={setSharedBusy} onRestore={restoreShared}
      input={session && safeDocumentUrl(sourceUrl) ? { target: selection, documentVersion: target.version, sha256: session.sha256, sourceUrl, pages, resultJson: json, note } : null} />
    <details className={styles.card}><summary>원본·작업 보관 및 이어서 하기</summary><p className={styles.hint}>현재 브라우저에 최대 5개 작업·100MB를 보관합니다. 작업 내용은 다른 기기로 동기화하지 않습니다. 새 작업으로 덮어쓰면 같은 대상의 이전 보관 내용을 대체합니다. 작성 중인 수기 입력은 먼저 수정안에 추가하세요.</p>
      <div className={styles.actions}><button disabled={Boolean(busy) || !session} onClick={() => void preserveWork()}>원본과 작업 보관</button><button disabled={Boolean(busy)} onClick={() => void resumeWork()}>보관 작업 이어서 하기</button><button disabled={Boolean(busy)} onClick={() => {
        if (window.confirm('이 대상의 브라우저 보관 원본과 작업을 삭제할까요? DB와 수정 이력은 유지됩니다.')) void removeReviewDraft(target.key).then(() => setDraftNotice('이 대상의 보관 작업을 삭제했습니다.')).catch(cause => setError(cause instanceof Error ? cause.message : '보관 작업을 삭제하지 못했습니다.'))
      }}>이 대상 보관 작업 삭제</button></div></details>
    </div></details>
    {draftNotice ? <p className={styles.draftStatus} role="status">{draftNotice}</p> : null}
    {busy ? <div role="status"><p>{busy}</p>{busy.startsWith('페이지 추출') || busy.includes('OCR') ? <button type="button" onClick={() => {
      request.current?.abort(); setBusy(''); setError('추출을 취소했습니다. 필요한 쪽수를 다시 선택할 수 있습니다.')
    }}>추출 취소</button> : null}</div> : null}
    {preview && previewUrl ? <div className={styles.preview}><Suspense fallback={<p role="status">원문 준비 중…</p>}><PdfPreview url={previewUrl} name={session?.fileName ?? '공식 PDF'} /></Suspense></div> : null}
    {extracted ? <div className={styles.reviewLayout} hidden={phase === 'source'}>
      <section className={`${styles.card} ${styles.reference}`}><header className={styles.cardHeading}><div><span className={styles.eyebrow}>공식 자료</span><h3>추출한 근거</h3></div></header>
        <p>선택한 {extracted.pages.length}쪽 · 텍스트 {extracted.pages.reduce((sum, page) => sum + page.text.length, 0).toLocaleString()}자</p>
        {extracted.pages.map((page, index) => <details key={page.page} open={index === 0}><summary>{page.page}쪽 {page.requiresOcr ? '· OCR 필요' : '· 추출 텍스트'}</summary>
          {page.requiresOcr ? <p className={styles.warning}>읽을 수 있는 텍스트가 없습니다. 아래 OCR 처리로 읽거나 원문을 직접 확인하세요.</p>
            : <>{page.method === 'ocr' ? <p className={styles.warning}>OCR 추정 텍스트 · 인식 점수 {Math.round(page.confidence ?? 0)}. 오인식이 있을 수 있으므로 숫자·단위·각주를 반드시 원문과 대조하세요.</p> : null}<pre className={styles.text}>{page.text}</pre></>}</details>)}
        {extracted.pages.some(page => page.requiresOcr) && session?.ocr ? <button disabled={Boolean(busy)} onClick={() => void runOcr()}>텍스트 없는 선택 페이지 OCR · 최대 5쪽</button> : null}
        <p className={styles.hint}>표의 열·단위·각주는 원문과 함께 확인하세요. 파일이 바뀌면 새로 선택해 다시 추출합니다.</p>
        <button type="button" onClick={() => setPreview(value => !value)} aria-expanded={preview}>{preview ? '원문 접기' : 'PDF 원문 보기'}</button>
      </section>
      <section className={`${styles.card} ${styles.draftEditor}`} hidden={phase !== 'draft'}><header className={styles.cardHeading}><div><span className={styles.eyebrow}>수정안 작성</span><h3>수정안을 작성하세요</h3><p>왼쪽 자료에서 확인한 값과 근거를 입력하세요.</p></div></header>
        <div className={styles.modeSwitch} aria-label="수정안 작성 방식"><button type="button" aria-pressed={method === 'manual'} disabled={workspaceBusy || conflict || detail.summary.deleted} onClick={() => setMethod('manual')}>직접 입력</button><button type="button" aria-pressed={method === 'agent'} disabled={workspaceBusy || conflict || detail.summary.deleted} onClick={() => setMethod('agent')}>에이전트 결과 가져오기</button></div>
        <div hidden={method !== 'agent'} className={styles.agentTools}><h4>에이전트에게 자료 전달</h4><p className={styles.hint}>자료 파일을 전달하거나 로컬 폴더로 연결한 뒤 결과를 가져오세요.</p><div className={styles.actions}>
        <Button data-design-system disabled={Boolean(busy) || !safeDocumentUrl(sourceUrl)} onClick={() => {
          try { downloadText(agentPacket(target, extracted, sourceUrl), `document-review-${detail.summary.id}.json`) }
          catch (cause) { setError(cause instanceof Error ? cause.message : '자료를 만들지 못했습니다.') }
        }}>에이전트 자료 다운로드</Button>
        <button disabled={Boolean(busy) || !safeDocumentUrl(sourceUrl)} onClick={() => {
          if (!navigator.clipboard) { setError('클립보드를 사용할 수 없습니다. 에이전트 자료 다운로드를 이용하세요.'); return }
          void navigator.clipboard.writeText(agentPacket(target, extracted, sourceUrl)).then(() => setDraftNotice('에이전트 요청을 복사했습니다. 사용하는 에이전트에 붙여넣으세요.')).catch(() => setError('복사 권한이 없습니다. 자료 다운로드를 이용하세요.'))
        }}>에이전트 요청 복사</button></div>
        <p className={styles.hint}>다운로드한 파일을 사용하는 에이전트에 전달하세요. 현재값·허용 항목·페이지 근거·응답 형식이 포함됩니다.</p>
        <FolderExchangePanel key={`${target.key}:${target.version}:${extracted.sha256}`} target={target} document={extracted} sourceUrl={sourceUrl}
          disabled={Boolean(busy) || sharedBusy || conflict || detail.summary.deleted || !safeDocumentUrl(sourceUrl)} onBusyChange={setExchangeBusy}
          onResult={importAgentResult} /></div>
        {method === 'manual' ? <PdfTablePanel key={`${target.key}:${extracted.sha256}:${pages}`} target={target} document={extracted} disabled={workspaceBusy || conflict || detail.summary.deleted} onAdded={addProposal} /> : null}
        {method === 'agent' ? <fieldset className={styles.inputs} disabled={workspaceBusy || conflict || detail.summary.deleted}>
          <legend className={styles.srOnly}>에이전트 결과</legend>
          <p className={styles.hint}>에이전트 자료를 전달한 뒤, 받은 결과 파일을 선택하거나 아래에 붙여넣으세요.</p>
          <label className={styles.upload}>에이전트 결과 JSON 파일<input type="file" accept=".json,application/json" onChange={event => {
            const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; if (file) void resultFile(file)
          }} /></label>
          <label>에이전트 결과 JSON<textarea rows={4} value={json} spellCheck={false} onChange={event => {
            if (review && json.trim()) setResultBackup({ context: resultContext, json, note })
            setJson(event.target.value); setReview(null); setChecked([]); setError('')
          }} /></label>
          <button type="button" disabled={!json.trim()} onClick={() => validate()}>결과 검증</button>
        </fieldset> : null}
        <div hidden={method !== 'manual'}><ManualProposal key={target.key} target={target} document={extracted} onDirtyChange={setManualDirty} disabled={workspaceBusy || conflict || detail.summary.deleted} onAdded={addProposal} /></div>
      </section>
      {phase === 'confirm' && incomingReview && review ? <IncomingReviewPanel current={review} incoming={incomingReview} target={target} onApply={applyIncoming} onCancel={() => { setIncomingReview(null); setPhase('draft') }} /> : null}
      {review ? <section className={`${styles.card} ${styles.review}`} hidden={phase !== 'confirm' || Boolean(incomingReview)} aria-label="수정안 검토"><header className={styles.cardHeading}><div><span className={styles.eyebrow}>최종 확인</span><h3>원문과 대조 후 선택 반영</h3></div><span className={styles.badge}>{checked.length}/{review.proposals.length}개 확인</span></header><button type="button" disabled={workspaceBusy} onClick={() => setPhase('draft')}>수정안 계속 작성</button>
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
        <div className={`${styles.actions} ${styles.saveActions}`}><p className={styles.hint}>{!checked.length ? '원문에서 확인한 항목을 선택하세요.' : !note.trim() ? '확인 메모를 작성하면 저장할 수 있습니다.' : '선택한 항목과 PDF 근거가 수정 이력에 저장됩니다.'}</p><Button data-design-system disabled={!checked.length || !note.trim() || !safeDocumentUrl(sourceUrl) || workspaceBusy || conflict || detail.summary.deleted}
          onClick={() => void save()}>확인한 {checked.length}개 항목 반영</Button>
          <button type="button" disabled={Boolean(busy)} onClick={() => downloadText(json, `document-review-result-${detail.summary.id}.json`)}>검토 결과 다운로드</button></div>
      </section> : null}
    </div> : null}
    {resultBackup?.context === resultContext ? <div className={`${styles.callout} ${styles.resultBackup}`}><p>가져오기 전 수정안·메모가 이 화면에 보관되어 있습니다. 새로고침하면 사라지므로 필요한 작업은 별도로 보관하세요.</p><button type="button" disabled={workspaceBusy} onClick={restorePreviousResult}>가져오기 전 입력 복원</button></div> : null}
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
