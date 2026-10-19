import { useState } from 'react'
import { AttachmentDialog } from '../../public-housing/components/AnnouncementAttachments'
import { SourceUrl } from '../shared/SourceUrl'
import type { ManagementDetailData } from './managementContract'
const attachmentLabels: Record<string, string> = { ANNOUNCEMENT: '공고문', CORRECTION: '정정공고문', CANCELLATION: '취소공고문', REFERENCE: '참고자료', ETC: '기타 첨부파일' }

export function AnnouncementDocuments({ id, detail }: { id: string; detail: ManagementDetailData }) {
  const [open, setOpen] = useState(false)
  const attachments = (detail.attachments ?? []).map(item => ({ attachmentId: String(item.attachmentId),
    fileName: item.fileName, fileTypeLabel: attachmentLabels[item.fileType] ?? item.fileType, fileUrl: item.fileUrl }))
  return <section>
    <h2>공고문·첨부파일</h2>
    <p>연결된 공식 공고문을 열어 값을 확인한 뒤, 기본정보나 공급정보에서 필요한 항목을 수정하세요.</p>
    <p>공식 원문: <SourceUrl url={detail.data.originalUrl} /></p>
    {attachments.length > 0 && !detail.summary.deleted
      ? <button type="button" onClick={() => setOpen(true)}>연결된 첨부파일 보기 ({attachments.length}개)</button>
      : <p>{detail.summary.deleted ? '휴지통의 공고는 공식 원문 링크에서 확인할 수 있습니다.' : '등록된 첨부파일이 없습니다. 공식 원문에서 확인하세요.'}</p>}
    {open && <AttachmentDialog announcementId={id} attachments={attachments} onClose={() => setOpen(false)} />}
  </section>
}
