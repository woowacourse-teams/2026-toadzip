import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { AnnouncementOwnership, ManagementStatus } from './ManagementStatus'
import type { ManagementSummary } from './managementContract'

const summary: ManagementSummary = {
  id: 1, name: '공고', subtitle: '2026-10-01', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false,
  reviewRequired: false, updatedAt: null,
  announcement: { sourceIdentifier: '21026', originalUrl: 'https://example.com', recruitmentType: 'NEW',
    postedDate: '2026-10-01', applicationStartDate: '2026-10-05', applicationEndDate: '2026-10-10',
    winnerAnnouncementDate: '2026-11-01', publicationType: 'ORIGINAL', origin: 'LH_SOURCE', supplyOwner: 'LH_SOURCE' },
}

it('기관 공고는 공급표를 공고문으로 확정했는지 알려 사용자 화면이 확인 전 정보를 보이는 공고인지 구분하게 한다', () => {
  const { rerender } = render(<AnnouncementOwnership summary={{ ...summary, announcement: { ...summary.announcement!, supplyConfirmed: false } }} />)
  expect(screen.getByText('출처: LH 원천')).toBeInTheDocument()
  expect(screen.getByText('공급정보: 공고문 확인 전')).toHaveAttribute('title', expect.stringContaining('기관 제공 정보로 공급표를 시작하면 확정됩니다'))
  rerender(<AnnouncementOwnership summary={{ ...summary, announcement: { ...summary.announcement!, origin: 'GH_SOURCE', supplyConfirmed: true } }} />)
  expect(screen.getByText('출처: GH 원천')).toBeInTheDocument()
  expect(screen.getByText('공급정보: 공고문 확정')).toBeInTheDocument()
  // supplyConfirmed가 없던 이전 응답은 supplyOwner로 읽는다.
  rerender(<AnnouncementOwnership summary={summary} />)
  expect(screen.getByText('공급정보: 공고문 확인 전')).toBeInTheDocument()
  rerender(<AnnouncementOwnership summary={{ ...summary, announcement: { ...summary.announcement!, supplyOwner: 'ANNOUNCEMENT_DOCUMENT' } }} />)
  expect(screen.getByText('공급정보: 공고문 확정')).toBeInTheDocument()
})

it('기관이 공급정보를 보내지 않는 공고에는 공급정보 확정 여부를 표시하지 않는다', () => {
  const { rerender } = render(<AnnouncementOwnership summary={{ ...summary, announcement: { ...summary.announcement!,
    origin: 'ADMIN_ENTRY', supplyOwner: 'ANNOUNCEMENT_DOCUMENT', supplyConfirmed: true } }} />)
  expect(screen.getByText('출처: 직접 등록')).toBeInTheDocument()
  expect(screen.queryByText(/공급정보:/)).not.toBeInTheDocument()
  rerender(<AnnouncementOwnership summary={{ ...summary, announcement: { ...summary.announcement!,
    origin: 'MYHOME_LEGACY', supplyOwner: 'LH_SOURCE', supplyConfirmed: false } }} />)
  expect(screen.getByText('출처: 마이홈(과거)')).toBeInTheDocument()
  expect(screen.queryByText(/공급정보:/)).not.toBeInTheDocument()
  rerender(<AnnouncementOwnership summary={{ ...summary, announcement: { ...summary.announcement!,
    origin: 'SH_SOURCE', supplyOwner: 'LH_SOURCE', supplyConfirmed: false } }} />)
  expect(screen.getByText('출처: SH 원천')).toBeInTheDocument()
  expect(screen.queryByText(/공급정보:/)).not.toBeInTheDocument()
})

it('출처와 공급정보 주인이 없는 이전 응답에는 표시하지 않는다', () => {
  render(<AnnouncementOwnership summary={{ ...summary, announcement: { ...summary.announcement!,
    origin: undefined, supplyOwner: undefined } }} />)
  expect(screen.queryByText(/출처:|공급정보:/)).not.toBeInTheDocument()
})

it('미확정 공고는 사용자에게 보이지 않는다고 표시하고 공개된 공고는 표시하지 않는다', () => {
  const { rerender } = render(<ManagementStatus summary={{ ...summary, announcement: { ...summary.announcement!,
    releaseStatus: 'UNCONFIRMED' } }} />)
  expect(screen.getByText('미확정')).toHaveAttribute('title', expect.stringContaining('사용자에게 보이지 않습니다'))
  rerender(<ManagementStatus summary={{ ...summary, announcement: { ...summary.announcement!, releaseStatus: 'PUBLISHED' } }} />)
  expect(screen.queryByText('미확정')).not.toBeInTheDocument()
})
