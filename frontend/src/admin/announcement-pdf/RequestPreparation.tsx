import { useEffect, useState, type DragEvent, type ReactNode } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import type { readExtraction } from './extraction'
import type { AnnouncementCheck } from './matching'
import type { ExtractionState } from './extractionApi'
import { PdfFilePicker } from './PdfFilePicker'
import styles from './PdfCorrection.module.css'

type Props = {
  attachments: NonNullable<JsonWorkspace['detail']['attachments']>
  attachmentId: string
  onAttachment: (id: string) => void
  localPdf: boolean
  onLocalFile: (file: File) => void
  pdf: { url: string; name: string } | null
  pdfError: string
  preview: ReactNode
  extraction: ExtractionState
  onExtract: () => void
  parsed: ReturnType<typeof readExtraction>
  checks: AnnouncementCheck[]
  onReview: () => void
}

const isPdfFile = (file: File) => file.type === 'application/pdf' || /\.pdf$/i.test(file.name)

export function RequestPreparation({ attachments, attachmentId, onAttachment, localPdf, onLocalFile, pdf, pdfError, preview, extraction, onExtract, parsed, checks, onReview }: Props) {
  const [error, setError] = useState('')
  const [dragging, setDragging] = useState(false)

  function choose(file: File | undefined) {
    if (!file) return
    if (!isPdfFile(file)) { setError('PDF 파일만 고를 수 있습니다.'); return }
    setError(''); onLocalFile(file)
  }
  function drop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault(); setDragging(false)
    choose(event.dataTransfer.files[0])
  }

  return <div className={styles.prepare}>
    <section aria-labelledby="pdf-source-title" className={styles.sourcePane}>
      <header><h2 id="pdf-source-title">공고문 PDF</h2><p>PDF를 고르거나 아래 영역에 끌어다 놓은 뒤 값을 추출합니다.</p></header>
      <div className={styles.sourceFile}>
        {localPdf && pdf ? <strong title={pdf.name}>내 PC · {pdf.name}</strong>
          : attachments.length ? <PdfFilePicker files={attachments} value={attachmentId} onChange={onAttachment} />
          : <strong>연결된 PDF가 없습니다. 원문에서 받은 PDF를 고르세요.</strong>}
        <div className={styles.sourceActions}>
          <label className={styles.downloadButton}>내 PC에서 PDF 고르기<input type="file" accept="application/pdf,.pdf" className={styles.srOnly} onChange={event => {
            const file = event.target.files?.[0]; event.target.value = ''
            choose(file)
          }} /></label>
        </div>
        {(pdfError || error) && <p role="alert" className={styles.error}>{error || pdfError}</p>}
      </div>
      <div className={styles.dropZone} data-dragging={dragging} aria-label="PDF 미리보기와 끌어다 놓기 영역" role="region"
        onDragOver={event => { event.preventDefault(); setDragging(true) }} onDragLeave={() => setDragging(false)} onDrop={drop}>
        {preview || <p className={styles.hint}>PDF를 고르거나 여기에 끌어다 놓으세요.</p>}
      </div>
    </section>
    <section aria-labelledby="pdf-extraction-title" className={styles.extractionPane}>
      <header><h2 id="pdf-extraction-title">자동 추출</h2><p>추출한 값은 다음 화면에서 기존 값과 비교하고 고칠 수 있습니다. 확인한 뒤에만 저장합니다.</p></header>
      <ExtractionStatus extraction={extraction} hasPdf={Boolean(pdf)} parsed={parsed} checks={checks} onExtract={onExtract} />
      <footer className={styles.prepareAction}><Button data-design-system disabled={extraction.status !== 'done' || !parsed.ok || !pdf} onClick={onReview}>추출 결과 검토하기</Button></footer>
    </section>
  </div>
}

function ExtractionStatus({ extraction, hasPdf, parsed, checks, onExtract }: { extraction: ExtractionState; hasPdf: boolean; parsed: ReturnType<typeof readExtraction>; checks: AnnouncementCheck[]; onExtract: () => void }) {
  const retry = <Button data-design-system className={styles.secondaryButton} onClick={onExtract}>다시 추출</Button>
  if (extraction.status === 'idle') return hasPdf
    ? <div className={styles.extractionStart}><p className={styles.hint}>PDF 확인이 끝나면 추출을 시작하세요. 보통 10~40초 걸립니다.</p><Button data-design-system onClick={onExtract}>PDF에서 값 추출</Button></div>
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
    <p className={styles.hint}>전체 {result.totalPages}쪽 중 {result.pages.join(', ')}쪽을 읽었습니다.{tokens && ` 토큰 ${tokens}`}</p>
    {result.warnings.length > 0 && <section aria-labelledby="pdf-extraction-warnings"><h3 id="pdf-extraction-warnings">확인할 점 {result.warnings.length}개</h3><ul>{result.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul></section>}
    {retry}
  </div>
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
