import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { PublicHousingRepository } from '../api/publicHousingRepository.ts'
import type {
  AnnouncementListItem,
  AnnouncementPage,
  RawAnnouncementListItem,
  RawAnnouncementPage,
} from '../model/publicHousing.ts'
import { useAnnouncementResults } from './useAnnouncementResults.ts'

describe('useAnnouncementResults', () => {
  it('공고 탭을 처음 열 때만 첫 페이지를 요청하고 탭을 오가도 유지한다', async () => {
    const repository = createRepository()
    const { rerender } = render(
      <Harness enabled={false} repository={repository} />,
    )

    expect(repository.findAnnouncementPage).not.toHaveBeenCalled()
    rerender(<Harness enabled repository={repository} />)

    await screen.findByText('101')
    expect(repository.findAnnouncementPage).toHaveBeenCalledWith(
      null,
      20,
      expect.any(AbortSignal),
    )

    rerender(<Harness enabled={false} repository={repository} />)
    rerender(<Harness enabled repository={repository} />)

    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
    expect(screen.getByText('101')).toBeVisible()
  })

  it('공고 전용 cursor로 다음 페이지를 이어 붙이고 중복 ID는 제거한다', async () => {
    const repository = createRepository()
    repository.findAnnouncementPage
      .mockResolvedValueOnce(announcementPage(['101'], 'next', true))
      .mockResolvedValueOnce(announcementPage(['101', '102'], null, false))
    render(<Harness enabled repository={repository} />)
    await screen.findByText('101')

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))

    expect(await screen.findByText('102')).toBeVisible()
    expect(screen.getAllByText('101')).toHaveLength(1)
    expect(repository.findAnnouncementPage).toHaveBeenLastCalledWith(
      'next',
      20,
      expect.any(AbortSignal),
    )
  })

  it('첫 페이지 오류는 다시 시도하고 성공한 결과로 교체한다', async () => {
    const repository = createRepository()
    repository.findAnnouncementPage
      .mockRejectedValueOnce(new Error('공고 연결 실패'))
      .mockResolvedValueOnce(announcementPage(['201'], null, false))
    render(<Harness enabled repository={repository} />)

    expect(await screen.findByText('공고 연결 실패')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('201')).toBeVisible()
    expect(repository.findAnnouncementPage).toHaveBeenCalledTimes(2)
  })

  it('cursor 요청 오류는 기존 결과와 cursor를 보존해 같은 페이지를 재시도한다', async () => {
    const repository = createRepository()
    repository.findAnnouncementPage
      .mockResolvedValueOnce(announcementPage(['101'], 'next', true))
      .mockRejectedValueOnce(new Error('다음 공고 실패'))
      .mockResolvedValueOnce(announcementPage(['102'], null, false))
    render(<Harness enabled repository={repository} />)
    await screen.findByText('101')
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))
    await screen.findByText('다음 공고 실패')

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('102')).toBeVisible()
    expect(screen.getByText('101')).toBeVisible()
    expect(repository.findAnnouncementPage).toHaveBeenLastCalledWith(
      'next',
      20,
      expect.any(AbortSignal),
    )
  })
})

function Harness({
  enabled,
  repository,
}: {
  enabled: boolean
  repository: PublicHousingRepository
}) {
  const { loadMore, retry, state } = useAnnouncementResults(
    repository,
    enabled,
  )
  return (
    <section>
      <output>{state.status}</output>
      {state.errorMessage && <p>{state.errorMessage}</p>}
      {state.items.map((item) => <span key={item.announcementId}>{item.announcementId}</span>)}
      <button type="button" onClick={loadMore}>더 보기</button>
      <button type="button" onClick={retry}>다시 시도</button>
    </section>
  )
}

function createRepository(): PublicHousingRepository & {
  findAnnouncementPage: ReturnType<typeof vi.fn>
} {
  return {
    findAnnouncementPage: vi.fn().mockResolvedValue(
      announcementPage(['101'], null, false),
    ),
    findComplexDetail: vi.fn(),
    findComplexPage: vi.fn(),
    findMapComplexes: vi.fn(),
  }
}

function announcementPage(
  ids: readonly string[],
  nextCursor: string | null,
  hasNext: boolean,
): AnnouncementPage {
  const items = ids.map(announcementListItem)
  const raw: RawAnnouncementPage = {
    hasNext,
    items: items.map((item) => item.raw),
    nextCursor,
  }
  return { hasNext, items, nextCursor, raw }
}

function announcementListItem(announcementId: string): AnnouncementListItem {
  const raw: RawAnnouncementListItem = {
    actualCompetitionRate: null,
    agency: { code: 'LH', name: '한국토지주택공사' },
    announcementId: Number(announcementId),
    applicationEndAt: '2026-08-30',
    applicationStartAt: '2026-08-28',
    applicationStatus: 'APPLYING',
    dDay: 2,
    predictedCompetitionRate: null,
    publicationType: 'ORIGINAL',
    publishedAt: '2026-08-20',
    recruitmentType: 'NEW',
    regionNames: ['서울특별시 중구'],
    rentalType: 'HAPPY_HOUSING',
    supplyComplexCount: 1,
    supplyHouseholdCount: 20,
    thumbnailImageUrl: null,
    title: `공고 ${announcementId}`,
    viewCount: 0,
  }
  return { ...raw, announcementId, raw }
}
