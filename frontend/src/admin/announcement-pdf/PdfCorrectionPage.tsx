import { lazy, Suspense, useEffect, useLayoutEffect, useMemo, useRef, useState, type DragEvent, type KeyboardEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { Button } from '../../design-system/components/Button'
import { attachmentErrorMessage, loadAnnouncementAttachment } from '../../public-housing/api/announcementAttachments'
import type { PdfFocus } from '../../public-housing/components/PdfDocumentPreview'
import { applyJsonUpdate, loadJsonWorkspace, previewJsonUpdate, type JsonPreview, type JsonUpdateInput, type JsonWorkspace } from '../announcement-json/api'
import { correctionReflected, documentReflected, summarizePreview } from './applySummary'
import { ApplySummaryPanel } from './ApplySummaryPanel'
import { getMatchingHousingTypes } from '../ingest/supplyMatchingApi'
import type { CorrectionRow, SupplyCorrectionInput } from '../supply/supplyCorrectionApi'
import { getManagementDetail, ManagementError } from '../management/api'
import { labels, rentals } from '../management/fields'
import { useUnsavedChanges } from '../management/useUnsavedChanges'
import { baseRowChanges, draftBaseRows, loadCorrectionDraft, type CorrectionDraft } from './correctionDraftApi'
import { useCorrectionDraft, type DraftStatus } from './useCorrectionDraft'
import { evidenceContext, evidenceQuery, readExtraction, type Evidence } from './extraction'
import { extractAnnouncementPdf, loadLatestExtraction, pdfFileProblem, pdfSha256, preferredPdf, type ExtractionState, type SavedExtraction } from './extractionApi'
import { checkAnnouncement, type ComplexLabels, type HousingCandidates } from './matching'
import { applicableCells, assembleReview, fieldNeeds, pendingField, serverFieldError, serverFieldsError, complexGroups, pendingRow, rowOfReviewId, cellMatches, createReview, pendingChanges, pendingItems, pendingSchedule, rebaseReview, remapSchedule, ReviewError, reviewSection, shown, type Cell, type ReleaseChoice, type PendingChange, type Review, type ReviewFilter, type ReviewSection, type ScheduleReview } from './review'
import { ReviewCells } from './ReviewCells'
import { ReviewSupply } from './ReviewSupply'
import { PdfSource, PreparationFooter, RequestPreparation } from './RequestPreparation'
import styles from './PdfCorrection.module.css'

const PdfDocumentPreview = lazy(() => import('../../public-housing/components/PdfDocumentPreview'))
type Phase = 'prepare' | 'review' | 'result'
// attachmentId가 있으면 공고 첨부라 추출 때 서버가 같은 첨부를 받는다.
type SelectedPdf = { url: string; name: string; file: Blob; attachmentId?: string }
type SectionTab = { key: string; label: string; count: number; section: ReviewSection; row?: number; selected: boolean }
/**
 * 검토 영역 탭. 공급정보는 PDF 단지마다 한 탭이고(고른 행이 속한 단지가 열린 탭), 그 뒤에 일정·접수처·기본정보가 온다.
 * 공급행이 없으면 공급정보 탭 하나를 둔다. 확인 수는 그 단지 행의 남은 확인 항목이다.
 */
function sectionTabs(review: Review, section: ReviewSection, selectedRow: number, pending: { id: string; section: ReviewSection }[], supplyOnly = false): SectionTab[] {
  const groups = complexGroups(review), active = groups.find(group => group.indexes.includes(selectedRow)) ?? groups[0]
  const count = (key: ReviewSection) => pending.filter(item => item.section === key).length
  const supply: SectionTab[] = groups.length ? groups.map(group => ({ key: `supply:${group.name}`, label: group.name, section: 'supply', selected: section === 'supply' && group === active,
    row: group.indexes.includes(selectedRow) ? selectedRow : group.indexes[0], count: pending.filter(item => item.section === 'supply' && group.indexes.includes(rowOfReviewId(item.id) ?? -1)).length }))
    : [{ key: 'supply', label: '공급정보', section: 'supply', selected: section === 'supply', count: count('supply') }]
  if (supplyOnly) return supply
  return [...supply, { key: 'schedule', label: '일정', section: 'schedule', selected: section === 'schedule', count: count('schedule') },
    { key: 'reception', label: '접수처·기본정보', section: 'reception', selected: section === 'reception', count: count('reception') }]
}
/** 탭 목록. 화살표·Home·End로 탭 사이를 옮기며 바로 연다(자동 활성화). */
function SectionTabs({ tabs, onSelect }: { tabs: SectionTab[]; onSelect: (tab: SectionTab) => void }) {
  const list = useRef<HTMLDivElement>(null)
  function move(event: KeyboardEvent<HTMLDivElement>) {
    const at = tabs.findIndex(tab => tab.selected), last = tabs.length - 1
    const next = event.key === 'ArrowRight' ? (at + 1) % tabs.length : event.key === 'ArrowLeft' ? (at - 1 + tabs.length) % tabs.length : event.key === 'Home' ? 0 : event.key === 'End' ? last : null
    if (next === null) return
    event.preventDefault(); onSelect(tabs[next])
    requestAnimationFrame(() => list.current?.querySelectorAll<HTMLElement>('[role=tab]')[next]?.focus())
  }
  return <div ref={list} role="tablist" aria-label="PDF 보정 영역" className={styles.sections} onKeyDown={move}>{tabs.map(tab => <button type="button" role="tab" key={tab.key} id={tab.selected ? 'pdf-review-tab-active' : undefined}
    aria-selected={tab.selected} aria-controls="pdf-review-panel" tabIndex={tab.selected ? 0 : -1} title={tab.label} aria-label={tab.count > 0 ? `${tab.label} · 확인 ${tab.count}` : undefined} onClick={() => onSelect(tab)}>{tab.label}{tab.count > 0 && <span className={styles.sectionCount}>{` · 확인 ${tab.count}`}</span>}</button>)}</div>
}
const filterLabels: [ReviewFilter, string][] = [['all', '전체'], ['pending', '확인 필요'], ['changed', '변경'], ['new', '추가']]
/** 서버 검증(^https?://[^\s]+$, 2048자 이하)과 같이 소문자 http(s)://로 시작하고 공백이 없어야 한다. */
function validEvidenceUrl(value: string) {
  try { return value.length <= 2048 && /^https?:\/\/\S+$/.test(value) && Boolean(new URL(value)) } catch { return false }
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
  const [release, setRelease] = useState<ReleaseChoice>({ rentalType: '', recruitmentType: '', publish: false }), [published, setPublished] = useState(false)
  const [attachmentId, setAttachmentId] = useState('')
  const [attachmentPdf, setPdf] = useState<SelectedPdf | null>(null), [pdfError, setPdfError] = useState('')
  // 첨부파일이 없는 기관 공고는 관리자가 원문에서 받은 PDF를 직접 골라 같은 흐름으로 쓴다.
  const [localPdf, setLocalPdf] = useState<SelectedPdf | null>(null), [localPdfError, setLocalPdfError] = useState(''), [dragging, setDragging] = useState(false)
  const pdf = localPdf ?? attachmentPdf
  const [extraction, setExtraction] = useState<ExtractionState>({ status: 'idle' })
  const extractionRun = useRef<AbortController | null>(null)
  // 같은 PDF를 다시 추출하는 비용을 아끼려고 서버에 남긴 최근 결과를 불러올 수 있게 한다.
  const [saved, setSaved] = useState<SavedExtraction | null>(null), [pdfHash, setPdfHash] = useState('')
  const [focus, setFocus] = useState<PdfFocus | null>(null), [mobileView, setMobileView] = useState<'pdf' | 'review'>('review')
  const [reason, setReason] = useState(''), [sourceUrl, setSourceUrl] = useState(''), [sourcePage, setSourcePage] = useState('')
  // 검토를 시작할 때 자동 근거가 저장을 막으면 입력란을 연다. 입력 중 값이 유효해져도 닫지 않아야 URL을 끝까지 칠 수 있다.
  const [reasonInput, setReasonInput] = useState(false)
  const [typeResult, setTypeResult] = useState<Pick<JsonPreview, 'housingTypes' | 'housingTypeWarnings'>>({ housingTypes: [], housingTypeWarnings: [] })
  const [selectedRow, setSelectedRow] = useState(0)
  const [filter, setFilter] = useState<ReviewFilter>('all'), [jump, setJump] = useState<{ id: string; nonce: number } | null>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  // 서버 임시저장. 저장된 초안이 있으면 복원·폐기를 고르기 전에는 자동 저장하지 않는다(덮어쓰지 않게).
  const drafts = useCorrectionDraft(id, !supplyDraft)
  const [storedDraft, setStoredDraft] = useState<CorrectionDraft | null>(null), [draftChecked, setDraftChecked] = useState(Boolean(supplyDraft))
  const [draftTick, setDraftTick] = useState(0), [draftError, setDraftError] = useState('')
  // 복원 안내는 배너 대신 상단 임시저장 상태 옆에 짧게 둔다. 배너가 쌓이면 PDF와 검토표 높이가 줄어든다.
  const [restoredNote, setRestoredNote] = useState('')
  const restoredText = useRef('')
  // 복원한 초안에는 추출 결과가 없으므로 저장해 둔 AI 워커 경고를 다시 보인다.
  const [restoredWarnings, setRestoredWarnings] = useState<string[]>([])
  // 적용 전 확인: 서버 미리보기를 받은 보정안. 화면을 고치면 다시 확인해야 한다. lastInput은 응답이 불분명했던 적용 요청이다.
  const [checked, setChecked] = useState<{ input: JsonUpdateInput; preview: JsonPreview } | null>(null)
  const lastInput = useRef<JsonUpdateInput | null>(null)
  useEffect(() => setChecked(null), [review, reason, sourceUrl, sourcePage])
  useEffect(() => { if (checked) reviewPane.current?.querySelector<HTMLElement>('[aria-label="적용 전 확인"]')?.focus() }, [checked])
  const reviewPane = useRef<HTMLDivElement>(null)
  const reasonProblem = !reason.trim() ? '보정 이유를 입력하세요.' : !validEvidenceUrl(sourceUrl.trim()) ? '공식 근거 URL에 올바른 http 또는 https 주소를 입력하세요.' : ''
  // 긴 응답을 입력마다 다시 읽지 않도록 검토 버튼을 누를 때만 읽는다.
  // 응답은 추출이 끝날 때만 바뀌므로 바로 읽어 추출 요약과 공고 일치 확인에 쓴다.
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
  // 임시저장이 끝나지 않았거나 실패·충돌한 편집이 있으면 떠나기 전에 묻는다. 서버에 저장된 편집은 다시 열어 이어 갈 수 있다.
  const unsavedDraft = Boolean(review) && drafts.status.state !== 'saved'
  useUnsavedChanges(phase !== 'result' && (supplyDraft ? Boolean(text.trim() || uncertain) : uncertain || unsavedDraft || !review && Boolean(text.trim())))
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
    const pending = loadAnnouncementAttachment(id, attachmentId, false, controller.signal, true)
    void pending.then(blob => {
      if (controller.signal.aborted) return
      objectUrl = URL.createObjectURL(blob); setPdf({ url: objectUrl, name: attachment?.fileName ?? '공고문 PDF', file: blob, attachmentId })
    }).catch(cause => { if (!controller.signal.aborted) setPdfError(attachmentErrorMessage(cause)) })
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl) }
  }, [id, attachmentId, workspace?.detail.attachments])
  useEffect(() => () => { if (localPdf) URL.revokeObjectURL(localPdf.url) }, [localPdf])
  // 추출은 관리자가 버튼을 누를 때만 한다(호출마다 비용이 든다). PDF를 바꾸면 이전 결과와 진행 중인 요청을 버린다.
  // 화면에 그리기 전에 비워야 새 PDF의 추출 버튼을 바로 눌러도 늦게 도는 초기화가 그 요청을 취소하지 않는다.
  useLayoutEffect(() => { extractionRun.current?.abort(); setExtraction({ status: 'idle' }); setText('') }, [pdf])
  useEffect(() => () => extractionRun.current?.abort(), [])
  useEffect(() => {
    const controller = new AbortController(); setSaved(null)
    if (!/^[1-9]\d*$/.test(id)) return
    void loadLatestExtraction(id, controller.signal).then(value => { if (!controller.signal.aborted) setSaved(value) }).catch(() => {})
    return () => controller.abort()
  }, [id])
  useEffect(() => {
    if (supplyDraft || !workspace || draftChecked || storedDraft) return
    const controller = new AbortController()
    void loadCorrectionDraft(id, controller.signal).then(draft => {
      if (controller.signal.aborted) return
      if (draft) setStoredDraft(draft); else { drafts.adopt(null); setDraftChecked(true) }
    }).catch(cause => { if (!controller.signal.aborted) { setDraftError(`임시저장본을 확인하지 못했습니다. ${cause instanceof Error ? cause.message : ''}`.trim()); setDraftChecked(true) } })
    return () => controller.abort()
  }, [id, workspace, supplyDraft, draftChecked, storedDraft, drafts])
  const extractionId = extraction.status === 'done' ? extraction.result.jobId : undefined
  const workerWarnings = extraction.status === 'done' ? extraction.result.warnings : restoredWarnings
  useEffect(() => {
    if (supplyDraft || !draftChecked || phase !== 'review' || !review || !workspace) return
    drafts.schedule({ baseSnapshotToken: workspace.snapshotToken, ...(pdfHash ? { pdfSha256: pdfHash } : {}), ...(extractionId ? { extractionId } : {}),
      payload: { version: 1, text: text || restoredText.current, review, reason, sourceUrl, sourcePage, section, pdfName: pdf?.name ?? '', baseRows: draftBaseRows(workspace.supply.rows), warnings: workerWarnings, selectedRow } })
    // 편집 내용이 바뀔 때만 저장을 예약한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [review, reason, sourceUrl, sourcePage, section, selectedRow, draftChecked, draftTick])
  function restoreDraft() {
    if (!storedDraft || !workspace) return
    const value = storedDraft.payload
    let next = value.review
    try { if (storedDraft.stale) next = rebaseReview(value.review, workspace, housing) } catch { setDraftError('임시저장본을 최신 공고 값에 맞추지 못해 저장 당시 그대로 복원했습니다. 기존 값을 다시 확인하세요.') }
    restoredText.current = value.text; setRestoredWarnings(value.warnings ?? [])
    setText(value.text); setReview(next); setReviewedWith(pdf ? `${pdf.url}\n${value.text}` : ''); setReason(value.reason); setSourceUrl(value.sourceUrl); setSourcePage(value.sourcePage)
    setSection(value.section); setSelectedRow(typeof value.selectedRow === 'number' && value.selectedRow >= 0 && value.selectedRow < next.rows.length ? value.selectedRow : 0); setFilter('all'); setPhase('review'); setError('')
    drafts.adopt(storedDraft.revision); setStoredDraft(null); setDraftChecked(true)
    setRestoredNote(storedDraft.stale ? '최신 공고 값에 맞춰 복원함 · 바뀐 기존 값을 확인하세요' : '임시저장본 복원함')
  }
  async function discardDraft() {
    if (!storedDraft) return
    try {
      await drafts.remove(storedDraft.revision); drafts.adopt(null)
      setStoredDraft(null); setDraftChecked(true); setDraftTick(value => value + 1); setDraftError('')
    } catch (cause) { setDraftError(cause instanceof ManagementError && cause.status === 409 ? '그사이 다른 탭에서 초안이 바뀌어 폐기하지 않았습니다. 최신 초안을 다시 불러오세요.' : '임시저장본을 폐기하지 못했습니다. 다시 시도하세요.') }
  }
  async function reloadDraft() {
    try { const draft = await loadCorrectionDraft(id); if (draft) setStoredDraft(draft); else { drafts.adopt(null); setDraftTick(value => value + 1) } }
    catch { setDraftError('최신 초안을 불러오지 못했습니다. 다시 시도하세요.') }
  }
  useEffect(() => {
    let current = true; setPdfHash('')
    if (pdf) void pdfSha256(pdf.file).then(hash => { if (current) setPdfHash(hash) }).catch(() => {})
    return () => { current = false }
  }, [pdf])
  function loadSaved() {
    if (!saved) return
    extractionRun.current?.abort()
    setText(saved.extraction.json); setExtraction({ status: 'done', result: saved.extraction, saved })
  }
  function extract() {
    if (!pdf) return
    extractionRun.current?.abort()
    const controller = new AbortController(); extractionRun.current = controller
    setExtraction({ status: 'running', startedAt: Date.now() }); setText('')
    const onNotice = (message: string | null) => {
      if (!controller.signal.aborted) setExtraction(previous => previous.status === 'running' ? { ...previous, notice: message ?? undefined } : previous)
    }
    void extractAnnouncementPdf(id, pdf.file, pdf.name, controller.signal, { attachmentId: pdf.attachmentId, onNotice }).then(result => {
      if (controller.signal.aborted) return
      setText(result.json); setExtraction({ status: 'done', result })
      if (pdfHash) setSaved({ fileName: pdf.name, pdfSha256: pdfHash, createdAt: new Date().toISOString(), extraction: result })
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
    const [item] = pendingItems(review, !supplyDraft, workspace ?? undefined)
    if (!item) return
    if (filter !== 'pending') setFilter('all')
    const row = rowOfReviewId(item.id)
    if (row !== null) setSelectedRow(row)
    setSection(item.section); setMobileView('review'); setJump(previous => ({ id: item.id, nonce: (previous?.nonce ?? 0) + 1 }))
  }
  function chooseLocalPdf(file: File | undefined) {
    if (!file) return
    const problem = pdfFileProblem(file)
    setLocalPdfError(problem)
    if (problem) return
    setFocus(null); setLocalPdf({ url: URL.createObjectURL(file), name: file.name, file })
  }
  function evidence(item: Evidence, cell?: Cell) {
    const context = cell && review ? evidenceContext(review.extraction, cell.path) : []
    setFocus(previous => ({ page: item.page, query: evidenceQuery(item.quote, cell ? cell.extracted.value : undefined, cell?.kind === 'money', context), context, quote: item.quote, nonce: (previous?.nonce ?? 0) + 1 })); setMobileView('pdf')
  }
  // 목록에서 공급행을 고르면 그 행의 주택형 표기 근거로 PDF를 옮겨, 표의 어느 줄인지 바로 대조하게 한다.
  // 좁은 화면에서는 편집 영역을 그대로 두도록 PDF 보기로 바꾸지 않는다.
  function selectRow(index: number) {
    setSelectedRow(index)
    const cell = review?.rows[index]?.cells.find(item => item.field === 'sourceHousingTypeName')
    if (!review || !cell?.evidence) return
    const context = evidenceContext(review.extraction, cell.path), item = cell.evidence
    setFocus(previous => ({ page: item.page, query: evidenceQuery(item.quote, cell.extracted.value, false, context), context, quote: item.quote, nonce: (previous?.nonce ?? 0) + 1 }))
  }
  // 복원한 초안처럼 추출 상태 없이 검토 중인 입력이 있으면, 같은 PDF·응답일 때 다시 추출하지 않고 그 입력으로 돌아간다.
  const resumable = Boolean(review && pdf && reviewedWith === `${pdf.url}\n${text}`)
  function change(next: Review) { setReview(next); setError('') }
  function start() {
    if (!parsed.ok || !workspace || !pdf) return
    if (review && reviewedWith === `${pdf.url}\n${text}`) { setPhase('review'); setError(''); return }
    const extraction = supplyDraft ? { ...parsed.value, announcement: {}, receptionPlace: {}, schedules: [] } : parsed.value
    const next = createReview(extraction, workspace, housing)
    setReview(next); setReviewedWith(`${pdf.url}\n${text}`); setReason(`${pdf.name} · AI 자동 추출 후 PDF와 대조`); setReasonInput(!validEvidenceUrl(sourceUrl.trim()))
    setSourcePage([...new Set(parsed.value.evidence.map(item => item.page))].sort((a, b) => a - b).join(', ').slice(0, 100))
    setPhase('review'); setSection('supply'); setFilter('all'); setError(''); setNotice('')
  }
  /** 1단계: 보정안을 만들고 서버 미리보기로 검증해 적용 전 확인을 연다. 공급정보 편집표 채우기는 바로 돌려준다. */
  async function apply() {
    if (!review || !workspace || blocked || busy || !supplyDraft && reasonProblem) return
    setBusy(true); setError('')
    let input: JsonUpdateInput | undefined
    try {
      input = assembleReview(review, workspace, { reason: reason.trim(), sourceUrl: sourceUrl.trim(), sourcePage: sourcePage.trim() || null }, unconfirmed(workspace) ? release : undefined)
      if (supplyDraft) { supplyDraft.onFill(input.correction); return }
      const preview = await previewJsonUpdate(id, input)
      setChecked({ input, preview })
    } catch (cause) { fail(cause, false, input) } finally { setBusy(false) }
  }
  /** 2단계: 확인한 미리보기 그대로 저장한다. */
  async function confirmApply() {
    if (!checked || busy || blocked) return
    setBusy(true); setError('')
    const { input, preview } = checked
    lastInput.current = input
    try {
      const latest = await applyJsonUpdate(id, input, preview.confirmationToken)
      finishApplied(latest, preview)
    } catch (cause) {
      // 이미 처리한 요청을 다시 보냈다는 409는 아무것도 바꾸지 않았으므로, 최신값에 이 변경이 들어 있는지 확인해 알린다.
      if (cause instanceof ManagementError && cause.status === 409 && cause.code === 'ADMIN_DATA_CONFLICT') {
        try {
          const latest = await loadJsonWorkspace(id)
          if (correctionReflected(input.correction.rows, latest.supply) && documentReflected(input, latest)) { finishApplied(latest, preview); setNotice('이 보정은 이미 반영돼 있습니다. 같은 요청을 다시 저장하지 않았습니다.'); return }
          setError('같은 보정 요청을 이미 처리했다는 응답을 받았지만 저장된 공급정보·기본정보·일정이 이 화면의 변경과 다릅니다. 이 변경은 반영되지 않았습니다. 최신값을 불러와 비교한 뒤 다시 적용하세요.')
          setBlocked(true); setChecked(null); return
        } catch { /* 최신값을 못 읽으면 일반 실패로 알린다. */ }
      }
      fail(cause, true, input)
    } finally { setBusy(false) }
  }
  function finishApplied(latest: JsonWorkspace, preview: JsonPreview) {
    setTypeResult({ housingTypes: preview.housingTypes, housingTypeWarnings: preview.housingTypeWarnings }); setPublished(Boolean(preview.publishes)); setWorkspace(latest); setPhase('result'); setText(''); setBlocked(false); setUncertain(false); setChecked(null)
    setNotice(''); setRestoredNote('')
    // 적용을 확인한 뒤에만 초안을 지운다. 지우지 못해도 적용은 끝났고, 다음에 열면 이미 적용한 초안(stale)으로 보인다.
    void drafts.remove().catch(() => setDraftError('보정은 적용했지만 임시저장본을 지우지 못했습니다. 다음에 열 때 폐기하세요.'))
  }
  function fail(cause: unknown, saving: boolean, input?: JsonUpdateInput) {
    const rejected = cause instanceof ManagementError && cause.status >= 400 && cause.status < 500
    const uncertainSave = saving && !rejected
    setUncertain(uncertainSave); setBlocked(uncertainSave || cause instanceof ManagementError && cause.status === 409)
    // 서버 검증 메시지가 칸 경로로 오면 화면 칸 이름으로 바꿔 보여 주고 그 칸으로 옮긴다.
    const field = !rejected || !review ? null : Object.keys(cause.fields).length && input ? serverFieldsError(review, input, cause.fields) : serverFieldError(review, cause.message)
    setError(uncertainSave ? '저장 응답을 받지 못했습니다. 최신 저장값을 조회해 적용 여부를 확인한 뒤 다시 비교하세요.' : field ? field.message : cause instanceof Error ? cause.message : '보정 내용을 적용하지 못했습니다. 다시 시도하세요.')
    setMobileView('review'); setChecked(null)
    // 적용 전 검증에 걸리면 문제 항목으로 바로 이동한다.
    if (cause instanceof ReviewError && cause.reviewId) goTo(cause.reviewId)
    else if (field?.reviewId) goTo(field.reviewId)
  }
  function goTo(target: string) {
    const row = rowOfReviewId(target)
    if (row !== null) setSelectedRow(row)
    setSection(reviewSection(target)); setFilter('all'); setJump(previous => ({ id: target, nonce: (previous?.nonce ?? 0) + 1 }))
  }
  async function reload() {
    setBusy(true); setError('')
    try {
      const latest = await loadJsonWorkspace(id), [nextHousing, names] = await Promise.all([candidates(latest), complexLabels(latest)])
      const reflected = uncertain && lastInput.current !== null && correctionReflected(lastInput.current.correction.rows, latest.supply)
      if (review) setReview(rebaseReview(review, latest, nextHousing))
      setWorkspace(latest); setHousing(nextHousing); setComplexes(names); setBlocked(false); setUncertain(false); setPhase('review')
      setNotice(reflected ? '최신 저장값에 이 보정이 이미 반영돼 있습니다. 다시 적용하지 마세요. 결과를 확인한 뒤 화면을 닫으세요.'
        : '최신 저장값을 불러왔습니다. 수동 입력은 유지했습니다. 달라진 기존 값과 선택 항목을 비교한 뒤 다시 적용하세요.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : '최신값을 불러오지 못했습니다.') }
    finally { setBusy(false) }
  }
  if (!workspace) return <section className={styles.page}><h1>PDF로 공고 보정</h1>{error ? <div role="alert"><p>{error}</p><Button onClick={() => setAttempt(value => value + 1)}>다시 불러오기</Button></div> : <p role="status">공고와 연결 주택형을 불러오는 중…</p>}</section>
  if (workspace.detail.summary.deleted) return <section className={styles.page}><h1>휴지통의 공고입니다.</h1><p>공고를 복구한 뒤 보정하세요.</p><Link to={back}>공고 상세로 돌아가기</Link></section>
  const pending = review ? pendingItems(review, !supplyDraft, workspace ?? undefined) : []
  const changes = review ? pendingChanges(review) : []
  const counts = { changed: 0, new: 0 }
  if (review) for (const cell of applicableCells(review)) if (cell.extracted.state === 'changed' || cell.extracted.state === 'new') counts[cell.extracted.state] += 1
  const empty = `이 영역에는 ‘${filterLabels.find(([key]) => key === filter)?.[1]}’ 항목이 없습니다.`
  const fieldNeed = review ? fieldNeeds(review) : new Map<string, string>()
  const visibleIn = (cells: Review['metadata']) => filter === 'all' || cells.some(cell => cellMatches(cell, filter) || filter === 'pending' && pendingField(cell, fieldNeed))
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
    {supplyDraft ? <header className={styles.heading}><div><h2>PDF로 공급정보 채우기</h2><p>PDF와 대조한 공급정보를 편집표에 채웁니다. 저장 전까지 등록값은 유지됩니다.</p></div><Button data-design-system className={styles.secondaryButton} onClick={() => { if (!text.trim() || window.confirm('PDF 검토를 취소하고 편집표로 돌아갈까요?')) supplyDraft.onCancel() }}>편집표로 돌아가기</Button></header> : <header className={styles.heading}><div><Link to={back}>공고 상세로 돌아가기</Link><h1 ref={heading} tabIndex={-1}>PDF로 공고 보정</h1><p>#{id} · {workspace.detail.summary.name}</p>{review && phase !== 'result' && <p className={styles.progress} aria-label="검토 진행">{`공급행 ${review.rows.filter(row => !row.include || !pendingRow(row)).length}/${review.rows.length} 확인 · 미해결 ${pending.length}개`}{' · '}<DraftState status={drafts.status} onRetry={() => void drafts.saveNow()} onReload={() => void reloadDraft()} />{restoredNote && <>{' · '}<span role="status" className={styles.draftState}>{restoredNote}</span></>}</p>}{phase !== 'result' && <FailureTargets rows={failureRows} workspace={workspace} />}</div><ol aria-label="보정 진행"><li aria-current={phase === 'prepare' ? 'step' : undefined}>요청 준비</li><li aria-current={phase === 'review' ? 'step' : undefined}>비교·보정</li><li aria-current={phase === 'result' ? 'step' : undefined}>적용 결과</li></ol></header>}
    {error && <div role="alert" className={styles.error}><p>{error}</p>{blocked && <Button data-design-system onClick={() => void reload()} disabled={busy}>입력을 유지하고 최신값 비교</Button>}</div>}
    {notice && <div role="status" className={`${styles.notice} ${styles.dismissible}`}><p>{notice}</p><Button data-design-system className={styles.quietButton} onClick={() => setNotice('')}>알림 닫기</Button></div>}
    {draftError && <div role="alert" className={`${styles.error} ${styles.dismissible}`}><p>{draftError}</p><Button data-design-system className={styles.quietButton} onClick={() => setDraftError('')}>알림 닫기</Button></div>}
    {storedDraft && <DraftOffer draft={storedDraft} workspace={workspace} pdfHash={pdfHash} onRestore={restoreDraft} onDiscard={() => void discardDraft()} />}
    {phase === 'prepare' && <><div className={styles.mobileSwitch}><Button data-design-system aria-pressed={mobileView === 'pdf'} onClick={() => setMobileView('pdf')}>PDF 보기</Button><Button data-design-system aria-pressed={mobileView === 'review'} onClick={() => setMobileView('review')}>추출 보기</Button></div><div className={styles.workbench}>{pdfPane}
      <div className={styles.reviewPane} hidden={mobileView !== 'review'} data-pane="prepare"><RequestPreparation announcementName={workspace.detail.summary.name} pdf={pdf} extraction={extraction} onExtract={extract} parsed={parsed} checks={checks} saved={saved} samePdf={Boolean(saved && pdfHash && saved.pdfSha256 === pdfHash)} onLoadSaved={loadSaved} /></div>
    </div><PreparationFooter ready={Boolean(pdf) && parsed.ok && (extraction.status === 'done' || resumable)} onReview={start} /></>}
    {active && review && <><div className={styles.mobileSwitch}><Button data-design-system aria-pressed={mobileView === 'pdf'} onClick={() => setMobileView('pdf')}>PDF 보기</Button><Button data-design-system aria-pressed={mobileView === 'review'} onClick={() => setMobileView('review')}>검토표 보기</Button></div><div className={styles.workbench}>{pdfPane}
      <div ref={reviewPane} className={styles.reviewPane} hidden={mobileView !== 'review'} data-pane="review">
        {checks.some(check => check.matches === false) && <p className={styles.error}>PDF의 {checks.filter(check => check.matches === false).map(check => `${check.label}(${check.pdf})`).join('·')}이 현재 공고와 다릅니다. 이 공고의 PDF로 받은 응답인지 확인하세요.</p>}
        <div className={styles.reviewTop}>
          {<SectionTabs tabs={sectionTabs(review, section, selectedRow, pending, Boolean(supplyDraft))} onSelect={tab => { setSection(tab.section); if (tab.row !== undefined) setSelectedRow(tab.row) }} />}
          <div className={styles.filters} role="group" aria-label="검토 항목 필터">{filterLabels.map(([key, label]) => <Button data-design-system key={key} aria-pressed={filter === key} onClick={() => setFilter(key)}>{label}{key !== 'all' && ` ${key === 'pending' ? pending.length : counts[key]}`}</Button>)}</div>
        </div>
        {checked && <ApplySummaryPanel summary={summarizePreview(checked.preview, housing, workspace.supply.lhSupplyRows ?? [])} busy={busy} onApply={() => void confirmApply()} onBack={() => setChecked(null)} />}
        <fieldset disabled={busy || blocked} hidden={Boolean(checked)} className={styles.reviewFields}><div role="tabpanel" id="pdf-review-panel" aria-labelledby="pdf-review-tab-active">
          {section === 'supply' && <ReviewSupply savesHousingTypes={!supplyDraft} warnings={workerWarnings} editorHref={supplyDraft ? undefined : `/admin/announcements/${id}?${supplyParams}`} review={review} workspace={workspace} housing={housing} complexes={complexes} filter={filter} empty={empty} onChange={change} onEvidence={evidence} selected={selectedRow} onSelect={selectRow} onJump={goTo} />}
          {section === 'schedule' && <><h2>일정</h2><p>기존 단지별 일정은 각 ID를 유지하며 같은 보정값으로 갱신합니다.</p>{review.extraction.undatedSchedules.length > 0 && <p className={styles.hint}>날짜 없이 안내한 일정: {review.extraction.undatedSchedules.join(' · ')}</p>}{review.schedules.map((schedule, index) => scheduleMatches(schedule) && <section className={styles.supplyBlock} key={index} data-review-id={`schedule:${index}`} tabIndex={-1}><header><h3>{shown(schedule.cells.find(cell => cell.field === 'name')?.value)}</h3><label><input type="checkbox" checked={schedule.include} onChange={event => updateSchedule(index, { include: event.target.checked, reviewed: true })} />일정 {index + 1} 적용</label></header>
            {pendingSchedule(schedule) && <div className={styles.pending}><p><strong>확인 필요</strong> {schedule.proposal.reason}</p><div className={styles.actions}><Button data-design-system className={styles.secondaryButton} onClick={() => updateSchedule(index, { include: true, reviewed: true })}>{`일정 ${index + 1} 적용`}</Button><Button data-design-system className={styles.secondaryButton} onClick={() => updateSchedule(index, { include: false, reviewed: true })}>{`일정 ${index + 1} 적용 안 함`}</Button></div></div>}
            <p>{pendingSchedule(schedule) ? '' : schedule.proposal.reason || (schedule.proposal.existingIds.length ? '기존 일정 갱신' : '새 일정 추가')}{schedule.proposal.existingIds.map(existingId => { const event = workspace.events.find(item => item.id === existingId); return <small className={styles.scheduleBefore} key={existingId}>#{existingId} · {event?.complexName ?? '공고 전체'} · {shown(event?.data.name)} · {shown(event?.data.startAt)} ~ {shown(event?.data.endAt)}</small> })}</p>
            {schedule.include && <><fieldset><legend>일정 {index + 1}에서 수정할 기존 일정</legend>{workspace.events.filter(event => event.data.scheduleType === schedule.cells.find(cell => cell.field === 'scheduleType')?.value).map(event => <label key={event.id}><input type="checkbox" checked={schedule.proposal.existingIds.includes(event.id)} onChange={changeEvent => change({ ...review, schedules: review.schedules.map((item, at) => at === index ? { ...remapSchedule(review, index, changeEvent.target.checked ? [...item.proposal.existingIds, event.id] : item.proposal.existingIds.filter(value => value !== event.id), workspace), reviewed: true } : item) })} />#{event.id} · {event.complexName ?? '공고 전체'} · {shown(event.data.name)}</label>)}<p className={styles.hint}>선택한 기존 일정이 없으면 새 일정으로 추가합니다.</p></fieldset>
              <ReviewCells cells={schedule.cells} scope={`일정 ${index + 1}`} filter={filter} onChange={cells => updateSchedule(index, { cells })} onEvidence={evidence} /></>}
          </section>)}{!review.schedules.some(scheduleMatches) && <p className={styles.hint}>{empty}</p>}</>}
          {section === 'reception' && <><h2>접수처·기본정보</h2><p>임대유형·모집유형·기관·공고명은 현재값을 유지합니다. 접수처는 한 곳만 저장할 수 있으므로 온라인·현장 병행 여부를 확인하세요.</p>{visibleIn(review.reception) && <h3>접수처</h3>}<ReviewCells cells={review.reception} scope="접수처" needs={fieldNeed} filter={filter} onChange={reception => change({ ...review, reception })} onEvidence={evidence} />{visibleIn(review.metadata) && <h3>공고 기본 일정</h3>}<ReviewCells cells={review.metadata} scope="기본정보" needs={fieldNeed} filter={filter} onChange={metadata => change({ ...review, metadata })} onEvidence={evidence} />{!visibleIn(review.reception) && !visibleIn(review.metadata) && <p className={styles.hint}>{empty}</p>}</>}
        </div></fieldset>
      </div></div>
      {!supplyDraft && unconfirmed(workspace) && <ReleaseCheck value={release} disabled={busy || blocked} onChange={setRelease} />}<footer className={styles.footer}><div><strong>{changes.length}개 값 반영</strong><span>{changeSummary(changes)} · 선택하지 않은 값은 유지합니다.</span></div><div className={styles.actions}>{!supplyDraft && <><Button data-design-system className={styles.secondaryButton} disabled={!draftChecked || drafts.status.state === 'saving' || drafts.status.state === 'conflict'} onClick={() => void drafts.saveNow()}>임시저장</Button></>}{pending.length > 0 ? <Button data-design-system className={styles.pendingButton} onClick={nextPending}>{`미해결 ${pending.length}개 ↓`}</Button> : <span className={styles.pendingDone}>확인 필요 없음</span>}<Button data-design-system className={styles.secondaryButton} disabled={busy} onClick={() => { setPhase('prepare'); setError('') }}>응답 준비로 돌아가기</Button><Button data-design-system disabled={busy || blocked || !supplyDraft && Boolean(reasonProblem)} onClick={() => void apply()}>{busy ? '적용 중…' : supplyDraft ? '공급정보 편집표에 채우기' : '보정 내용 적용'}</Button></div></footer>
      {/* 근거는 추출할 때 자동으로 채운다. 원문 URL이 없을 때처럼 저장을 막는 값만 직접 입력하게 한다. */}
      {phase === 'review' && !supplyDraft && (reasonInput || reasonProblem) && <section className={styles.reason} aria-label="적용 근거 입력"><p role="status" className={styles.error}>{reasonProblem}</p><label>보정 이유<textarea value={reason} maxLength={2000} disabled={busy || blocked} onChange={event => { setReason(event.target.value) }} /></label><label>공식 근거 URL<input value={sourceUrl} maxLength={2048} disabled={busy || blocked} onChange={event => { setSourceUrl(event.target.value) }} /></label><label>근거 쪽<input value={sourcePage} maxLength={100} disabled={busy || blocked} onChange={event => { setSourcePage(event.target.value) }} /></label></section>}
    </>}
    {phase === 'result' && <section className={styles.result}><h2>{published ? '공고를 공개했습니다.' : '공고 보정을 적용했습니다.'}</h2><p>{unconfirmed(workspace) ? '선택한 변경을 저장했습니다. 이 공고는 아직 미확정이라 사용자에게 보이지 않습니다. 단지를 확인한 뒤 공개를 요청해 다시 적용하세요.' : published ? '사용자에게 공개했고 신규 공고 알림을 보냅니다. 공개 화면에서 공고와 단지의 현재 공급정보를 확인하세요.' : '선택한 변경을 저장했습니다. 공개 화면에서 공고와 단지의 현재 공급정보를 확인하세요.'}</p><p>보정 저장은 공고 전체 검토 완료와 다릅니다.{failureReturn ? ` ${Number.isSafeInteger(failureCount) && failureCount > 0 ? `이 공고의 오류 ${failureCount}건을 포함해 이 공고의 미해결 오류를` : '이 공고의 미해결 오류를'} 해결로 바꿨습니다.` : ''}</p><div className={styles.actions}><Link to={`/?announcementId=${id}`} target="_blank" rel="noreferrer">공개 공고 확인</Link><Link to={`/admin/announcements/${id}?${supplyParams}`}>저장된 공급정보 확인</Link><Link to={`/admin/announcements/${id}?${historyParams}`}>출처·수정 이력 보기</Link>{failureReturn && <Link to={failureReturn}>실패 검토로 돌아가기</Link>}</div>
      {(typeResult.housingTypes.length > 0 || typeResult.housingTypeWarnings.length > 0) && <section className={styles.renames} aria-labelledby="housing-type-result-title"><h3 id="housing-type-result-title">공고로 갱신한 주택형</h3><p className={styles.hint}>공급행에 연결한 주택형의 이름·공급면적·세대수(건설호수)를 이번 공고 값으로 맞췄습니다. 관리자가 직접 고친 값과 더 최근 공고의 값은 유지합니다.</p>
        {typeResult.housingTypes.length > 0 && <ul>{typeResult.housingTypes.map(item => <li key={`${item.rowKey}:${item.housingTypeId ?? item.after.name}`}>{item.housingTypeId === null ? `새 주택형 「${item.after.name}」` : `#${item.housingTypeId} 「${item.typeName}」`} → {typeValuesLabel(item.after)}</li>)}</ul>}
        {typeResult.housingTypeWarnings.length > 0 && <ul aria-label="유지한 주택형 값">{typeResult.housingTypeWarnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul>}</section>}
    </section>}
  </section>
}

function typeValuesLabel(values: JsonPreview['housingTypes'][number]['after']) {
  const won = (value: number | null | undefined) => typeof value === 'number' ? `${value.toLocaleString('ko-KR')}원` : '미확인'
  return [`이름 ${values.name}`, `공급면적 ${values.supplyArea ?? '미확인'}㎡`, `세대수 ${values.householdCount ?? '미확인'}`,
    ...(values.basicDeposit != null || values.basicMonthlyRent != null ? [`기본 보증금 ${won(values.basicDeposit)} · 월 ${won(values.basicMonthlyRent)}`] : [])].join(' · ')
}

const changeSections: PendingChange['section'][] = ['기본정보', '접수처', '공급정보', '일정']
function changeSummary(changes: PendingChange[]) {
  return changeSections.map(section => `${section} ${changes.filter(change => change.section === section).length}`).join(' · ')
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

function DraftState({ status, onRetry, onReload }: { status: DraftStatus; onRetry: () => void; onReload: () => void }) {
  const time = (value: string) => new Date(value).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
  if (status.state === 'conflict') return <span role="alert" className={styles.draftFailed}>임시저장 멈춤: 다른 탭/세션에서 더 최신 초안이 저장됐습니다. 이 화면의 편집은 그대로 남아 있습니다. <Button data-design-system className={styles.quietButton} onClick={onReload}>최신 초안 불러오기</Button></span>
  if (status.state === 'failed') return <span className={styles.draftFailed}>임시저장 실패 <Button data-design-system className={styles.quietButton} onClick={onRetry}>다시 시도</Button></span>
  const label = status.state === 'saving' ? '저장 중…' : status.state === 'saved' ? `저장됨 ${time(status.at)}` : status.state === 'pending' ? '저장 대기' : '임시저장 전'
  return <span className={styles.draftState} aria-label={`임시저장 상태: ${label}`}>{label}</span>
}

/** 저장된 초안을 복원할지 폐기할지 고르게 한다. 초안 뒤 공고가 바뀌었거나 다른 PDF였으면 무엇이 다른지 먼저 보여 준다. */
function DraftOffer({ draft, workspace, pdfHash, onRestore, onDiscard }: { draft: CorrectionDraft; workspace: JsonWorkspace; pdfHash: string; onRestore: () => void; onDiscard: () => void }) {
  const changes = draft.stale ? baseRowChanges(draft.payload.baseRows, workspace.supply.rows) : []
  const otherPdf = Boolean(draft.pdfSha256 && pdfHash && draft.pdfSha256 !== pdfHash)
  const at = new Date(draft.updatedAt).toLocaleString('ko-KR')
  return <section className={styles.draftOffer} role="region" aria-label="임시저장한 작업">
    <p><strong>임시저장한 작업이 있습니다.</strong> {at} 저장 · {draft.payload.pdfName || 'PDF 이름 없음'} · 공급행 {draft.payload.review.rows.length}개</p>
    {draft.stale && <div className={styles.warningNote}><p><strong>경고</strong> 초안을 저장한 뒤 공고 데이터가 바뀌었습니다{changes.length ? '.' : '(공급행 밖의 기본정보·일정·원천이 바뀌었거나 이미 적용한 초안입니다).'} 복원하면 최신 기존 값에 맞춰 다시 비교합니다.</p>
      {changes.length > 0 && <ul aria-label="초안 뒤 바뀐 공급행">{changes.map(change => <li key={change}>{change}</li>)}</ul>}</div>}
    {otherPdf && <div className={styles.warningNote}><p><strong>경고</strong> {`초안은 지금 열린 PDF와 다른 파일(SHA-256 ${draft.pdfSha256!.slice(0, 8)}…)로 작업했습니다. 근거 쪽 번호가 맞지 않을 수 있습니다.`}</p></div>}
    <div className={styles.actions}><Button data-design-system onClick={onRestore}>임시저장본 복원</Button><Button data-design-system className={styles.secondaryButton} onClick={onDiscard}>임시저장본 폐기</Button></div>
  </section>
}

/** GH·SH 원천으로 만든 공고는 관리자가 PDF 보정에서 단지를 확인하고 공개를 요청하기 전까지 사용자에게 보이지 않는다. */
function unconfirmed(workspace: JsonWorkspace | null) {
  return workspace?.detail.summary.announcement?.releaseStatus === 'UNCONFIRMED'
}

function ReleaseCheck({ value, disabled, onChange }: { value: ReleaseChoice; disabled: boolean; onChange: (next: ReleaseChoice) => void }) {
  return <section className={styles.reason} aria-label="공개 전 확인"><h2>공개 전 확인</h2>
    <p className={styles.hint}>미확정 공고라 사용자에게 보이지 않습니다. 비어 있는 공급·모집 유형을 정하고, 공급행이 가리키는 단지가 맞는지 확인했으면 공개를 요청하세요. 공개하면 신규 공고 알림이 나갑니다.</p>
    <label>공급 유형<select value={value.rentalType} disabled={disabled} onChange={event => onChange({ ...value, rentalType: event.target.value })}><option value="">현재값 유지</option>{rentals.map(type => <option key={type} value={type}>{labels[type] ?? type}</option>)}</select></label>
    <label>모집 유형<select value={value.recruitmentType} disabled={disabled} onChange={event => onChange({ ...value, recruitmentType: event.target.value })}><option value="">현재값 유지</option>{['NEW', 'WAITLIST', 'ETC'].map(type => <option key={type} value={type}>{labels[type] ?? type}</option>)}</select></label>
    <label><input type="checkbox" checked={value.publish} disabled={disabled} onChange={event => onChange({ ...value, publish: event.target.checked })} />단지를 확인했고 이 보정과 함께 공개합니다</label>
  </section>
}
