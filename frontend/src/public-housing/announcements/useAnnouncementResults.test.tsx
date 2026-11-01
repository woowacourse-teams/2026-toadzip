import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type {
  AnnouncementSearchFilters,
  PublicHousingRepository,
} from '../api/publicHousingRepository.ts'
import type {
  AnnouncementListItem,
  AnnouncementPage,
  RawAnnouncementListItem,
  RawAnnouncementPage,
} from '../model/publicHousing.ts'
import { useAnnouncementResults } from './useAnnouncementResults.ts'

describe('useAnnouncementResults', () => {
  it('렌더 전에 연속으로 도착한 추가 조회 요청을 중복 실행하지 않는다', async () => {
    const next = deferred<AnnouncementPage>()
    const repository = createRepository()
    repository.findAnnouncementPage.mockResolvedValueOnce(announcementPage(['101'], 'next', true))
      .mockReturnValue(next.promise)
    const { result } = renderHook(() => useAnnouncementResults(repository, true))
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    act(() => { result.current.loadMore(); result.current.loadMore() })
    expect(repository.findAnnouncementPage).toHaveBeenCalledTimes(2)
    await act(async () => next.resolve(announcementPage(['102'], null, false)))
    expect(result.current.state.items.map((item) => item.announcementId)).toEqual(['101', '102'])
  })

  it('페이지를 추가해도 전체 건수를 유지하고 필터 변경 시 이전 집계를 지운다', async () => {
    const nextFilter = deferred<AnnouncementPage>()
    const repository = createRepository()
    repository.findAnnouncementPage
      .mockResolvedValueOnce({ ...announcementPage(['101'], 'next', true), totalCount: 30 })
      .mockResolvedValueOnce({ ...announcementPage(['102'], 'more', true), totalCount: 30 })
      .mockReturnValueOnce(nextFilter.promise)
    const { result, rerender } = renderHook(({ regionCode }) =>
      useAnnouncementResults(repository, true, { regionCode }), { initialProps: { regionCode: '11' } })
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    expect(result.current.state.totalCount).toBe(30)
    act(() => result.current.loadMore())
    await waitFor(() => expect(result.current.state.items).toHaveLength(2))
    expect(result.current.state.totalCount).toBe(30)
    rerender({ regionCode: '41' })
    expect(result.current.state.totalCount).toBeNull()
    await act(async () => nextFilter.resolve({ ...announcementPage([], null, false), totalCount: 0 }))
    expect(result.current.state.totalCount).toBe(0)
  })

  it('필터 변경 전의 늦은 응답이 새 전체 건수를 덮어쓰지 않는다', async () => {
    const previous = deferred<AnnouncementPage>()
    const repository = createRepository()
    repository.findAnnouncementPage.mockReturnValueOnce(previous.promise)
      .mockResolvedValueOnce({ ...announcementPage(['201'], null, false), totalCount: 1 })
    const { result, rerender } = renderHook(({ regionCode }) =>
      useAnnouncementResults(repository, true, { regionCode }), { initialProps: { regionCode: '11' } })
    rerender({ regionCode: '41' })
    await waitFor(() => expect(result.current.state.totalCount).toBe(1))
    await act(async () => previous.resolve({ ...announcementPage(['101'], 'next', true), totalCount: 100 }))
    expect(result.current.state.totalCount).toBe(1)
    expect(result.current.state.items[0]?.announcementId).toBe('201')
  })

  it('이전 API의 전체 수는 모든 페이지를 받은 경우에만 중복 없이 계산한다', async () => {
    const repository = createRepository()
    repository.findAnnouncementPage.mockResolvedValueOnce(announcementPage(['101'], 'next', true))
      .mockResolvedValueOnce(announcementPage(['101', '102'], null, false))
    const { result } = renderHook(() => useAnnouncementResults(repository, true))
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    expect(result.current.state.totalCount).toBeNull()
    act(() => result.current.loadMore())
    await waitFor(() => expect(result.current.state.items).toHaveLength(2))
    expect(result.current.state.totalCount).toBe(2)
  })

  it('상세에서 확인한 조회수를 목록에 반영하고 늦은 페이지 응답으로 되돌리지 않는다', async () => {
    const pending = deferred<AnnouncementPage>()
    const repository = createRepository()
    repository.findAnnouncementPage.mockReturnValueOnce(pending.promise)
    const { result } = renderHook(() => useAnnouncementResults(repository, true))
    act(() => result.current.updateViewCount('101', 7))
    await act(async () => pending.resolve(announcementPage(['101'], null, false)))
    await waitFor(() => expect(result.current.state.items[0]?.viewCount).toBe(7))
    act(() => result.current.updateViewCount('101', 8))
    expect(result.current.state.items[0]?.viewCount).toBe(8)
    act(() => result.current.updateViewCount('101', 3))
    expect(result.current.state.items[0]?.viewCount).toBe(8)
  })

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
      { applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] },
    )

    rerender(<Harness enabled={false} repository={repository} />)
    rerender(<Harness enabled repository={repository} />)

    expect(repository.findAnnouncementPage).toHaveBeenCalledOnce()
    expect(screen.getByText('101')).toBeVisible()
  })

  it('진행 중인 첫 페이지 요청을 탭을 닫을 때 취소하고 다시 열면 다시 요청한다', async () => {
    const firstPage = deferred<AnnouncementPage>()
    const repeatedFirstPage = deferred<AnnouncementPage>()
    const repository = createRepository()
    repository.findAnnouncementPage
      .mockReturnValueOnce(firstPage.promise)
      .mockReturnValueOnce(repeatedFirstPage.promise)
    const { rerender } = render(<Harness enabled repository={repository} />)
    const firstSignal = repository.findAnnouncementPage.mock.calls[0][2]

    rerender(<Harness enabled={false} repository={repository} />)

    expect(firstSignal.aborted).toBe(true)
    rerender(<Harness enabled repository={repository} />)
    expect(repository.findAnnouncementPage).toHaveBeenCalledTimes(2)

    repeatedFirstPage.resolve(announcementPage(['201'], null, false))
    expect(await screen.findByText('201')).toBeVisible()
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
      { applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] },
    )
  })

  it('진행 중인 다음 페이지 요청을 탭을 닫을 때 취소하고 기존 첫 페이지를 유지한다', async () => {
    const nextPage = deferred<AnnouncementPage>()
    const repository = createRepository()
    repository.findAnnouncementPage
      .mockResolvedValueOnce(announcementPage(['101'], 'next', true))
      .mockReturnValueOnce(nextPage.promise)
    const { rerender } = render(<Harness enabled repository={repository} />)
    await screen.findByText('101')

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))
    const paginationSignal = repository.findAnnouncementPage.mock.calls[1][2]
    rerender(<Harness enabled={false} repository={repository} />)

    expect(paginationSignal.aborted).toBe(true)
    expect(screen.getByText('ready')).toBeVisible()
    rerender(<Harness enabled repository={repository} />)
    expect(repository.findAnnouncementPage).toHaveBeenCalledTimes(2)
    expect(screen.getByText('101')).toBeVisible()
  })

  it('필터를 다음 페이지에도 유지하고 변경 시 cursor 없이 다시 시작한다', async () => {
    const repository = createRepository()
    repository.findAnnouncementPage
      .mockResolvedValueOnce(announcementPage(['101'], 'next', true))
      .mockResolvedValueOnce(announcementPage(['102'], null, false))
      .mockResolvedValueOnce(announcementPage(['201'], null, false))
    const seoulFilters: AnnouncementSearchFilters = {
      regionCode: '11',
      rentalTypes: ['HAPPY_HOUSING'],
    }
    const gyeonggiFilters: AnnouncementSearchFilters = {
      regionCode: '41',
      rentalTypes: ['NATIONAL_RENTAL'],
    }
    const { rerender } = render(
      <Harness
        enabled
        filters={seoulFilters}
        repository={repository}
      />,
    )
    await screen.findByText('101')

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))

    expect(await screen.findByText('102')).toBeVisible()
    expect(repository.findAnnouncementPage).toHaveBeenLastCalledWith(
      'next',
      20,
      expect.any(AbortSignal),
      { ...seoulFilters, applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] },
    )

    rerender(
      <Harness
        enabled
        filters={gyeonggiFilters}
        repository={repository}
      />,
    )

    expect(await screen.findByText('201')).toBeVisible()
    expect(screen.queryByText('101')).not.toBeInTheDocument()
    expect(repository.findAnnouncementPage).toHaveBeenLastCalledWith(
      null,
      20,
      expect.any(AbortSignal),
      { ...gyeonggiFilters, applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] },
    )
  })

  it('필터 변경 뒤 첫 페이지가 실패해도 이전 필터의 공고를 표시하지 않는다', async () => {
    const nextFiltersPage = deferred<AnnouncementPage>()
    const repository = createRepository()
    repository.findAnnouncementPage
      .mockResolvedValueOnce(announcementPage(['101'], null, false))
      .mockReturnValueOnce(nextFiltersPage.promise)
    const { rerender } = render(
      <Harness
        enabled
        filters={{ regionCode: '11' }}
        repository={repository}
      />,
    )
    await screen.findByText('101')

    rerender(
      <Harness
        enabled
        filters={{ regionCode: '41' }}
        repository={repository}
      />,
    )

    expect(screen.getByText('loading')).toBeVisible()
    expect(screen.queryByText('101')).not.toBeInTheDocument()
    nextFiltersPage.reject(new Error('새 공고 연결 실패'))

    expect(await screen.findByText('잠시 후 다시 시도해 주세요.')).toBeVisible()
    expect(screen.queryByText('101')).not.toBeInTheDocument()
  })

  it('첫 페이지 오류는 다시 시도하고 성공한 결과로 교체한다', async () => {
    const repository = createRepository()
    repository.findAnnouncementPage
      .mockRejectedValueOnce(new Error('공고 연결 실패'))
      .mockResolvedValueOnce(announcementPage(['201'], null, false))
    render(<Harness enabled repository={repository} />)

    expect(await screen.findByText('잠시 후 다시 시도해 주세요.')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('201')).toBeVisible()
    expect(repository.findAnnouncementPage).toHaveBeenCalledTimes(2)
    expect(repository.findAnnouncementPage).toHaveBeenLastCalledWith(
      null,
      20,
      expect.any(AbortSignal),
      { applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] },
    )
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
    await screen.findByText('잠시 후 다시 시도해 주세요.')

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('102')).toBeVisible()
    expect(screen.getByText('101')).toBeVisible()
    expect(repository.findAnnouncementPage).toHaveBeenLastCalledWith(
      'next',
      20,
      expect.any(AbortSignal),
      { applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] },
    )
  })

  it.each([
    { selected: ['BEFORE_APPLICATION'], expected: ['BEFORE_APPLICATION'] },
    { selected: ['APPLYING'], expected: ['APPLYING'] },
    { selected: ['APPLYING', 'CLOSED'], expected: ['APPLYING'] },
    { selected: ['CLOSED'], expected: ['BEFORE_APPLICATION', 'APPLYING'] },
    { selected: [], expected: ['BEFORE_APPLICATION', 'APPLYING'] },
  ] as const)('선택한 $selected 상태에서 마감을 제외하고 초기화 뒤에도 제외한다', async ({ selected, expected }) => {
    const repository = createRepository()
    const { rerender } = render(
      <Harness enabled filters={{ applicationStatuses: selected }} repository={repository} />,
    )
    await screen.findByText('101')

    expect(repository.findAnnouncementPage).toHaveBeenLastCalledWith(
      null,
      20,
      expect.any(AbortSignal),
      { applicationStatuses: expected },
    )

    rerender(<Harness enabled filters={{}} repository={repository} />)

    expect(repository.findAnnouncementPage).toHaveBeenLastCalledWith(
      null,
      20,
      expect.any(AbortSignal),
      { applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'] },
    )
    expect(await screen.findByText('101')).toBeVisible()
  })
})

function Harness({
  enabled,
  filters = {},
  repository,
}: {
  enabled: boolean
  filters?: AnnouncementSearchFilters
  repository: PublicHousingRepository
}) {
  const { loadMore, retry, state } = useAnnouncementResults(
    repository,
    enabled,
    filters,
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
    findAnnouncementDetail: vi.fn(),
    findAnnouncementPage: vi.fn().mockResolvedValue(
      announcementPage(['101'], null, false),
    ),
    findComplexDetail: vi.fn(),
    findComplexPage: vi.fn(),
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

function deferred<T>() {
  let reject: (reason?: unknown) => void = () => undefined
  let resolve: (value: T) => void = () => undefined
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return { promise, reject, resolve }
}
