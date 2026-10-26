import { useEffect, useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import type { readExtraction } from './extraction'
import type { ExtractionState, SavedExtraction } from './extractionApi'
import type { AnnouncementCheck } from './matching'
import { PdfFilePicker } from './PdfFilePicker'
import styles from './PdfCorrection.module.css'

type SourceProps = {
  attachments: NonNullable<JsonWorkspace['detail']['attachments']>
  attachmentId: string
  onAttachment: (id: string) => void
  localPdf: boolean
  onLocalFile: (file: File | undefined) => void
  pdf: { url: string; name: string } | null
  pdfError: string
}
type Props = {
  announcementName: string
  pdf: { url: string; name: string } | null
  extraction: ExtractionState
  onExtract: () => void
  parsed: ReturnType<typeof readExtraction>
  checks: AnnouncementCheck[]
  saved: SavedExtraction | null
  /** 저장한 결과가 지금 고른 PDF와 같은 파일에서 나왔는지 */
  samePdf: boolean
  onLoadSaved: () => void
}

/** 준비 단계 PDF 패널 머리. 첨부 PDF 드롭다운과 내 PC 고르기를 PDF 바로 위에 둔다. */
export function PdfSource({ attachments, attachmentId, onAttachment, localPdf, onLocalFile, pdf, pdfError }: SourceProps) {
  return <div className={styles.sourceFile}>
    {localPdf && pdf ? <strong title={pdf.name}>내 PC · {pdf.name}</strong>
      : attachments.length ? <PdfFilePicker files={attachments} value={attachmentId} onChange={onAttachment} />
      : <strong>연결된 PDF가 없습니다</strong>}
    <div className={styles.sourceActions}>
      <label className={styles.downloadButton}>내 PC에서 PDF 고르기<input type="file" accept="application/pdf,.pdf" className={styles.srOnly} onChange={event => {
        const file = event.target.files?.[0]; event.target.value = ''
        onLocalFile(file)
      }} /></label>
    </div>
    {pdfError && <p role="alert" className={styles.error}>{pdfError}</p>}
  </div>
}

/** PDF 보정 준비 단계의 오른쪽 패널. 왼쪽 PDF를 보며 제목 확인 → 추출 순서로 진행한다. */
export function RequestPreparation({ announcementName, pdf, extraction, onExtract, parsed, checks, saved, samePdf, onLoadSaved }: Props) {
  return <div className={styles.preparation}>
    <header><h2 id="pdf-extraction-title">자동 추출</h2><p>PDF 영역에서 PDF를 고르거나 끌어다 놓고 순서대로 진행하세요. 추출한 값은 다음 화면에서 기존 값과 비교하고, 확인한 뒤에만 저장합니다.</p></header>
    <section aria-labelledby="pdf-title-check" className={styles.prepareStep}>
      <h3 id="pdf-title-check">1. 제목 확인</h3>
      <p className={styles.hint}>PDF 첫 쪽의 공고 제목이 이 공고와 같은지 확인하세요. 다른 공고의 PDF로 추출하면 검토 화면에서 값이 맞지 않습니다.</p>
      <p className={styles.announcementName}>{announcementName}</p>
    </section>
    <section aria-labelledby="pdf-extract-step" className={styles.prepareStep}>
      <h3 id="pdf-extract-step">2. 추출</h3>
      <ExtractionStatus extraction={extraction} hasPdf={Boolean(pdf)} parsed={parsed} checks={checks} onExtract={onExtract} samePdf={samePdf} />
      {saved && pdf && extraction.status !== 'running' && extraction.status !== 'done' && <SavedResult saved={saved} samePdf={samePdf} onLoad={onLoadSaved} />}
    </section>
  </div>
}

/** 검토 단계와 같은 자리의 하단 버튼 줄. 추출이 끝나야 검토 버튼을 누를 수 있다. */
export function PreparationFooter({ ready, onReview }: { ready: boolean; onReview: () => void }) {
  return <footer className={`${styles.footer} ${styles.prepareFooter}`}>
    <div><strong>{ready ? '추출을 마쳤습니다' : '추출 전입니다'}</strong><span>{ready ? '다음 화면에서 기존 값과 비교하고 반영할 값을 고릅니다.' : 'PDF 제목을 확인하고 추출하면 검토할 수 있습니다.'}</span></div>
    <Button data-design-system disabled={!ready} onClick={onReview}>추출 결과 검토하기</Button>
  </footer>
}

function ExtractionStatus({ extraction, hasPdf, parsed, checks, onExtract, samePdf }: { extraction: ExtractionState; hasPdf: boolean; parsed: ReturnType<typeof readExtraction>; checks: AnnouncementCheck[]; onExtract: () => void; samePdf: boolean }) {
  const retry = <Button data-design-system className={styles.secondaryButton} onClick={onExtract}>다시 추출</Button>
  if (extraction.status === 'idle') return hasPdf
    ? <div className={styles.extractionStart}><p className={styles.hint}>제목을 확인했으면 추출을 시작하세요. 보통 10~40초 걸립니다.</p><Button data-design-system className={samePdf ? styles.secondaryButton : undefined} onClick={onExtract}>PDF에서 값 추출</Button></div>
    : <p role="status" className={styles.hint}>PDF를 고르면 추출할 수 있습니다.</p>
  if (extraction.status === 'running') return <Elapsed startedAt={extraction.startedAt} />
  if (extraction.status === 'failed') return <div role="alert" className={styles.error}><p>{extraction.message}</p>{retry}</div>
  if (!parsed.ok) return <div role="alert" className={styles.error}><p>{parsed.error}</p>{retry}</div>
  const { result } = extraction
  const mismatched = checks.some(check => check.matches === false)
  const identity = checks.map(check => check.matches === null ? `${check.label} 확인 불가` : check.matches ? `${check.label} 일치` : `${check.label} 다름(PDF ${check.pdf})`).join(' · ')
  const tokens = [['입력', result.usage.promptTokens], ['출력', result.usage.outputTokens], ['생각', result.usage.thinkingTokens]]
    .filter(([, value]) => value !== null).map(([label, value]) => `${label} ${(value as number).toLocaleString('ko-KR')}`).join(' · ')
  return <div className={styles.extractionResult}>
    <p role={mismatched ? 'alert' : 'status'} className={mismatched ? styles.error : styles.notice}>{mismatched ? `현재 공고와 다른 PDF일 수 있습니다 · ${identity}`
      : `공급행 ${parsed.value.supplyRows.length}개 · 임대조건 ${parsed.value.supplyRows.reduce((sum, row) => sum + row.targets.length, 0)}개 · 일정 ${parsed.value.schedules.length}개 · 미확인 ${parsed.value.unresolved.length}개 · ${identity}`}</p>
    {extraction.saved && <p className={styles.hint}>{savedAt(extraction.saved.createdAt)}에 {extraction.saved.fileName}에서 추출해 저장한 결과를 불러왔습니다.</p>}
    <p className={styles.hint}>전체 {result.totalPages}쪽 중 {result.pages.join(', ')}쪽을 읽었습니다.{tokens && ` 토큰 ${tokens}`}</p>
    {result.warnings.length > 0 && <section aria-labelledby="pdf-extraction-warnings"><h4 id="pdf-extraction-warnings">확인할 점 {result.warnings.length}개</h4><ul>{result.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul></section>}
    {retry}
  </div>
}

/** 저장한 이전 결과. 같은 PDF면 다시 추출하지 않고 불러오도록 먼저 권한다. */
function SavedResult({ saved, samePdf, onLoad }: { saved: SavedExtraction; samePdf: boolean; onLoad: () => void }) {
  return <section aria-labelledby="pdf-saved-result" className={styles.savedResult}>
    <h4 id="pdf-saved-result">이전 결과</h4>
    <p className={styles.hint}>{savedAt(saved.createdAt)} · {saved.fileName} · {samePdf ? '지금 PDF와 같은 파일입니다. 불러오면 토큰을 쓰지 않습니다.' : '지금 고른 PDF와 다른 파일에서 추출했습니다.'}</p>
    <Button data-design-system className={samePdf ? undefined : styles.secondaryButton} onClick={onLoad}>이전 결과 불러오기</Button>
  </section>
}

function savedAt(value: string) {
  return new Date(value).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function Elapsed({ startedAt }: { startedAt: number }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(timer) }, [])
  const seconds = Math.max(0, Math.floor((now - startedAt) / 1000))
  return <div className={styles.extractionProgress}>
    <p role="status">PDF에서 값을 추출하는 중… {seconds}초 · 보통 10~40초 걸립니다.</p>
    <div role="progressbar" aria-label="추출 진행" className={styles.loadingBar}><span /></div>
  </div>
}
