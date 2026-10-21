import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { attachmentErrorMessage, loadAnnouncementAttachment } from '../../public-housing/api/announcementAttachments'
import type { PdfFocus } from '../../public-housing/components/PdfDocumentPreview'
import { applyJsonUpdate, loadJsonWorkspace, previewJsonUpdate, type JsonWorkspace } from '../announcement-json/api'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
import type { CorrectionRow, SupplyCorrectionInput } from '../supply/supplyCorrectionApi'
import { ManagementError } from '../management/api'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { readExtraction, type Evidence } from './extraction'
import { checkAnnouncement, type HousingCandidates } from './matching'
import { applicableCells, assembleReview, createReview, rebaseReview, remapSchedule, shown, type Review } from './review'
import { ReviewCells } from './ReviewCells'
import { ReviewSupply } from './ReviewSupply'
import { RequestPreparation } from './RequestPreparation'
import styles from './PdfCorrection.module.css'

const PdfDocumentPreview = lazy(() => import('../../public-housing/components/PdfDocumentPreview'))
type Phase = 'prepare' | 'review' | 'result'
type Section = 'supply' | 'schedule' | 'reception'
function validEvidenceUrl(value: string) {
  try { return ['http:', 'https:'].includes(new URL(value).protocol) && !/\s/.test(value) } catch { return false }
}
async function candidates(workspace: JsonWorkspace, signal?: AbortSignal): Promise<HousingCandidates> {
  const ids = [...new Set(workspace.supply.rows.map(row => row.housingComplexId).filter(id => id !== null))]
  return Object.fromEntries(await Promise.all(ids.map(async id => [id, await getMatchingHousingTypes(id, signal)])))
}
export function PdfCorrectionPage() {
  const { id = '' } = useParams()
  return <PdfCorrectionWorkspace key={id} id={id} />
}

export function PdfCorrectionWorkspace({ id, supplyDraft }: { id: string; supplyDraft?: {
  rows: CorrectionRow[]; onFill: (input: SupplyCorrectionInput) => void; onCancel: () => void
} }) {
  const [params] = useSearchParams()
  // The draft is the baseline for PDF matching, including unsaved manual rows.
  const [initialRows] = useState(supplyDraft?.rows)
  const [workspace, setWorkspace] = useState<JsonWorkspace | null>(null), [housing, setHousing] = useState<HousingCandidates>({})
  const [phase, setPhase] = useState<Phase>('prepare'), [section, setSection] = useState<Section>('supply')
  const [text, setText] = useState(''), [review, setReview] = useState<Review | null>(null)
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [busy, setBusy] = useState(false), [attempt, setAttempt] = useState(0)
  const [blocked, setBlocked] = useState(false), [uncertain, setUncertain] = useState(false)
  const [attachmentId, setAttachmentId] = useState('')
  const [pdf, setPdf] = useState<{ url: string; name: string } | null>(null), [pdfError, setPdfError] = useState('')
  const [focus, setFocus] = useState<PdfFocus | null>(null), [mobileView, setMobileView] = useState<'pdf' | 'review'>('review')
  const [reason, setReason] = useState(''), [sourceUrl, setSourceUrl] = useState(''), [sourcePage, setSourcePage] = useState('')
  const heading = useRef<HTMLHeadingElement>(null)
  const reviewPane = useRef<HTMLDivElement>(null)
  const reasonProblem = !reason.trim() ? '보정 이유를 입력하세요.' : !validEvidenceUrl(sourceUrl.trim()) ? '공식 근거 URL에 올바른 http 또는 https 주소를 입력하세요.' : ''
  const parsed = useMemo(() => readExtraction(text), [text])
  const checks = useMemo(() => parsed.ok && workspace ? checkAnnouncement(parsed.value, workspace.detail.data) : [], [parsed, workspace])
  const backParams = new URLSearchParams(params); backParams.delete('failureCount')
  const supplyParams = new URLSearchParams(backParams); supplyParams.set('section', 'relations')
  const back = `/admin/announcements/${id}${backParams.size ? `?${backParams}` : ''}`
  const rawFailureReturn = params.get('failureReturnTo'), failureReturn = rawFailureReturn && /^\/admin\/failures(?:\?|$)/.test(rawFailureReturn) ? rawFailureReturn : null
  const failureCount = Number(params.get('failureCount'))
  const active = phase === 'review'
  useUnsavedChanges(phase !== 'result' && Boolean(text.trim() || uncertain))
  useEffect(() => {
    const controller = new AbortController(); setWorkspace(null); setError('')
    if (!/^[1-9]\d*$/.test(id)) { setError('올바른 공고 주소가 아닙니다.'); return }
    void loadJsonWorkspace(id, controller.signal).then(async value => {
      if (initialRows) value = { ...value, supply: { ...value.supply, rows: initialRows } }
      const next = await candidates(value, controller.signal)
      if (!controller.signal.aborted) { setWorkspace(value); setHousing(next); setSourceUrl(String(value.detail.data.originalUrl ?? '')); setAttachmentId(String(value.detail.attachments?.find(item => /\.pdf(?:$|[?\s])/i.test(item.fileName))?.attachmentId ?? '')) }
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '공고를 불러오지 못했습니다.') })
    return () => controller.abort()
  }, [id, attempt, initialRows])
  useEffect(() => {
    const controller = new AbortController(); let objectUrl: string | undefined
    setPdf(null); setPdfError(''); setFocus(null)
    if (!attachmentId) return
    const attachment = workspace?.detail.attachments?.find(item => String(item.attachmentId) === attachmentId)
    const pending = loadAnnouncementAttachment(id, attachmentId, false, controller.signal)
    void pending.then(blob => {
      if (controller.signal.aborted) return
      objectUrl = URL.createObjectURL(blob); setPdf({ url: objectUrl, name: attachment?.fileName ?? '공고문 PDF' })
    }).catch(cause => { if (!controller.signal.aborted) setPdfError(attachmentErrorMessage(cause)) })
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl) }
  }, [id, attachmentId, workspace?.detail.attachments])
  useEffect(() => {
    heading.current?.focus()
    if (reviewPane.current) reviewPane.current.scrollTop = 0
  }, [phase])
  function evidence(item: Evidence) { setFocus(previous => ({ page: item.page, query: '', nonce: (previous?.nonce ?? 0) + 1 })); setMobileView('pdf') }
  function change(next: Review) { setReview(next); setError('') }
  function start() {
    if (!parsed.ok || !workspace || !pdf) return
    const extraction = supplyDraft ? { ...parsed.value, announcement: {}, receptionPlace: {}, schedules: [] } : parsed.value
    const next = createReview(extraction, workspace, housing)
    setReview(next); setReason(`${pdf.name} · 프롬프트 v3로 추출 후 PDF와 대조`)
    setSourcePage([...new Set(parsed.value.evidence.map(item => item.page))].sort((a, b) => a - b).join(', ').slice(0, 100))
    setPhase('review'); setError(''); setNotice('')
  }
  async function apply() {
    if (!review || !workspace || blocked || busy || !supplyDraft && reasonProblem) return
    setBusy(true); setError('')
    let saving = false
    try {
      const input = assembleReview(review, workspace, { reason: reason.trim(), sourceUrl: sourceUrl.trim(), sourcePage: sourcePage.trim() || null })
      if (supplyDraft) { supplyDraft.onFill(input.correction); return }
      const checked = await previewJsonUpdate(id, input)
      saving = true
      const latest = await applyJsonUpdate(id, input, checked.confirmationToken)
      setWorkspace(latest); setPhase('result'); setText(''); setBlocked(false); setUncertain(false)
    } catch (cause) {
      const rejected = cause instanceof ManagementError && cause.status >= 400 && cause.status < 500
      const uncertainSave = saving && !rejected
      setUncertain(uncertainSave); setBlocked(uncertainSave || cause instanceof ManagementError && cause.status === 409)
      setError(uncertainSave ? '저장 응답을 받지 못했습니다. 최신 저장값을 조회해 적용 여부를 확인한 뒤 다시 비교하세요.' : cause instanceof Error ? cause.message : '보정 내용을 적용하지 못했습니다. 다시 시도하세요.')
      setMobileView('review')
    } finally { setBusy(false) }
  }
  async function reload() {
    setBusy(true); setError('')
    try {
      const latest = await loadJsonWorkspace(id), nextHousing = await candidates(latest)
      if (review) setReview(rebaseReview(review, latest, nextHousing))
      setWorkspace(latest); setHousing(nextHousing); setBlocked(false); setUncertain(false); setPhase('review')
      setNotice('최신 저장값을 불러왔습니다. 수동 입력은 유지했습니다. 달라진 기존 값과 선택 항목을 비교한 뒤 다시 적용하세요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '최신값을 불러오지 못했습니다.') }
    finally { setBusy(false) }
  }
  if (!workspace) return <section className={styles.page}><h1>PDF로 공고 보정</h1>{error ? <div role="alert"><p>{error}</p><Button onClick={() => setAttempt(value => value + 1)}>다시 불러오기</Button></div> : <p role="status">공고와 연결 주택형을 불러오는 중…</p>}</section>
  if (workspace.detail.summary.deleted) return <section className={styles.page}><h1>휴지통의 공고입니다.</h1><p>공고를 복구한 뒤 보정하세요.</p><Link to={back}>공고 상세로 돌아가기</Link></section>
  const pdfPane = <section className={styles.pdfPane} aria-label="PDF 근거" hidden={active && mobileView !== 'pdf'} data-pane="pdf"><header><strong>{pdf?.name ?? '공고문 PDF'}</strong></header>{pdf ? <Suspense fallback={<p role="status">PDF 뷰어를 불러오는 중…</p>}><PdfDocumentPreview url={pdf.url} name={pdf.name} focus={focus} /></Suspense> : <p role={pdfError ? 'alert' : 'status'}>{pdfError || 'PDF를 선택하세요.'}</p>}</section>
  return <section className={styles.page} data-focused={active} data-embedded={!!supplyDraft}>
    {supplyDraft ? <header className={styles.heading}><div><h2>PDF로 공급정보 채우기</h2><p>PDF와 대조한 공급정보를 편집표에 채웁니다. 저장 전까지 등록값은 유지됩니다.</p></div><Button data-design-system className={styles.secondaryButton} onClick={() => { if (!text.trim() || window.confirm('PDF 검토를 취소하고 편집표로 돌아갈까요?')) supplyDraft.onCancel() }}>편집표로 돌아가기</Button></header> : <header className={styles.heading}><div><Link to={back}>공고 상세로 돌아가기</Link><h1 ref={heading} tabIndex={-1}>PDF로 공고 보정</h1><p>#{id} · {workspace.detail.summary.name}</p></div><ol aria-label="보정 진행"><li aria-current={phase === 'prepare' ? 'step' : undefined}>요청 준비</li><li aria-current={phase === 'review' ? 'step' : undefined}>비교·보정</li><li aria-current={phase === 'result' ? 'step' : undefined}>적용 결과</li></ol></header>}
    {error && <div role="alert" className={styles.error}><p>{error}</p>{blocked && <Button data-design-system onClick={() => void reload()} disabled={busy}>입력을 유지하고 최신값 비교</Button>}</div>}
    {notice && <p role="status" className={styles.notice}>{notice}</p>}
    {phase === 'prepare' && <RequestPreparation attachments={workspace.detail.attachments?.filter(item => /\.pdf(?:$|[?\s])/i.test(item.fileName)) ?? []} attachmentId={attachmentId} onAttachment={value => { if (value !== attachmentId) { setPdf(null); setAttachmentId(value) } }} pdf={pdf} pdfError={pdfError} text={text} onText={setText} parsed={parsed} checks={checks} onReview={start} />}
    {active && review && <><div className={styles.mobileSwitch}><Button data-design-system aria-pressed={mobileView === 'pdf'} onClick={() => setMobileView('pdf')}>PDF 보기</Button><Button data-design-system aria-pressed={mobileView === 'review'} onClick={() => setMobileView('review')}>검토표 보기</Button></div><div className={styles.workbench}>{pdfPane}
      <div ref={reviewPane} className={styles.reviewPane} hidden={mobileView !== 'review'} data-pane="review">
        {checks.some(check => check.matches === false) && <p className={styles.error}>PDF의 {checks.filter(check => check.matches === false).map(check => `${check.label}(${check.pdf})`).join('·')}이 현재 공고와 다릅니다. 이 공고의 PDF로 받은 응답인지 확인하세요.</p>}
        {!supplyDraft && <nav className={styles.sections} aria-label="PDF 보정 영역">{([['supply', '공급정보'], ['schedule', '일정'], ['reception', '접수처·기본정보']] as const).map(([key, label]) => <Button data-design-system key={key} aria-pressed={section === key} onClick={() => setSection(key)}>{label}</Button>)}</nav>}
        <fieldset disabled={busy || blocked} className={styles.reviewFields}>
          {section === 'supply' && <ReviewSupply review={review} workspace={workspace} housing={housing} onChange={change} onEvidence={evidence} />}
          {section === 'schedule' && <><h2>일정</h2><p>기존 단지별 일정은 각 ID를 유지하며 같은 보정값으로 갱신합니다.</p>{review.schedules.map((schedule, index) => <section className={styles.supplyBlock} key={index}><header><h3>{shown(schedule.cells.find(cell => cell.field === 'name')?.value)}</h3><label><input type="checkbox" checked={schedule.include} onChange={event => change({ ...review, schedules: review.schedules.map((item, at) => at === index ? { ...item, include: event.target.checked } : item) })} />일정 {index + 1} 적용</label></header>
            <p>{schedule.proposal.reason || (schedule.proposal.existingIds.length ? '기존 일정 갱신' : '새 일정 추가')}{schedule.proposal.existingIds.map(existingId => { const event = workspace.events.find(item => item.id === existingId); return <small className={styles.scheduleBefore} key={existingId}>#{existingId} · {event?.complexName ?? '공고 전체'} · {shown(event?.data.name)} · {shown(event?.data.startAt)} ~ {shown(event?.data.endAt)}</small> })}</p>
            {schedule.include && <><fieldset><legend>일정 {index + 1}에서 수정할 기존 일정</legend>{workspace.events.filter(event => event.data.scheduleType === schedule.cells.find(cell => cell.field === 'scheduleType')?.value).map(event => <label key={event.id}><input type="checkbox" checked={schedule.proposal.existingIds.includes(event.id)} onChange={changeEvent => change({ ...review, schedules: review.schedules.map((item, at) => at === index ? remapSchedule(review, index, changeEvent.target.checked ? [...item.proposal.existingIds, event.id] : item.proposal.existingIds.filter(value => value !== event.id), workspace) : item) })} />#{event.id} · {event.complexName ?? '공고 전체'} · {shown(event.data.name)}</label>)}<p className={styles.hint}>선택한 기존 일정이 없으면 새 일정으로 추가합니다.</p></fieldset>
              <ReviewCells cells={schedule.cells} scope={`일정 ${index + 1}`} onChange={cells => change({ ...review, schedules: review.schedules.map((item, at) => at === index ? { ...item, cells } : item) })} onEvidence={evidence} /></>}
          </section>)}</>}
          {section === 'reception' && <><h2>접수처·기본정보</h2><p>임대유형·모집유형·기관·공고명은 현재값을 유지합니다. 접수처는 한 곳만 저장할 수 있으므로 온라인·현장 병행 여부를 확인하세요.</p><h3>접수처</h3><ReviewCells cells={review.reception} scope="접수처" onChange={reception => change({ ...review, reception })} onEvidence={evidence} /><h3>공고 기본 일정</h3><ReviewCells cells={review.metadata} scope="기본정보" onChange={metadata => change({ ...review, metadata })} onEvidence={evidence} /></>}
        </fieldset>
      </div></div>
      <footer className={styles.footer}><div><strong>{applicableCells(review).filter(cell => cell.selected && !cell.locked).length}개 값 반영</strong><span>선택하지 않은 값은 유지합니다.</span></div><div className={styles.actions}><Button data-design-system className={styles.secondaryButton} disabled={busy || blocked} onClick={() => { if (window.confirm('검토 입력을 초기화하고 응답 준비로 돌아갈까요?')) { setPhase('prepare'); setReview(null) } }}>응답 다시 준비</Button><Button data-design-system disabled={busy || blocked || !supplyDraft && Boolean(reasonProblem)} onClick={() => void apply()}>{busy ? '적용 중…' : supplyDraft ? '공급정보 편집표에 채우기' : '보정 내용 적용'}</Button></div></footer>
      {phase === 'review' && !supplyDraft && <details className={styles.reason} open={reasonProblem ? true : undefined}><summary>적용 근거 확인{reasonProblem ? ' · 필수 입력 필요' : ''}</summary>{reasonProblem && <p role="status" className={styles.error}>{reasonProblem}</p>}<label>보정 이유<textarea value={reason} maxLength={2000} disabled={busy || blocked} onChange={event => { setReason(event.target.value) }} /></label><label>공식 근거 URL<input value={sourceUrl} maxLength={2048} disabled={busy || blocked} onChange={event => { setSourceUrl(event.target.value) }} /></label><label>근거 쪽<input value={sourcePage} maxLength={100} disabled={busy || blocked} onChange={event => { setSourcePage(event.target.value) }} /></label></details>}
    </>}
    {phase === 'result' && <section className={styles.result}><h2>공고 보정을 적용했습니다.</h2><p>선택한 변경을 저장했습니다. 공개 화면에서 공고와 단지의 현재 공급정보를 확인하세요.</p><p>보정 저장은 공고 전체 검토 완료와 다릅니다.{failureReturn ? ` ${Number.isSafeInteger(failureCount) && failureCount > 0 ? `이 공고의 오류 ${failureCount}건은` : '연결된 오류는'} 다음 정제 때 다시 판정됩니다.` : ''}</p><div className={styles.actions}><Link to={`/?announcementId=${id}`} target="_blank" rel="noreferrer">공개 공고 확인</Link><Link to={`/admin/announcements/${id}?${supplyParams}`}>저장된 공급정보 확인</Link>{failureReturn && <Link to={failureReturn}>실패 검토로 돌아가기</Link>}</div></section>}
  </section>
}
