import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { AnnouncementDocuments } from './AnnouncementDocuments'
import type { ManagementDetailData } from './managementContract'
vi.mock('../../public-housing/components/AnnouncementAttachments', () => ({ AttachmentDialog: ({ announcementId, attachments, onClose }: { announcementId: string; attachments: { fileName: string }[]; onClose: () => void }) => <div role="dialog" aria-label={`공고 ${announcementId} 첨부파일`}><p>{attachments[0].fileName}</p><button onClick={onClose}>닫기</button></div> }))
const detail: ManagementDetailData = {
  summary: { id: 7, name: '한스빌', subtitle: '', rental: 'HAPPY_HOUSING', provider: 'LH', deleted: false, modified: false, reviewRequired: false, updatedAt: null },
  data: { version: 1, originalUrl: 'https://example.com/notice' }, sourceIdentifier: 'LH-7', housingTypes: [], supplyRows: [], announcements: [], schedules: [], scheduleReviewed: false,
  attachments: [{ attachmentId: 9, fileName: '연결된 공고문.pdf', fileType: 'ANNOUNCEMENT', fileUrl: 'https://example.com/notice.pdf' }],
}
it('연결된 첨부파일을 기존 뷰어로 열고 닫는다', () => {
  render(<AnnouncementDocuments id="7" detail={detail} />)
  expect(screen.getByRole('link')).toHaveAttribute('href', 'https://example.com/notice')
  fireEvent.click(screen.getByRole('button', { name: '연결된 첨부파일 보기 (1개)' }))
  expect(screen.getByRole('dialog', { name: '공고 7 첨부파일' })).toHaveTextContent('연결된 공고문.pdf')
  fireEvent.click(screen.getByRole('button', { name: '닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})
it('첨부가 없거나 휴지통인 공고는 원문 확인을 안내한다', () => {
  const { rerender } = render(<AnnouncementDocuments id="7" detail={{ ...detail, attachments: [] }} />)
  expect(screen.getByText('등록된 첨부파일이 없습니다. 공식 원문에서 확인하세요.')).toBeVisible()
  rerender(<AnnouncementDocuments id="7" detail={{ ...detail, summary: { ...detail.summary, deleted: true } }} />)
  expect(screen.getByText('휴지통의 공고는 공식 원문 링크에서 확인할 수 있습니다.')).toBeVisible()
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
})
