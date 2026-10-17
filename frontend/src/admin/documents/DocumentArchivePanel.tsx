import { useEffect, useRef, useState } from 'react'
import type { ManagementResource } from '../management/managementContract'
import { archiveDocument, documentDownloadUrl, listArchivedDocuments, type ArchivedDocument } from './documentArchiveApi'
import { safeDocumentUrl } from './documentReview'
import styles from './DocumentReview.module.css'

export function DocumentArchivePanel({ resource, id, file, sourceUrl, onBusyChange }: {
  resource: ManagementResource; id: number; file: File | null; sourceUrl: string; onBusyChange: (busy: boolean) => void
}) {
  const [documents, setDocuments] = useState<ArchivedDocument[] | null>(null)
  const [page, setPage] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => { request.current?.abort(); onBusyChange(false) }, [resource, id, onBusyChange])
  async function run(save: boolean, pageNumber = 0) {
    if (busy || save && (!file || !safeDocumentUrl(sourceUrl))) return
    const controller = new AbortController(); request.current = controller; setBusy(true); onBusyChange(true); setError(''); setNotice('')
    try {
      if (save && file) {
        await archiveDocument(resource, id, file, sourceUrl, controller.signal)
        if (!controller.signal.aborted) setNotice('원본 PDF를 서버에 보관했습니다. 같은 파일의 중복 보관은 기존 원본을 유지합니다.')
      }
      const next = await listArchivedDocuments(resource, id, pageNumber, controller.signal)
      if (!controller.signal.aborted) { setDocuments(next); setPage(pageNumber) }
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '원본 보관 요청을 처리하지 못했습니다.') }
    finally { if (!controller.signal.aborted) { setBusy(false); onBusyChange(false) } }
  }
  return <details className={styles.card}><summary>관리자 공유 원본 보관함</summary>
    <p className={styles.hint}>공식 PDF 원본을 서버 DB에 보관합니다. 관리자만 다운로드할 수 있으며 파일 해시·출처·보관자·시각을 기록합니다. 원본은 덮어쓰지 않습니다. 브라우저 작업 보관과 별개입니다.</p>
    <div className={styles.actions}><button disabled={busy || !file || !safeDocumentUrl(sourceUrl)} onClick={() => void run(true)}>선택 PDF 원본 서버 보관</button><button disabled={busy} onClick={() => void run(false)}>서버 보관 원본 보기</button></div>
    {busy ? <p role="status">원본 보관함 처리 중…</p> : null}{error ? <p role="alert">{error}</p> : null}{notice ? <p role="status">{notice}</p> : null}
    {documents ? <>{!documents.length ? <p>이 페이지에 보관된 원본이 없습니다.</p> : <ul>{documents.map(document => <li key={document.sha256}>
      <a href={documentDownloadUrl(resource, id, document.sha256)} download>{document.fileName} 원본 다운로드</a><p>{(document.byteCount / 1024 / 1024).toFixed(2)}MB · {document.actor} · {new Date(document.createdAt).toLocaleString('ko-KR')}</p><small>SHA-256 {document.sha256}</small>
    </li>)}</ul>}<div className={styles.actions}><button disabled={busy || page === 0} onClick={() => void run(false, page - 1)}>이전 보관 원본</button><button disabled={busy || documents.length < 20} onClick={() => void run(false, page + 1)}>다음 보관 원본</button></div></> : null}
  </details>
}
