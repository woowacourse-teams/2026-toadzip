import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { AnnouncementOwnership } from './ManagementStatus'
import type { ManagementSummary } from './managementContract'

const summary: ManagementSummary = {
  id: 1, name: '공고', subtitle: '2026-10-01', provider: 'LH', rental: 'HAPPY_HOUSING', deleted: false, modified: false,
  reviewRequired: false, updatedAt: null,
  announcement: { sourceIdentifier: '21026', originalUrl: 'https://example.com', recruitmentType: 'NEW',
    postedDate: '2026-10-01', applicationStartDate: '2026-10-05', applicationEndDate: '2026-10-10',
    winnerAnnouncementDate: '2026-11-01', publicationType: 'ORIGINAL', origin: 'LH_SOURCE', supplyOwner: 'LH_SOURCE' },
}

it('공고 출처와 공급정보 주인을 알려 정제가 공급행을 다시 만드는 공고인지 구분하게 한다', () => {
  const { rerender } = render(<AnnouncementOwnership summary={summary} />)
  expect(screen.getByText('출처: LH 원천')).toBeInTheDocument()
  expect(screen.getByText('공급정보: 원천 반영')).toBeInTheDocument()
  rerender(<AnnouncementOwnership summary={{ ...summary, announcement: { ...summary.announcement!,
    supplyOwner: 'ANNOUNCEMENT_DOCUMENT' } }} />)
  expect(screen.getByText('공급정보: 공고문 확정')).toHaveAttribute('title', expect.stringContaining('공급행을 다시 만들지 않습니다'))
})

it('LH 공고 정제가 다루지 않는 공고에는 공급정보 주인을 표시하지 않는다', () => {
  const { rerender } = render(<AnnouncementOwnership summary={{ ...summary, announcement: { ...summary.announcement!,
    origin: 'ADMIN_ENTRY', supplyOwner: 'ANNOUNCEMENT_DOCUMENT' } }} />)
  expect(screen.getByText('출처: 직접 등록')).toBeInTheDocument()
  expect(screen.queryByText(/공급정보:/)).not.toBeInTheDocument()
  rerender(<AnnouncementOwnership summary={{ ...summary, announcement: { ...summary.announcement!,
    origin: 'MYHOME_LEGACY', supplyOwner: 'LH_SOURCE' } }} />)
  expect(screen.getByText('출처: 마이홈(과거)')).toBeInTheDocument()
  expect(screen.queryByText(/공급정보:/)).not.toBeInTheDocument()
})

it('출처와 공급정보 주인이 없는 이전 응답에는 표시하지 않는다', () => {
  render(<AnnouncementOwnership summary={{ ...summary, announcement: { ...summary.announcement!,
    origin: undefined, supplyOwner: undefined } }} />)
  expect(screen.queryByText(/출처:|공급정보:/)).not.toBeInTheDocument()
})
