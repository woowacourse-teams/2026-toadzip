import { lazy, Suspense, useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Button } from '../../design-system/components/Button.tsx'
import { attachmentErrorMessage, hasAttachmentUrl, loadAnnouncementAttachment } from '../api/announcementAttachments.ts'
import type { HousingAnnouncementDetailAttachment } from './HousingAnnouncementDetailPanel.tsx'
import { DetailCloseButton } from './DetailPrimitives.tsx'
import { PdfPreviewBoundary } from './PdfPreviewBoundary.tsx'
import styles from './AnnouncementAttachments.module.css'

const PdfDocumentPreview = lazy(() => import('./PdfDocumentPreview.tsx'))

interface AttachmentsProps {
  readonly announcementId: string
  readonly attachments: readonly HousingAnnouncementDetailAttachment[]
}

export function AttachmentList({ announcementId, attachments }: AttachmentsProps) {
  const [expanded, setExpanded] = useState(false)
  const titleId = useId()
  const listId = useId()
  if (attachments.length === 0) return null
  return (
    <section className={styles.accordion} aria-labelledby={titleId}>
      <h3 id={titleId}>
        <button type="button" aria-expanded={expanded} aria-controls={listId} onClick={() => setExpanded(!expanded)}>
          <span>첨부파일 <small>{attachments.length}개</small></span>
          <span className={styles.chevron} data-expanded={expanded} aria-hidden="true">⌄</span>
        </button>
      </h3>
      <div id={listId} hidden={!expanded}>
        {expanded && <ul className={styles.downloadList}>
          {attachments.map((attachment) => <li key={attachment.attachmentId}>
            <div><small>{attachment.fileTypeLabel}</small><strong>{attachment.fileName ?? '이름 없는 파일'}</strong></div>
            <DownloadButton announcementId={announcementId} attachment={attachment} />
          </li>)}
        </ul>}
      </div>
    </section>
  )
}

export function AttachmentDialog({ announcementId, attachments, onClose }: AttachmentsProps & { readonly onClose: () => void }) {
  const [selectedId, setSelectedId] = useState(attachments.length === 1 ? attachments[0]?.attachmentId : undefined)
  const selected = attachments.find((attachment) => attachment.attachmentId === selectedId)
  const dialogRef = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const trigger = document.activeElement
    const dialog = dialogRef.current
    dialog?.showModal()
    return () => {
      dialog?.close()
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus({ preventScroll: true })
    }
  }, [])

  return createPortal(
    <dialog ref={dialogRef} className={styles.dialog} aria-labelledby={titleId}
      onCancel={(event) => { event.preventDefault(); onClose() }}
      onKeyDown={(event) => event.stopPropagation()}
      onClick={(event) => {
        if (event.target !== event.currentTarget) return
        const rect = event.currentTarget.getBoundingClientRect()
        if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) onClose()
      }}>
      <header className={styles.header}>
        <div><h2 id={titleId}>첨부파일</h2><p>{selected?.fileName ?? '확인할 파일을 선택해 주세요.'}</p></div>
        <DetailCloseButton label="첨부파일 닫기" onClose={onClose} />
      </header>
      {selected ? <>
        <div className={styles.toolbar}>
          {attachments.length > 1 && <Button onClick={() => {
            setSelectedId(undefined)
            dialogRef.current?.querySelector<HTMLButtonElement>('header button')?.focus()
          }}>파일 목록</Button>}
          <span>{selected.fileTypeLabel}</span>
          <DownloadButton key={selected.attachmentId} announcementId={announcementId} attachment={selected} />
        </div>
        <AttachmentPreview key={selected.attachmentId} announcementId={announcementId} attachment={selected} />
      </> : <ul className={styles.choices} aria-label="확인할 첨부파일">
        {attachments.map((attachment) => <li key={attachment.attachmentId}>
          <button type="button" disabled={!hasAttachmentUrl(attachment)} onClick={() => {
            setSelectedId(attachment.attachmentId)
            dialogRef.current?.querySelector<HTMLButtonElement>('header button')?.focus()
          }}>
            <span className={styles.fileIcon} aria-hidden="true">▤</span>
            <span><small>{attachment.fileTypeLabel}</small><strong>{attachment.fileName ?? '이름 없는 파일'}</strong>
              {!hasAttachmentUrl(attachment) && <small>파일을 사용할 수 없습니다.</small>}</span>
            <span aria-hidden="true">›</span>
          </button>
        </li>)}
      </ul>}
    </dialog>, document.body,
  )
}

type PreviewState = { readonly type: 'loading' } | { readonly type: 'ready'; readonly url: string }
  | { readonly type: 'error'; readonly message: string }

function AttachmentPreview({ announcementId, attachment }: {
  readonly announcementId: string
  readonly attachment: HousingAnnouncementDetailAttachment
}) {
  const [state, setState] = useState<PreviewState>({ type: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const extension = attachment.fileName?.trim().match(/\.([a-z0-9]+)$/i)?.[1]?.toLowerCase()
  const unsupported = extension !== undefined && extension !== 'pdf'
  const available = hasAttachmentUrl(attachment)

  useEffect(() => {
    if (unsupported || !available) return
    const controller = new AbortController()
    let url: string | undefined
    loadAnnouncementAttachment(announcementId, attachment.attachmentId, false, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return
        url = URL.createObjectURL(blob)
        setState({ type: 'ready', url })
      }).catch((error: unknown) => {
        if (!controller.signal.aborted) setState({ type: 'error', message: attachmentErrorMessage(error) })
      })
    return () => {
      controller.abort()
      if (url) URL.revokeObjectURL(url)
    }
  }, [announcementId, attachment.attachmentId, available, attempt, unsupported])

  if (!hasAttachmentUrl(attachment)) return <p className={styles.message}>파일을 사용할 수 없습니다. 공고 원문에서 확인해 주세요.</p>
  if (unsupported) return <p className={styles.message}>이 파일은 미리보기를 지원하지 않습니다. 다운로드해서 확인해 주세요.</p>
  if (state.type === 'loading') return <p role="status" className={styles.message}>PDF를 불러오는 중…</p>
  if (state.type === 'error') return <div className={styles.message}>
    <p role="alert">{state.message}</p>
    <button type="button" onClick={() => { setState({ type: 'loading' }); setAttempt(attempt + 1) }}>다시 시도</button>
  </div>
  return <div className={styles.preview}>
    <p className={styles.viewerHint}>미리보기가 표시되지 않으면 <a href={state.url} target="_blank" rel="noreferrer">PDF 새 창에서 보기</a>를 이용해 주세요.</p>
    <PdfPreviewBoundary key={state.url}>
      <Suspense fallback={<p role="status" className={styles.message}>PDF 뷰어를 준비하는 중…</p>}>
        <PdfDocumentPreview url={state.url} name={attachment.fileName ?? '첨부파일'} />
      </Suspense>
    </PdfPreviewBoundary>
  </div>
}

function DownloadButton({ announcementId, attachment }: {
  readonly announcementId: string
  readonly attachment: HousingAnnouncementDetailAttachment
}) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const requestRef = useRef<AbortController | null>(null)
  useEffect(() => () => requestRef.current?.abort(), [])

  async function download() {
    if (requestRef.current) return
    const controller = new AbortController()
    requestRef.current = controller
    setPending(true)
    setError(null)
    try {
      const blob = await loadAnnouncementAttachment(announcementId, attachment.attachmentId, true, controller.signal)
      if (controller.signal.aborted) return
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = attachment.fileName ?? '첨부파일'
      document.body.append(anchor)
      anchor.click()
      anchor.remove()
      // Keep the URL alive until the browser has consumed the click's download task.
      setTimeout(() => URL.revokeObjectURL(url), 1_000)
    } catch (error: unknown) {
      if (!controller.signal.aborted) setError(attachmentErrorMessage(error))
    } finally {
      requestRef.current = null
      if (!controller.signal.aborted) setPending(false)
    }
  }

  return <div className={styles.downloadAction}>
    <Button disabled={pending || !hasAttachmentUrl(attachment)}
      aria-label={`${attachment.fileName ?? '첨부파일'} 다운로드`} onClick={() => void download()}>
      {pending ? '준비 중…' : '다운로드'}
    </Button>
    {error && <small role="alert">{error}</small>}
  </div>
}
