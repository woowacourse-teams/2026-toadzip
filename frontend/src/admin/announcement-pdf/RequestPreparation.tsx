import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import type { readExtraction } from './extraction'
import type { AnnouncementCheck } from './matching'
import { PdfFilePicker } from './PdfFilePicker'
import prompt from './announcementUpdatePrompt.v5.txt?raw'
import styles from './PdfCorrection.module.css'

type Props = {
  attachments: NonNullable<JsonWorkspace['detail']['attachments']>
  attachmentId: string
  onAttachment: (id: string) => void
  localPdf: boolean
  onLocalFile: (file: File) => void
  pdf: { url: string; name: string } | null
  pdfError: string
  text: string
  onText: (text: string) => void
  parsed: ReturnType<typeof readExtraction>
  checks: AnnouncementCheck[]
  onReview: () => void
}

export function RequestPreparation({ attachments, attachmentId, onAttachment, localPdf, onLocalFile, pdf, pdfError, text, onText, parsed, checks, onReview }: Props) {
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const mismatched = checks.some(check => check.matches === false)
  const identity = checks.map(check => check.matches === null ? `${check.label} 확인 불가` : check.matches ? `${check.label} 일치` : `${check.label} 다름(PDF ${check.pdf})`).join(' · ')

  async function copyPrompt() {
    setMessage(''); setError('')
    try { await navigator.clipboard.writeText(prompt); setMessage('프롬프트를 복사했습니다.') }
    catch { setError('프롬프트를 복사하지 못했습니다. 아래 내용을 직접 선택해 복사하세요.') }
  }

  function prepareChatGpt() {
    if (!pdf) return
    setMessage(''); setError('')
    // Start both browser actions in the click gesture so a new tab does not
    // depend on an asynchronous clipboard operation retaining activation.
    try {
      const copied = navigator.clipboard.writeText(prompt)
      window.open('https://chatgpt.com/', '_blank', 'noopener,noreferrer')
      void copied.then(() => {
        if (localPdf) { setMessage('프롬프트를 복사했습니다. ChatGPT에서 고른 PDF를 첨부하고 프롬프트를 붙여넣으세요.'); return }
        const link = document.createElement('a')
        link.href = pdf.url; link.download = pdf.name
        document.body.append(link); link.click(); link.remove()
        setMessage('PDF 다운로드를 시작하고 프롬프트를 복사했습니다. ChatGPT에서 PDF를 첨부하고 프롬프트를 붙여넣으세요.')
      }).catch(() => setError('프롬프트를 복사하지 못했습니다. 아래 내용을 직접 복사한 뒤 다시 준비하세요.'))
    } catch { setError('프롬프트를 복사하지 못했습니다. 아래 내용을 직접 선택해 복사하세요.') }
  }

  return <div className={styles.prepare}>
    <section aria-labelledby="pdf-request-title">
      <header><h2 id="pdf-request-title">ChatGPT에 요청하기</h2><p>공고문 PDF와 아래 프롬프트를 사용합니다.</p></header>
      <div className={styles.sourceFile}>
        {localPdf && pdf ? <strong title={pdf.name}>내 PC · {pdf.name}</strong>
          : attachments.length ? <PdfFilePicker files={attachments} value={attachmentId} onChange={onAttachment} />
          : <strong>연결된 PDF가 없습니다. 원문에서 받은 PDF를 고르세요.</strong>}
        <div className={styles.sourceActions}>
          {pdf && !localPdf && <a className={styles.downloadButton} href={pdf.url} download={pdf.name}>PDF 다운로드</a>}
          {(!attachments.length || localPdf) && <label className={styles.downloadButton}>내 PC에서 PDF 고르기<input type="file" accept="application/pdf,.pdf" className={styles.srOnly} onChange={event => {
            const file = event.target.files?.[0]; event.target.value = ''
            if (!file) return
            if (file.type !== 'application/pdf' && !/\.pdf$/i.test(file.name)) { setError('PDF 파일만 고를 수 있습니다.'); return }
            setError(''); onLocalFile(file)
          }} /></label>}
        </div>
        {pdfError && <p role="alert">{pdfError}</p>}
      </div>
      <section className={styles.promptPanel} aria-labelledby="extraction-prompt-title">
        <header><h3 id="extraction-prompt-title">추출 프롬프트 <span>v5</span></h3><Button data-design-system className={`${styles.secondaryButton} ${styles.copyButton}`} onClick={() => void copyPrompt()}>{message ? '프롬프트 복사됨' : '프롬프트만 복사'}</Button></header>
        <pre className={styles.prompt} tabIndex={0} aria-label="프롬프트 내용">{prompt}</pre>
      </section>
      <span role="status" className={styles.srOnly}>{message}</span>
      <div className={styles.prepareError}>{error && <p role="alert" className={styles.error}>{error}</p>}</div>
      <footer className={styles.prepareAction}><p className={styles.hint}>ChatGPT 새 대화에서 브라우저 다운로드 목록(오른쪽 위 ↓)의 PDF를 입력창으로 끌어 놓고 ⌘V로 프롬프트를 붙여넣으세요.</p><Button data-design-system disabled={!pdf} onClick={prepareChatGpt}>{localPdf ? '프롬프트 복사해 ChatGPT 열기' : 'PDF 받고 프롬프트 복사해 ChatGPT 열기'}</Button></footer>
    </section>
    <section aria-labelledby="pdf-response-title">
      <header><h2 id="pdf-response-title">응답 가져오기</h2><p>ChatGPT가 답하면 전체 내용을 그대로 붙여넣으세요.</p></header>
      <section className={`${styles.promptPanel} ${styles.responsePanel}`}><header><label htmlFor="chatgpt-response">ChatGPT 응답</label></header><textarea id="chatgpt-response" value={text} spellCheck={false} placeholder="JSON 응답 전체를 붙여넣으세요." onChange={event => onText(event.target.value)} /></section>
      <div className={styles.prepareFeedback}>{text.trim() && <div role={parsed.ok && !mismatched ? 'status' : 'alert'} className={parsed.ok && !mismatched ? `${styles.notice} ${styles.parseSuccess}` : `${styles.error} ${styles.parseError}`}>{parsed.ok ? <p title={identity}>{mismatched ? `현재 공고와 다른 PDF의 응답일 수 있습니다 · ${identity}` : `공급행 ${parsed.value.supplyRows.length}개 · 임대조건 ${parsed.value.supplyRows.reduce((count, row) => count + row.targets.length, 0)}개 · 일정 ${parsed.value.schedules.length}개 · 미확인 ${parsed.value.unresolved.length}개 · ${identity}`}</p> : <p title={parsed.error}>{parsed.error}</p>}</div>}</div>
      <footer className={styles.prepareAction}><p className={styles.hint}>가져온 값은 다음 화면에서 기존 값과 비교하고 고칠 수 있습니다.</p><Button data-design-system disabled={!parsed.ok || !pdf} onClick={onReview}>응답 검토하기</Button></footer>
    </section>
  </div>
}
