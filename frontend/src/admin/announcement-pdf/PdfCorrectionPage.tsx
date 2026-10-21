import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { getApiBaseUrl } from '../../api/apiBaseUrl'
import { Button } from '../../design-system/components/Button'
import { attachmentErrorMessage, loadAnnouncementAttachment } from '../../public-housing/api/announcementAttachments'
import type { PdfFocus } from '../../public-housing/components/PdfDocumentPreview'
import { applyJsonUpdate, loadJsonWorkspace, previewJsonUpdate, type JsonPreview, type JsonUpdateInput, type JsonWorkspace } from '../announcement-json/api'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
import { ManagementError } from '../management/api'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { readExtraction, searchText, type Evidence } from './extraction'
import type { HousingCandidates } from './matching'
import { applicableCells, assembleReview, createReview, rebaseReview, remapSchedule, shown, type Review } from './review'
import { ReviewCells } from './ReviewCells'
import { ReviewSupply } from './ReviewSupply'
import { PreviewChanges } from './PreviewChanges'
import prompt from './announcementUpdatePrompt.v3.txt?raw'
import styles from './PdfCorrection.module.css'

const PdfDocumentPreview = lazy(() => import('../../public-housing/components/PdfDocumentPreview'))
type Phase = 'prepare' | 'review' | 'preview' | 'result'
type Section = 'supply' | 'schedule' | 'reception'
function validEvidenceUrl(value: string) {
  try { return ['http:', 'https:'].includes(new URL(value).protocol) && !/\s/.test(value) } catch { return false }
}
async function candidates(workspace: JsonWorkspace, signal?: AbortSignal): Promise<HousingCandidates> {
  const ids = [...new Set(workspace.supply.rows.map(row => row.housingComplexId).filter(id => id !== null))]
  return Object.fromEntries(await Promise.all(ids.map(async id => [id, await getMatchingHousingTypes(id, signal)])))
}
export function PdfCorrectionPage() {
  const { id } = useParams()
  return <PdfCorrectionWorkspace key={id} />
}

function PdfCorrectionWorkspace() {
  const { id = '' } = useParams(), [params] = useSearchParams()
  const [workspace, setWorkspace] = useState<JsonWorkspace | null>(null), [housing, setHousing] = useState<HousingCandidates>({})
  const [phase, setPhase] = useState<Phase>('prepare'), [section, setSection] = useState<Section>('supply')
  const [text, setText] = useState(''), [review, setReview] = useState<Review | null>(null)
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [busy, setBusy] = useState(false), [attempt, setAttempt] = useState(0)
  const [blocked, setBlocked] = useState(false), [uncertain, setUncertain] = useState(false)
  const [attachmentId, setAttachmentId] = useState(''), [file, setFile] = useState<File | null>(null)
  const [pdf, setPdf] = useState<{ url: string; name: string } | null>(null), [pdfError, setPdfError] = useState('')
  const [focus, setFocus] = useState<PdfFocus | null>(null), [mobileView, setMobileView] = useState<'pdf' | 'review'>('review')
  const [reason, setReason] = useState(''), [sourceUrl, setSourceUrl] = useState(''), [sourcePage, setSourcePage] = useState('')
  const [preview, setPreview] = useState<{ input: JsonUpdateInput; result: JsonPreview } | null>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  const reviewPane = useRef<HTMLDivElement>(null)
  const reasonProblem = !reason.trim() ? '보정 이유를 입력하세요.' : !validEvidenceUrl(sourceUrl.trim()) ? '공식 근거 URL에 올바른 http 또는 https 주소를 입력하세요.' : ''
  const parsed = useMemo(() => readExtraction(text), [text])
  const backParams = new URLSearchParams(params); backParams.delete('failureCount')
  const supplyParams = new URLSearchParams(backParams); supplyParams.set('section', 'relations')
  const back = `/admin/announcements/${id}${backParams.size ? `?${backParams}` : ''}`
  const rawFailureReturn = params.get('failureReturnTo'), failureReturn = rawFailureReturn && /^\/admin\/failures(?:\?|$)/.test(rawFailureReturn) ? rawFailureReturn : null
  const failureCount = Number(params.get('failureCount'))
  const active = phase === 'review' || phase === 'preview'
  useUnsavedChanges(phase !== 'result' && Boolean(text.trim() || uncertain))
  useEffect(() => {
    const controller = new AbortController(); setWorkspace(null); setError('')
    if (!/^[1-9]\d*$/.test(id)) { setError('올바른 공고 주소가 아닙니다.'); return }
    void loadJsonWorkspace(id, controller.signal).then(async value => {
      const next = await candidates(value, controller.signal)
      if (!controller.signal.aborted) { setWorkspace(value); setHousing(next); setSourceUrl(String(value.detail.data.originalUrl ?? '')); setAttachmentId(String(value.detail.attachments?.find(item => /\.pdf(?:$|[?\s])/i.test(item.fileName))?.attachmentId ?? '')) }
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '공고를 불러오지 못했습니다.') })
    return () => controller.abort()
  }, [id, attempt])
  useEffect(() => {
    const controller = new AbortController(); let objectUrl: string | undefined
    setPdf(null); setPdfError(''); setFocus(null)
    if (!file && !attachmentId) return
    const attachment = workspace?.detail.attachments?.find(item => String(item.attachmentId) === attachmentId)
    const pending = file ? Promise.resolve(file) : loadAnnouncementAttachment(id, attachmentId, false, controller.signal)
    void pending.then(blob => {
      if (controller.signal.aborted) return
      objectUrl = URL.createObjectURL(blob); setPdf({ url: objectUrl, name: file?.name ?? attachment?.fileName ?? '공고문 PDF' })
    }).catch(cause => { if (!controller.signal.aborted) setPdfError(attachmentErrorMessage(cause)) })
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl) }
  }, [id, attachmentId, file, workspace?.detail.attachments])
  useEffect(() => {
    if (phase !== 'preview') heading.current?.focus()
    if (reviewPane.current) reviewPane.current.scrollTop = 0
  }, [phase])
  function evidence(item: Evidence) { setFocus(previous => ({ page: item.page, query: searchText(item.quote), nonce: (previous?.nonce ?? 0) + 1 })); setMobileView('pdf') }
  function change(next: Review) { setReview(next); setPreview(null); setError('') }
  function start() {
    if (!parsed.ok || !workspace || !pdf) return
    const next = createReview(parsed.value, workspace, housing)
    setReview(next); setReason(`${pdf.name} · 프롬프트 v3로 추출 후 PDF와 대조`)
    setSourcePage([...new Set(parsed.value.evidence.map(item => item.page))].sort((a, b) => a - b).join(', ').slice(0, 100))
    setPhase('review'); setError(''); setNotice('')
  }
  async function inspect() {
    if (!review || !workspace || blocked) return
    setBusy(true); setError('')
    try { const input = assembleReview(review, workspace, { reason: reason.trim(), sourceUrl: sourceUrl.trim(), sourcePage: sourcePage.trim() || null }); setPreview({ input, result: await previewJsonUpdate(id, input) }); setMobileView('review'); setPhase('preview') }
    catch (cause) { setError(cause instanceof Error ? cause.message : '변경 미리보기를 만들지 못했습니다.'); if (cause instanceof ManagementError && cause.status === 409) setBlocked(true) }
    finally { setBusy(false) }
  }
  async function apply() {
    if (!preview || blocked || busy) return
    setBusy(true); setError('')
    try { const latest = await applyJsonUpdate(id, preview.input, preview.result.confirmationToken); setWorkspace(latest); setPhase('result'); setText(''); setBlocked(false); setUncertain(false) }
    catch (cause) {
      const rejected = cause instanceof ManagementError && cause.status >= 400 && cause.status < 500
      setUncertain(!rejected); setBlocked(!rejected || cause instanceof ManagementError && cause.status === 409)
      setError(rejected ? cause.message : '저장 응답을 받지 못했습니다. 최신 저장값을 조회해 적용 여부를 확인한 뒤 다시 비교하세요.')
      setPreview(null); setPhase('review')
    } finally { setBusy(false) }
  }
  async function reload() {
    setBusy(true); setError('')
    try {
      const latest = await loadJsonWorkspace(id), nextHousing = await candidates(latest)
      if (review) setReview(rebaseReview(review, latest, nextHousing))
      setWorkspace(latest); setHousing(nextHousing); setPreview(null); setBlocked(false); setUncertain(false); setPhase('review')
      setNotice('최신 저장값을 불러왔습니다. 수동 입력은 유지했습니다. 달라진 행 대응과 선택 항목을 다시 대조하세요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '최신값을 불러오지 못했습니다.') }
    finally { setBusy(false) }
  }
  if (!workspace) return <section className={styles.page}><h1>PDF로 공고 보정</h1>{error ? <div role="alert"><p>{error}</p><Button onClick={() => setAttempt(value => value + 1)}>다시 불러오기</Button></div> : <p role="status">공고와 연결 주택형을 불러오는 중…</p>}</section>
  if (workspace.detail.summary.deleted) return <section className={styles.page}><h1>휴지통의 공고입니다.</h1><p>공고를 복구한 뒤 보정하세요.</p><Link to={back}>공고 상세로 돌아가기</Link></section>
  const pdfPane = <section className={styles.pdfPane} aria-label="PDF 근거" hidden={active && mobileView !== 'pdf'} data-pane="pdf"><header><strong>{pdf?.name ?? '공고문 PDF'}</strong>{focus && <span>{focus.page}쪽 근거</span>}</header>{pdf ? <Suspense fallback={<p role="status">PDF 뷰어를 불러오는 중…</p>}><PdfDocumentPreview url={pdf.url} name={pdf.name} focus={focus} /></Suspense> : <p role={pdfError ? 'alert' : 'status'}>{pdfError || 'PDF를 선택하세요.'}</p>}</section>
  return <section className={styles.page} data-focused={active}>
    <header className={styles.heading}><div><Link to={back}>공고 상세로 돌아가기</Link><h1 ref={heading} tabIndex={-1}>PDF로 공고 보정</h1><p>#{id} · {workspace.detail.summary.name}</p></div><ol aria-label="보정 진행"><li aria-current={phase === 'prepare' ? 'step' : undefined}>요청 준비</li><li aria-current={phase === 'review' ? 'step' : undefined}>비교·보정</li><li aria-current={phase === 'preview' ? 'step' : undefined}>변경 미리보기</li><li aria-current={phase === 'result' ? 'step' : undefined}>적용 결과</li></ol></header>
    {error && <div role="alert" className={styles.error}><p>{error}</p>{blocked && <Button data-design-system onClick={() => void reload()} disabled={busy}>입력을 유지하고 최신값 비교</Button>}</div>}
    {notice && <p role="status" className={styles.notice}>{notice}</p>}
    {phase === 'prepare' && <div className={styles.prepare}>
      <section><h2>공고문과 요청 준비</h2><p>PDF와 고정 프롬프트를 ChatGPT에 직접 전달한 뒤 받은 응답을 붙여넣으세요.</p>
        <label>등록된 첨부파일<select value={file ? '' : attachmentId} onChange={event => { setFile(null); setAttachmentId(event.target.value) }}><option value="">파일을 선택하세요</option>{workspace.detail.attachments?.filter(item => /\.pdf(?:$|[?\s])/i.test(item.fileName)).map(item => <option key={item.attachmentId} value={item.attachmentId}>{item.fileName}</option>)}</select></label>
        <label>내 컴퓨터에서 PDF 선택<input type="file" accept="application/pdf,.pdf" onChange={event => { const selected = event.target.files?.[0]; if (!selected) return; if (!/\.pdf$/i.test(selected.name)) { setPdfError('PDF 파일을 선택하세요.'); return } setFile(selected); setAttachmentId('') }} /></label>
        <p className={styles.hint}>선택한 파일은 이 브라우저에서만 열며 서버로 업로드하지 않습니다.</p>
        {pdf && <p><strong>{pdf.name}</strong> · <a href={file ? pdf.url : `${getApiBaseUrl()}/api/v1/announcements/${id}/attachments/${attachmentId}/content?download=true`} download={file ? pdf.name : undefined} target={file ? undefined : '_blank'} rel="noreferrer">PDF 다운로드</a></p>}
        {pdfError && <p role="alert">{pdfError}</p>}
        <div className={styles.actions}><Button data-design-system onClick={() => void navigator.clipboard.writeText(prompt).then(() => setNotice('프롬프트 v3를 복사했습니다. PDF와 함께 ChatGPT에 붙여넣으세요.')).catch(() => setError('복사하지 못했습니다. 프롬프트 내용을 펼쳐 직접 복사하세요.'))}>프롬프트 v3 복사</Button><a href="https://chatgpt.com/" target="_blank" rel="noreferrer">ChatGPT 열기</a></div>
        <details><summary>프롬프트 내용</summary><pre className={styles.prompt}>{prompt}</pre></details>
      </section>
      <section><h2>ChatGPT 응답 붙여넣기</h2><label>추출 응답<textarea rows={14} value={text} spellCheck={false} placeholder="JSON 응답 전체를 붙여넣으세요." onChange={event => setText(event.target.value)} /></label>
        {text.trim() && <div role={parsed.ok ? 'status' : 'alert'} className={parsed.ok ? styles.notice : styles.error}>{parsed.ok ? <p>공급행 {parsed.value.supplyRows.length}개 · 대상 {parsed.value.supplyRows.reduce((count, row) => count + row.targets.length, 0)}개 · 일정 {parsed.value.schedules.length}개 · 미확인 {parsed.value.unresolved.length}개 · 해당 없음 {parsed.value.notApplicable.length}개</p> : <p>{parsed.error}</p>}{parsed.repairs.length > 0 && <details open><summary>형식 복구 {parsed.repairs.length}건</summary><ul>{parsed.repairs.map(item => <li key={item}>{item}</li>)}</ul></details>}</div>}
        <Button data-design-system disabled={!parsed.ok || !pdf} onClick={start}>응답 검토하기</Button>{!pdf && <p className={styles.hint}>대조할 PDF를 먼저 선택하세요.</p>}
      </section>
    </div>}
    {active && review && <><div className={styles.mobileSwitch}><Button data-design-system aria-pressed={mobileView === 'pdf'} onClick={() => setMobileView('pdf')}>PDF 보기</Button><Button data-design-system aria-pressed={mobileView === 'review'} onClick={() => setMobileView('review')}>검토표 보기</Button></div><div className={styles.workbench}>{pdfPane}
      <div ref={reviewPane} className={styles.reviewPane} hidden={mobileView !== 'review'} data-pane="review">
        {phase === 'review' ? <><nav className={styles.sections} aria-label="PDF 보정 영역">{([['supply', '공급정보'], ['schedule', '일정'], ['reception', '접수처·기본정보']] as const).map(([key, label]) => <Button data-design-system key={key} aria-pressed={section === key} onClick={() => setSection(key)}>{label}</Button>)}</nav>
        <fieldset disabled={busy || blocked} className={styles.reviewFields}>
          {section === 'supply' && <ReviewSupply review={review} workspace={workspace} housing={housing} onChange={change} onEvidence={evidence} />}
          {section === 'schedule' && <><h2>일정</h2><p>기존 단지별 일정은 각 ID를 유지하며 같은 보정값으로 갱신합니다.</p>{review.schedules.map((schedule, index) => <section className={styles.supplyBlock} key={index}><header><h3>{shown(schedule.cells.find(cell => cell.field === 'name')?.value)}</h3><label><input type="checkbox" checked={schedule.include} onChange={event => change({ ...review, schedules: review.schedules.map((item, at) => at === index ? { ...item, include: event.target.checked } : item) })} />일정 {index + 1} 적용</label></header>
            <p>{schedule.proposal.reason || (schedule.proposal.existingIds.length ? '기존 일정 갱신' : '새 일정 추가')}{schedule.proposal.existingIds.map(existingId => { const event = workspace.events.find(item => item.id === existingId); return <small className={styles.scheduleBefore} key={existingId}>#{existingId} · {event?.complexName ?? '공고 전체'} · {shown(event?.data.name)} · {shown(event?.data.startAt)} ~ {shown(event?.data.endAt)}</small> })}</p>
            {schedule.include && <><fieldset><legend>일정 {index + 1} 대응</legend>{workspace.events.filter(event => event.data.scheduleType === schedule.cells.find(cell => cell.field === 'scheduleType')?.value).map(event => <label key={event.id}><input type="checkbox" checked={schedule.proposal.existingIds.includes(event.id)} onChange={changeEvent => change({ ...review, schedules: review.schedules.map((item, at) => at === index ? remapSchedule(review, index, changeEvent.target.checked ? [...item.proposal.existingIds, event.id] : item.proposal.existingIds.filter(value => value !== event.id), workspace) : item) })} />#{event.id} · {event.complexName ?? '공고 전체'} · {shown(event.data.name)}</label>)}<p className={styles.hint}>선택한 기존 일정이 없으면 새 일정으로 추가합니다.</p></fieldset>
              <ReviewCells cells={schedule.cells} scope={`일정 ${index + 1}`} onChange={cells => change({ ...review, schedules: review.schedules.map((item, at) => at === index ? { ...item, cells, confirmed: false } : item) })} onEvidence={evidence} />
              <label className={styles.confirm}><input type="checkbox" checked={schedule.confirmed} onChange={event => change({ ...review, schedules: review.schedules.map((item, at) => at === index ? { ...item, confirmed: event.target.checked } : item) })} />일정 {index + 1}의 대응과 날짜·시각 근거를 확인함</label></>}
          </section>)}</>}
          {section === 'reception' && <><h2>접수처·기본정보</h2><p>임대유형·모집유형·기관·공고명은 현재값을 유지합니다. 접수처는 한 곳만 저장할 수 있으므로 온라인·현장 병행 여부를 확인하세요.</p><h3>접수처</h3><ReviewCells cells={review.reception} scope="접수처" onChange={reception => change({ ...review, reception })} onEvidence={evidence} /><h3>공고 기본 일정</h3><ReviewCells cells={review.metadata} scope="기본정보" onChange={metadata => change({ ...review, metadata })} onEvidence={evidence} /></>}
        </fieldset></> : preview && <PreviewChanges preview={preview.result} input={preview.input} review={review} />}
      </div></div>
      <footer className={styles.footer}><div><strong>{applicableCells(review).filter(cell => cell.selected && !cell.locked).length}개 값 선택</strong><span>미확인·제외 항목은 현재값을 유지합니다.</span></div><div className={styles.actions}>{phase === 'preview' ? <><Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => setPhase('review')}>검토로 돌아가기</Button><Button data-design-system disabled={busy || blocked} onClick={() => void apply()}>{busy ? '적용 중…' : '확인한 변경 적용'}</Button></> : <><Button data-design-system className={styles.secondaryButton} disabled={busy || blocked} onClick={() => { if (window.confirm('검토 입력을 초기화하고 응답 준비로 돌아갈까요?')) { setPhase('prepare'); setReview(null) } }}>응답 다시 준비</Button><Button data-design-system disabled={busy || blocked || Boolean(reasonProblem)} onClick={() => void inspect()}>{busy ? '미리보기 준비 중…' : '변경 미리보기'}</Button></>}</div></footer>
      {phase === 'review' && <details className={styles.reason} open={reasonProblem ? true : undefined}><summary>적용 근거 확인{reasonProblem ? ' · 필수 입력 필요' : ''}</summary>{reasonProblem && <p role="status" className={styles.error}>{reasonProblem}</p>}<label>보정 이유<textarea value={reason} maxLength={2000} disabled={busy || blocked} onChange={event => { setReason(event.target.value); setPreview(null) }} /></label><label>공식 근거 URL<input value={sourceUrl} maxLength={2048} disabled={busy || blocked} onChange={event => { setSourceUrl(event.target.value); setPreview(null) }} /></label><label>근거 쪽<input value={sourcePage} maxLength={100} disabled={busy || blocked} onChange={event => { setSourcePage(event.target.value); setPreview(null) }} /></label></details>}
    </>}
    {phase === 'result' && <section className={styles.result}><h2>공고 보정을 적용했습니다.</h2><p>선택한 변경을 저장했습니다. 공개 화면에서 공고와 단지의 현재 공급정보를 확인하세요.</p><p>보정 저장은 공고 전체 검토 완료와 다릅니다.{failureReturn ? ` ${Number.isSafeInteger(failureCount) && failureCount > 0 ? `이 공고의 오류 ${failureCount}건은` : '연결된 오류는'} 다음 정제 때 다시 판정됩니다.` : ''}</p><div className={styles.actions}><Link to={`/?announcementId=${id}`} target="_blank" rel="noreferrer">공개 공고 확인</Link><Link to={`/admin/announcements/${id}?${supplyParams}`}>저장된 공급정보 확인</Link>{failureReturn && <Link to={failureReturn}>실패 검토로 돌아가기</Link>}</div></section>}
  </section>
}
