import { lazy, Suspense, useEffect, useMemo, useRef, useState, type DragEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { attachmentErrorMessage, loadAnnouncementAttachment } from '../../public-housing/api/announcementAttachments'
import type { PdfFocus } from '../../public-housing/components/PdfDocumentPreview'
import { applyJsonUpdate, loadJsonWorkspace, previewJsonUpdate, type JsonWorkspace } from '../announcement-json/api'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
import type { CorrectionRow, SupplyCorrectionInput } from '../supply/supplyCorrectionApi'
import { getManagementDetail, ManagementError } from '../management/api'
import { labels } from '../management/fields'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { readExtraction, type Evidence } from './extraction'
import { extractAnnouncementPdf, pdfFileProblem, preferredPdf, type ExtractionState } from './extractionApi'
import { checkAnnouncement, type ComplexLabels, type HousingCandidates } from './matching'
import { applicableCells, assembleReview, cellMatches, changeValue, createReview, housingNameCandidates, pendingChanges, pendingItems, pendingSchedule, rebaseReview, remapSchedule, ReviewError, reviewSection, shown, type HousingNameCandidate, type PendingChange, type Review, type ReviewFilter, type ReviewSection, type ScheduleReview } from './review'
import { ReviewCells } from './ReviewCells'
import { ReviewSupply } from './ReviewSupply'
import { RerunAnnouncement } from './RerunAnnouncement'
import { PdfSource, PreparationFooter, RequestPreparation } from './RequestPreparation'
import styles from './PdfCorrection.module.css'

const PdfDocumentPreview = lazy(() => import('../../public-housing/components/PdfDocumentPreview'))
type Phase = 'prepare' | 'review' | 'result'
type SelectedPdf = { url: string; name: string; file: Blob }
const sectionLabels: [ReviewSection, string][] = [['supply', '공급정보'], ['schedule', '일정'], ['reception', '접수처·기본정보']]
const filterLabels: [ReviewFilter, string][] = [['all', '전체'], ['pending', '확인 필요'], ['changed', '변경'], ['new', '추가']]
function validEvidenceUrl(value: string) {
  try { return ['http:', 'https:'].includes(new URL(value).protocol) && !/\s/.test(value) } catch { return false }
}
async function candidates(workspace: JsonWorkspace, signal?: AbortSignal): Promise<HousingCandidates> {
  const ids = [...new Set(workspace.supply.rows.map(row => row.housingComplexId).filter(id => id !== null))]
  return Object.fromEntries(await Promise.all(ids.map(async id => [id, await getMatchingHousingTypes(id, signal)])))
}
/** 기준 단지는 원천 공급행이 아니라 단지 도메인(단지명 + 임대유형)으로 보여준다. 조회에 실패하면 공급행의 연결 단지명만 쓴다. */
async function complexLabels(workspace: JsonWorkspace, signal?: AbortSignal): Promise<ComplexLabels> {
  const linked = new Map(workspace.supply.rows.flatMap(row => row.housingComplexId === null ? [] : [[row.housingComplexId, row.housingComplexName] as const]))
  return Object.fromEntries(await Promise.all([...linked].map(async ([id, fallback]) => {
    try {
      const { data } = await getManagementDetail('complexes', String(id), signal)
      const rental = typeof data.rentalType === 'string' ? labels[data.rentalType] ?? data.rentalType : null
      return [id, [typeof data.name === 'string' ? data.name : fallback ?? `단지 #${id}`, rental].filter(Boolean).join(' · ')]
    } catch (cause) {
      if (signal?.aborted) throw cause
      return [id, fallback ?? `단지 #${id}`]
    }
  })))
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
  const [workspace, setWorkspace] = useState<JsonWorkspace | null>(null), [housing, setHousing] = useState<HousingCandidates>({}), [complexes, setComplexes] = useState<ComplexLabels>({})
  const [phase, setPhase] = useState<Phase>('prepare'), [section, setSection] = useState<ReviewSection>('supply')
  const [text, setText] = useState(''), [review, setReview] = useState<Review | null>(null)
  // 응답 준비로 돌아갔다 와도 같은 응답·PDF면 검토하던 선택을 그대로 이어 간다.
  const [reviewedWith, setReviewedWith] = useState('')
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [busy, setBusy] = useState(false), [attempt, setAttempt] = useState(0)
  const [blocked, setBlocked] = useState(false), [uncertain, setUncertain] = useState(false)
  const [attachmentId, setAttachmentId] = useState('')
  const [attachmentPdf, setPdf] = useState<SelectedPdf | null>(null), [pdfError, setPdfError] = useState('')
  // 첨부파일이 없는 기관 공고는 관리자가 원문에서 받은 PDF를 직접 골라 같은 흐름으로 쓴다.
  const [localPdf, setLocalPdf] = useState<SelectedPdf | null>(null), [localPdfError, setLocalPdfError] = useState(''), [dragging, setDragging] = useState(false)
  const pdf = localPdf ?? attachmentPdf
  const [extraction, setExtraction] = useState<ExtractionState>({ status: 'idle' })
  const extractionRun = useRef<AbortController | null>(null)
  const [focus, setFocus] = useState<PdfFocus | null>(null), [mobileView, setMobileView] = useState<'pdf' | 'review'>('review')
  const [reason, setReason] = useState(''), [sourceUrl, setSourceUrl] = useState(''), [sourcePage, setSourcePage] = useState('')
  const [renames, setRenames] = useState<HousingNameCandidate[]>([])
  const [filter, setFilter] = useState<ReviewFilter>('all'), [jump, setJump] = useState<{ id: string; nonce: number } | null>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  const reviewPane = useRef<HTMLDivElement>(null)
  const reasonProblem = !reason.trim() ? '보정 이유를 입력하세요.' : !validEvidenceUrl(sourceUrl.trim()) ? '공식 근거 URL에 올바른 http 또는 https 주소를 입력하세요.' : ''
  const parsed = useMemo(() => readExtraction(text), [text])
  const checks = useMemo(() => parsed.ok && workspace ? checkAnnouncement(parsed.value, workspace.detail.data) : [], [parsed, workspace])
  const backParams = new URLSearchParams(params); backParams.delete('failureCount'); backParams.delete('failureRow')
  const supplyParams = new URLSearchParams(backParams); supplyParams.set('section', 'relations')
  const historyParams = new URLSearchParams(backParams); historyParams.set('section', 'history')
  const back = `/admin/announcements/${id}${backParams.size ? `?${backParams}` : ''}`
  const rawFailureReturn = params.get('failureReturnTo'), failureReturn = rawFailureReturn && /^\/admin\/failures(?:\?|$)/.test(rawFailureReturn) ? rawFailureReturn : null
  const failureCount = Number(params.get('failureCount'))
  const failureRows = params.getAll('failureRow')
  const active = phase === 'review'
  // 준비와 검토는 같은 작업대 배치를 써서 단계를 넘어가도 PDF 뷰어 크기가 그대로다.
  const focused = phase !== 'result'
  useUnsavedChanges(phase !== 'result' && Boolean(text.trim() || uncertain))
  useEffect(() => {
    const controller = new AbortController(); setWorkspace(null); setError('')
    if (!/^[1-9]\d*$/.test(id)) { setError('올바른 공고 주소가 아닙니다.'); return }
    void loadJsonWorkspace(id, controller.signal).then(async value => {
      if (initialRows) value = { ...value, supply: { ...value.supply, rows: initialRows } }
      const [next, names] = await Promise.all([candidates(value, controller.signal), complexLabels(value, controller.signal)])
      if (!controller.signal.aborted) { setWorkspace(value); setHousing(next); setComplexes(names); setSourceUrl(String(value.detail.data.originalUrl ?? '')); setAttachmentId(String(preferredPdf(value.detail.attachments ?? [])?.attachmentId ?? '')) }
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
      objectUrl = URL.createObjectURL(blob); setPdf({ url: objectUrl, name: attachment?.fileName ?? '공고문 PDF', file: blob })
    }).catch(cause => { if (!controller.signal.aborted) setPdfError(attachmentErrorMessage(cause)) })
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl) }
  }, [id, attachmentId, workspace?.detail.attachments])
  useEffect(() => () => { if (localPdf) URL.revokeObjectURL(localPdf.url) }, [localPdf])
  // 추출은 관리자가 버튼을 누를 때만 한다(호출마다 비용이 든다). PDF를 바꾸면 이전 결과와 진행 중인 요청을 버린다.
  useEffect(() => { extractionRun.current?.abort(); setExtraction({ status: 'idle' }); setText('') }, [pdf])
  useEffect(() => () => extractionRun.current?.abort(), [])
  function extract() {
    if (!pdf) return
    extractionRun.current?.abort()
    const controller = new AbortController(); extractionRun.current = controller
    setExtraction({ status: 'running', startedAt: Date.now() }); setText('')
    void extractAnnouncementPdf(id, pdf.file, pdf.name, controller.signal).then(result => {
      if (controller.signal.aborted) return
      setText(result.json); setExtraction({ status: 'done', result })
    }).catch(cause => { if (!controller.signal.aborted) setExtraction({ status: 'failed', message: cause instanceof Error ? cause.message : 'PDF에서 값을 추출하지 못했습니다.' }) })
  }
  useEffect(() => {
    heading.current?.focus()
    if (reviewPane.current) reviewPane.current.scrollTop = 0
  }, [phase])
  useEffect(() => {
    if (!jump) return
    const target = reviewPane.current?.querySelector<HTMLElement>(`[data-review-id="${CSS.escape(jump.id)}"]`)
    // 행·조건·일정 묶음은 머리(저장할 위치·확인 안내)가 보이도록 위에 맞추고, 값 칸은 가운데에 둔다.
    target?.scrollIntoView?.({ block: jump.id.startsWith('cell:') ? 'center' : 'start' }); target?.focus({ preventScroll: true })
  }, [jump])
  function nextPending() {
    if (!review) return
    const [item] = pendingItems(review)
    if (!item) return
    if (filter !== 'pending') setFilter('all')
    setSection(item.section); setMobileView('review'); setJump(previous => ({ id: item.id, nonce: (previous?.nonce ?? 0) + 1 }))
  }
  function chooseLocalPdf(file: File | undefined) {
    if (!file) return
    const problem = pdfFileProblem(file)
    setLocalPdfError(problem)
    if (problem) return
    setFocus(null); setLocalPdf({ url: URL.createObjectURL(file), name: file.name, file })
  }
  function evidence(item: Evidence) { setFocus(previous => ({ page: item.page, query: '', nonce: (previous?.nonce ?? 0) + 1 })); setMobileView('pdf') }
  function change(next: Review) { setReview(next); setError('') }
  function start() {
    if (!parsed.ok || !workspace || !pdf) return
    if (review && reviewedWith === `${pdf.url}\n${text}`) { setPhase('review'); setError(''); return }
    const extraction = supplyDraft ? { ...parsed.value, announcement: {}, receptionPlace: {}, schedules: [] } : parsed.value
    const next = createReview(extraction, workspace, housing)
    setReview(next); setReviewedWith(`${pdf.url}\n${text}`); setReason(`${pdf.name} · AI 자동 추출 후 PDF와 대조`)
    setSourcePage([...new Set(parsed.value.evidence.map(item => item.page))].sort((a, b) => a - b).join(', ').slice(0, 100))
    setPhase('review'); setSection('supply'); setFilter('all'); setError(''); setNotice('')
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
      setRenames(housingNameCandidates(review)); setWorkspace(latest); setPhase('result'); setText(''); setBlocked(false); setUncertain(false)
    } catch (cause) {
      const rejected = cause instanceof ManagementError && cause.status >= 400 && cause.status < 500
      const uncertainSave = saving && !rejected
      setUncertain(uncertainSave); setBlocked(uncertainSave || cause instanceof ManagementError && cause.status === 409)
      setError(uncertainSave ? '저장 응답을 받지 못했습니다. 최신 저장값을 조회해 적용 여부를 확인한 뒤 다시 비교하세요.' : cause instanceof Error ? cause.message : '보정 내용을 적용하지 못했습니다. 다시 시도하세요.')
      setMobileView('review')
      // 적용 전 검증에 걸리면 문제 항목으로 바로 이동한다.
      if (cause instanceof ReviewError && cause.reviewId) {
        const id = cause.reviewId
        setSection(reviewSection(id)); setFilter('all'); setJump(previous => ({ id, nonce: (previous?.nonce ?? 0) + 1 }))
      }
    } finally { setBusy(false) }
  }
  async function reload() {
    setBusy(true); setError('')
    try {
      const latest = await loadJsonWorkspace(id), [nextHousing, names] = await Promise.all([candidates(latest), complexLabels(latest)])
      if (review) setReview(rebaseReview(review, latest, nextHousing))
      setWorkspace(latest); setHousing(nextHousing); setComplexes(names); setBlocked(false); setUncertain(false); setPhase('review')
      setNotice('최신 저장값을 불러왔습니다. 수동 입력은 유지했습니다. 달라진 기존 값과 선택 항목을 비교한 뒤 다시 적용하세요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '최신값을 불러오지 못했습니다.') }
    finally { setBusy(false) }
  }
  if (!workspace) return <section className={styles.page}><h1>PDF로 공고 보정</h1>{error ? <div role="alert"><p>{error}</p><Button onClick={() => setAttempt(value => value + 1)}>다시 불러오기</Button></div> : <p role="status">공고와 연결 주택형을 불러오는 중…</p>}</section>
  if (workspace.detail.summary.deleted) return <section className={styles.page}><h1>휴지통의 공고입니다.</h1><p>공고를 복구한 뒤 보정하세요.</p><Link to={back}>공고 상세로 돌아가기</Link></section>
  const pending = review ? pendingItems(review) : []
  const changes = review ? pendingChanges(review) : []
  const counts = { changed: 0, new: 0 }
  if (review) for (const cell of applicableCells(review)) if (cell.extracted.state === 'changed' || cell.extracted.state === 'new') counts[cell.extracted.state] += 1
  const empty = `이 영역에는 ‘${filterLabels.find(([key]) => key === filter)?.[1]}’ 항목이 없습니다.`
  const visibleIn = (cells: Review['metadata']) => filter === 'all' || cells.some(cell => cellMatches(cell, filter))
  const scheduleMatches = (schedule: ScheduleReview) => filter === 'all' || filter === 'pending' && pendingSchedule(schedule) || schedule.include && visibleIn(schedule.cells)
  function updateSchedule(index: number, patch: Partial<ScheduleReview>) {
    if (review) change({ ...review, schedules: review.schedules.map((item, at) => at === index ? { ...item, ...patch } : item) })
  }
  const dropProps = phase === 'prepare' ? {
    'data-dragging': dragging,
    onDragOver: (event: DragEvent<HTMLElement>) => { event.preventDefault(); setDragging(true) },
    onDragLeave: () => setDragging(false),
    onDrop: (event: DragEvent<HTMLElement>) => { event.preventDefault(); setDragging(false); chooseLocalPdf(event.dataTransfer.files[0]) },
  } : {}
  const pdfPane = <section className={styles.pdfPane} aria-label="PDF 근거" hidden={focused && mobileView !== 'pdf'} data-pane="pdf" {...dropProps}><header>{phase === 'prepare' ? <PdfSource attachments={workspace.detail.attachments?.filter(item => /\.pdf(?:$|[?\s])/i.test(item.fileName)) ?? []} attachmentId={attachmentId} onAttachment={value => { setLocalPdf(null); setLocalPdfError(''); if (value !== attachmentId) { setPdf(null); setAttachmentId(value) } }} localPdf={Boolean(localPdf)} onLocalFile={chooseLocalPdf} pdf={pdf} pdfError={localPdfError || (localPdf ? '' : pdfError)} /> : <strong>{pdf?.name ?? '공고문 PDF'}</strong>}</header>{pdf ? <Suspense fallback={<p role="status">PDF 뷰어를 불러오는 중…</p>}><PdfDocumentPreview url={pdf.url} name={pdf.name} focus={focus} /></Suspense> : <p role={pdfError ? 'alert' : 'status'} className={styles.pdfEmpty}>{pdfError || 'PDF를 고르거나 여기에 끌어다 놓으세요.'}</p>}</section>
  return <section className={styles.page} data-focused={focused} data-embedded={!!supplyDraft}>
    {supplyDraft ? <header className={styles.heading}><div><h2>PDF로 공급정보 채우기</h2><p>PDF와 대조한 공급정보를 편집표에 채웁니다. 저장 전까지 등록값은 유지됩니다.</p></div><Button data-design-system className={styles.secondaryButton} onClick={() => { if (!text.trim() || window.confirm('PDF 검토를 취소하고 편집표로 돌아갈까요?')) supplyDraft.onCancel() }}>편집표로 돌아가기</Button></header> : <header className={styles.heading}><div><Link to={back}>공고 상세로 돌아가기</Link><h1 ref={heading} tabIndex={-1}>PDF로 공고 보정</h1><p>#{id} · {workspace.detail.summary.name}</p>{phase !== 'result' && <FailureTargets rows={failureRows} workspace={workspace} />}</div><ol aria-label="보정 진행"><li aria-current={phase === 'prepare' ? 'step' : undefined}>요청 준비</li><li aria-current={phase === 'review' ? 'step' : undefined}>비교·보정</li><li aria-current={phase === 'result' ? 'step' : undefined}>적용 결과</li></ol></header>}
    {error && <div role="alert" className={styles.error}><p>{error}</p>{blocked && <Button data-design-system onClick={() => void reload()} disabled={busy}>입력을 유지하고 최신값 비교</Button>}</div>}
    {notice && <p role="status" className={styles.notice}>{notice}</p>}
    {phase === 'prepare' && <><div className={styles.mobileSwitch}><Button data-design-system aria-pressed={mobileView === 'pdf'} onClick={() => setMobileView('pdf')}>PDF 보기</Button><Button data-design-system aria-pressed={mobileView === 'review'} onClick={() => setMobileView('review')}>추출 보기</Button></div><div className={styles.workbench}>{pdfPane}
      <div className={styles.reviewPane} hidden={mobileView !== 'review'} data-pane="prepare"><RequestPreparation announcementName={workspace.detail.summary.name} pdf={pdf} extraction={extraction} onExtract={extract} parsed={parsed} checks={checks} /></div>
    </div><PreparationFooter ready={extraction.status === 'done' && parsed.ok && Boolean(pdf)} onReview={start} /></>}
    {active && review && <><div className={styles.mobileSwitch}><Button data-design-system aria-pressed={mobileView === 'pdf'} onClick={() => setMobileView('pdf')}>PDF 보기</Button><Button data-design-system aria-pressed={mobileView === 'review'} onClick={() => setMobileView('review')}>검토표 보기</Button></div><div className={styles.workbench}>{pdfPane}
      <div ref={reviewPane} className={styles.reviewPane} hidden={mobileView !== 'review'} data-pane="review">
        {checks.some(check => check.matches === false) && <p className={styles.error}>PDF의 {checks.filter(check => check.matches === false).map(check => `${check.label}(${check.pdf})`).join('·')}이 현재 공고와 다릅니다. 이 공고의 PDF로 받은 응답인지 확인하세요.</p>}
        <div className={styles.reviewTop}>
          {!supplyDraft && <nav className={styles.sections} aria-label="PDF 보정 영역">{sectionLabels.map(([key, label]) => { const count = pending.filter(item => item.section === key).length; return <Button data-design-system key={key} aria-label={count > 0 ? `${label} · 확인 ${count}` : undefined} aria-pressed={section === key} onClick={() => setSection(key)}>{label}{count > 0 && <span className={styles.sectionCount}>{` · 확인 ${count}`}</span>}</Button> })}</nav>}
          <div className={styles.filters} role="group" aria-label="검토 항목 필터">{filterLabels.map(([key, label]) => <Button data-design-system key={key} aria-pressed={filter === key} onClick={() => setFilter(key)}>{label}{key !== 'all' && ` ${key === 'pending' ? pending.length : counts[key]}`}</Button>)}</div>
        </div>
        <fieldset disabled={busy || blocked} className={styles.reviewFields}>
          {section === 'supply' && <ReviewSupply review={review} workspace={workspace} housing={housing} complexes={complexes} filter={filter} empty={empty} onChange={change} onEvidence={evidence} />}
          {section === 'schedule' && <><h2>일정</h2><p>기존 단지별 일정은 각 ID를 유지하며 같은 보정값으로 갱신합니다.</p>{review.schedules.map((schedule, index) => scheduleMatches(schedule) && <section className={styles.supplyBlock} key={index} data-review-id={`schedule:${index}`} tabIndex={-1}><header><h3>{shown(schedule.cells.find(cell => cell.field === 'name')?.value)}</h3><label><input type="checkbox" checked={schedule.include} onChange={event => updateSchedule(index, { include: event.target.checked, reviewed: true })} />일정 {index + 1} 적용</label></header>
            {pendingSchedule(schedule) && <div className={styles.pending}><p><strong>확인 필요</strong> {schedule.proposal.reason}</p><div className={styles.actions}><Button data-design-system className={styles.secondaryButton} onClick={() => updateSchedule(index, { include: true, reviewed: true })}>{`일정 ${index + 1} 적용`}</Button><Button data-design-system className={styles.secondaryButton} onClick={() => updateSchedule(index, { include: false, reviewed: true })}>{`일정 ${index + 1} 적용 안 함`}</Button></div></div>}
            <p>{pendingSchedule(schedule) ? '' : schedule.proposal.reason || (schedule.proposal.existingIds.length ? '기존 일정 갱신' : '새 일정 추가')}{schedule.proposal.existingIds.map(existingId => { const event = workspace.events.find(item => item.id === existingId); return <small className={styles.scheduleBefore} key={existingId}>#{existingId} · {event?.complexName ?? '공고 전체'} · {shown(event?.data.name)} · {shown(event?.data.startAt)} ~ {shown(event?.data.endAt)}</small> })}</p>
            {schedule.include && <><fieldset><legend>일정 {index + 1}에서 수정할 기존 일정</legend>{workspace.events.filter(event => event.data.scheduleType === schedule.cells.find(cell => cell.field === 'scheduleType')?.value).map(event => <label key={event.id}><input type="checkbox" checked={schedule.proposal.existingIds.includes(event.id)} onChange={changeEvent => change({ ...review, schedules: review.schedules.map((item, at) => at === index ? { ...remapSchedule(review, index, changeEvent.target.checked ? [...item.proposal.existingIds, event.id] : item.proposal.existingIds.filter(value => value !== event.id), workspace), reviewed: true } : item) })} />#{event.id} · {event.complexName ?? '공고 전체'} · {shown(event.data.name)}</label>)}<p className={styles.hint}>선택한 기존 일정이 없으면 새 일정으로 추가합니다.</p></fieldset>
              <ReviewCells cells={schedule.cells} scope={`일정 ${index + 1}`} filter={filter} onChange={cells => updateSchedule(index, { cells })} onEvidence={evidence} /></>}
          </section>)}{!review.schedules.some(scheduleMatches) && <p className={styles.hint}>{empty}</p>}</>}
          {section === 'reception' && <><h2>접수처·기본정보</h2><p>임대유형·모집유형·기관·공고명은 현재값을 유지합니다. 접수처는 한 곳만 저장할 수 있으므로 온라인·현장 병행 여부를 확인하세요.</p>{visibleIn(review.reception) && <h3>접수처</h3>}<ReviewCells cells={review.reception} scope="접수처" filter={filter} onChange={reception => change({ ...review, reception })} onEvidence={evidence} />{visibleIn(review.metadata) && <h3>공고 기본 일정</h3>}<ReviewCells cells={review.metadata} scope="기본정보" filter={filter} onChange={metadata => change({ ...review, metadata })} onEvidence={evidence} />{!visibleIn(review.reception) && !visibleIn(review.metadata) && <p className={styles.hint}>{empty}</p>}</>}
        </fieldset>
      </div></div>
      <ChangeList changes={changes} />
      <footer className={styles.footer}><div><strong>{changes.length}개 값 반영</strong><span>{changeSummary(changes)} · 선택하지 않은 값은 유지합니다.</span></div><div className={styles.actions}>{pending.length > 0 ? <Button data-design-system className={styles.pendingButton} onClick={nextPending}>{`확인 필요 ${pending.length}개 남음 ↓`}</Button> : <span className={styles.pendingDone}>확인 필요 없음</span>}<Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => { setPhase('prepare'); setError('') }}>응답 준비로 돌아가기</Button><Button data-design-system disabled={busy || blocked || !supplyDraft && Boolean(reasonProblem)} onClick={() => void apply()}>{busy ? '적용 중…' : supplyDraft ? '공급정보 편집표에 채우기' : '보정 내용 적용'}</Button></div></footer>
      {phase === 'review' && !supplyDraft && <details className={styles.reason} open={reasonProblem ? true : undefined}><summary>적용 근거 확인{reasonProblem ? ' · 필수 입력 필요' : ''}</summary>{reasonProblem && <p role="status" className={styles.error}>{reasonProblem}</p>}<label>보정 이유<textarea value={reason} maxLength={2000} disabled={busy || blocked} onChange={event => { setReason(event.target.value) }} /></label><label>공식 근거 URL<input value={sourceUrl} maxLength={2048} disabled={busy || blocked} onChange={event => { setSourceUrl(event.target.value) }} /></label><label>근거 쪽<input value={sourcePage} maxLength={100} disabled={busy || blocked} onChange={event => { setSourcePage(event.target.value) }} /></label></details>}
    </>}
    {phase === 'result' && <section className={styles.result}><h2>공고 보정을 적용했습니다.</h2><p>선택한 변경을 저장했습니다. 공개 화면에서 공고와 단지의 현재 공급정보를 확인하세요.</p><p>보정 저장은 공고 전체 검토 완료와 다릅니다.{failureReturn ? ` ${Number.isSafeInteger(failureCount) && failureCount > 0 ? `이 공고의 오류 ${failureCount}건은` : '연결된 오류는'} 다음 정제 때 다시 판정됩니다.` : ''}</p><div className={styles.actions}><Link to={`/?announcementId=${id}`} target="_blank" rel="noreferrer">공개 공고 확인</Link><Link to={`/admin/announcements/${id}?${supplyParams}`}>저장된 공급정보 확인</Link><Link to={`/admin/announcements/${id}?${historyParams}`}>출처·수정 이력 보기</Link>{failureReturn && <Link to={failureReturn}>실패 검토로 돌아가기</Link>}</div><RerunAnnouncement workspace={workspace} failureReturn={failureReturn} auto />
      {renames.length > 0 && <section className={styles.renames} aria-labelledby="housing-name-title"><h3 id="housing-name-title">단지 주택형 이름 후보</h3><p className={styles.hint}>연결한 단지 주택형의 이름이 이번 공고 표기와 다릅니다. 이 화면은 저장하지 않으며, 바꾸려면 단지 상세에서 따로 수정하세요. 관리자가 수정한 주택형은 이후 재수집으로 이름·면적·세대수가 갱신되지 않습니다.</p><ul>{renames.map(item => <li key={item.id}>#{item.id} 「{item.current}」 → 공고 표기 「{item.pdf}」</li>)}</ul></section>}
    </section>}
  </section>
}

const changeSections: PendingChange['section'][] = ['기본정보', '접수처', '공급정보', '일정']
function changeSummary(changes: PendingChange[]) {
  return changeSections.map(section => `${section} ${changes.filter(change => change.section === section).length}`).join(' · ')
}

// 다른 탭의 변경도 저장 전에 한 곳에서 확인하도록 영역별 변경 전후를 보여 준다.
function ChangeList({ changes }: { changes: PendingChange[] }) {
  if (!changes.length) return null
  return <details className={styles.changes}><summary>{`반영할 변경 ${changes.length}개 보기 · ${changeSummary(changes)}`}</summary>
    <div className={styles.changeTable} role="region" aria-label="반영할 변경 목록" tabIndex={0}><table><thead><tr><th scope="col">영역</th><th scope="col">대상</th><th scope="col">항목</th><th scope="col">현재</th><th scope="col">저장 후</th></tr></thead>
      <tbody>{changes.map(change => <tr key={change.cell.path}><td>{change.section}</td><td>{change.scope}</td><th scope="row">{change.cell.label}</th><td>{changeValue(change, change.cell.before)}</td><td>{changeValue(change, change.cell.value)}</td></tr>)}</tbody></table></div>
  </details>
}

const failureReasons: Record<string, string> = {
  AMBIGUOUS_HOUSING_TYPE: '같은 면적의 주택형이 여러 개라 하나로 정하지 못했습니다',
  HOUSING_TYPE_NOT_FOUND: '맞는 주택형을 찾지 못했습니다',
}
// 실패 검토에서 들어오면 어떤 공급행의 무엇을 고치러 왔는지 먼저 보여 준다.
function FailureTargets({ rows, workspace }: { rows: string[]; workspace: JsonWorkspace }) {
  if (!rows.length) return null
  const sources = workspace.supply.rowSources ?? []
  return <div className={styles.failureTargets} role="note" aria-label="고칠 오류"><strong>실패 검토에서 연 오류</strong><ul>{rows.map(value => {
    const [reason = '', sourceKey = ''] = value.split('|')
    const rowKey = sources.find(source => source.sourceKey === sourceKey)?.rowKey
    const row = workspace.supply.rows.find(item => item.key === rowKey)
    return <li key={value}>{row ? `${row.housingComplexName ?? row.complexName} · ${row.typeName} (#${String(row.id)})` : `원천 공급행 ${sourceKey}`}: {failureReasons[reason] ?? reason}. 이 행의 연결 주택형을 정하면 다음 정제에서 다시 판정합니다.</li>
  })}</ul></div>
}
