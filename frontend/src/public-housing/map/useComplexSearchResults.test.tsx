import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { toAnnouncementPage, toComplexPage } from '../api/publicHousingMapper.ts'
import type { AnnouncementSearchFilters, ComplexSearchFilters, PublicHousingRepository } from '../api/publicHousingRepository.ts'
import type { AnnouncementPage, ComplexPage, ComplexSearchSnapshot, SearchScope } from '../model/publicHousing.ts'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT as fixture } from '../testing/minimalPublicHousingSnapshot.ts'
import { useComplexSearchResults } from './useComplexSearchResults.ts'

const region: SearchScope = { mode: 'region', regionCode: '11380' }
const area: SearchScope = { mode: 'area', bounds: {
  southWestLat: 37.4, southWestLng: 126.8, northEastLat: 37.8, northEastLng: 127.3,
} }
type Inputs = {
  scope: SearchScope | null
  filters: ComplexSearchFilters
  options: { preserveList: boolean; restore: boolean; revision: number }
  announcements?: AnnouncementSearchFilters
}
const initial: Inputs = { scope: region, filters: {}, options: { preserveList: false, restore: false, revision: 0 } }

function repository() {
  return {
    findComplexSearch: vi.fn<NonNullable<PublicHousingRepository['findComplexSearch']>>().mockResolvedValue(snapshot([17, 18], [17], 'next')),
    findComplexPage: vi.fn<PublicHousingRepository['findComplexPage']>().mockResolvedValue(page([18])),
    findComplexDetail: vi.fn<PublicHousingRepository['findComplexDetail']>(),
    findAnnouncementPage: vi.fn<PublicHousingRepository['findAnnouncementPage']>(),
    findAnnouncementDetail: vi.fn<PublicHousingRepository['findAnnouncementDetail']>(),
  }
}
function setup(repo: PublicHousingRepository, props = initial) {
  return renderHook((input: Inputs) => useComplexSearchResults(repo, input.scope, input.filters, input.options, input.announcements),
    { initialProps: props })
}
function page(ids: number[], nextCursor: string | null = null): ComplexPage {
  return toComplexPage({ items: ids.map((complexId) => ({ ...fixture.complexListItems[0], complexId })),
    nextCursor, hasNext: nextCursor !== null })
}
function snapshot(ids: number[], firstPage = ids, nextCursor: string | null = null): ComplexSearchSnapshot {
  return { totalCount: ids.length, locatedCount: 0, complexIds: ids.map(String), bounds: null, mapItems: [], page: page(firstPage, nextCursor) }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

describe('확정한 검색 범위의 단지 결과', () => {
  it('검색 범위가 준비될 때까지 요청하지 않고 같은 조건의 렌더링은 재검색하지 않는다', async () => {
    const repo = repository()
    const { result, rerender } = setup(repo, { ...initial, scope: null })
    expect(repo.findComplexSearch).not.toHaveBeenCalled()
    rerender(initial)
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    rerender({ ...initial, scope: { ...region }, filters: {} })
    expect(repo.findComplexSearch).toHaveBeenCalledOnce()
    expect(result.current.state.totalCount).toBe(2)
    expect(result.current.state.items).toHaveLength(1)
  })

  it('지역 검색의 다음 페이지는 좌표가 없어도 지역 범위를 유지하고 중복을 제거한다', async () => {
    const repo = repository()
    repo.findComplexPage.mockResolvedValueOnce(page([17, 18]))
    const { result } = setup(repo)
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    act(() => result.current.loadMore())
    await waitFor(() => expect(result.current.state.items.map((item) => item.complexId)).toEqual(['17', '18']))
    expect(repo.findComplexPage).toHaveBeenCalledWith(expect.any(Object), 'next', 20, expect.any(AbortSignal), { scope: region })
  })

  it('지도 자동 검색의 전체 ID가 같으면 페이지와 목록 위치 변경 번호를 유지한다', async () => {
    const repo = repository()
    const { result, rerender } = setup(repo)
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    act(() => result.current.loadMore())
    await waitFor(() => expect(result.current.state.items).toHaveLength(2))
    const revision = result.current.state.listRevision
    repo.findComplexSearch.mockResolvedValueOnce(snapshot([18, 17], [18], 'new-next'))
    rerender({ ...initial, scope: area, options: { ...initial.options, preserveList: true } })
    await waitFor(() => expect(result.current.state.scope).toEqual(area))
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['17', '18'])
    expect(result.current.state.nextCursor).toBeNull()
    expect(result.current.state.listRevision).toBe(revision)
    repo.findComplexSearch.mockResolvedValueOnce(snapshot([19]))
    rerender({ ...initial, scope: area, options: { ...initial.options, preserveList: true, revision: 1 } })
    await waitFor(() => expect(result.current.state.totalCount).toBe(1))
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['19'])
    expect(result.current.state.listRevision).toBe(revision + 1)
  })

  it('새 범위 검색은 이전 목록을 유지하면서 오래된 응답과 다음 페이지를 취소한다', async () => {
    const repo = repository()
    const oldPage = deferred<ComplexPage>()
    const oldSearch = deferred<ComplexSearchSnapshot>()
    repo.findComplexPage.mockReturnValueOnce(oldPage.promise)
    const { result, rerender } = setup(repo)
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    act(() => result.current.loadMore())
    repo.findComplexSearch.mockReturnValueOnce(oldSearch.promise)
    rerender({ ...initial, scope: area })
    expect(repo.findComplexPage.mock.calls[0][3].aborted).toBe(true)
    expect(result.current.state.status).toBe('loading')
    expect(result.current.state.items[0]?.complexId).toBe('17')
    act(() => result.current.loadMore())
    expect(repo.findComplexPage).toHaveBeenCalledOnce()
    repo.findComplexSearch.mockResolvedValueOnce(snapshot([19]))
    rerender({ ...initial, scope: { mode: 'region', regionCode: '41' } })
    expect(repo.findComplexSearch.mock.calls[1][2].aborted).toBe(true)
    await waitFor(() => expect(result.current.state.items[0]?.complexId).toBe('19'))
    await act(async () => { oldSearch.resolve(snapshot([20])); oldPage.resolve(page([21])) })
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['19'])
  })

  it('뒤로가기는 이미 더 불러온 목록을 캐시에서 복원한다', async () => {
    const repo = repository()
    const { result, rerender } = setup(repo)
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    act(() => result.current.loadMore())
    await waitFor(() => expect(result.current.state.items).toHaveLength(2))
    repo.findComplexSearch.mockResolvedValueOnce(snapshot([19]))
    rerender({ ...initial, scope: area })
    await waitFor(() => expect(result.current.state.items[0]?.complexId).toBe('19'))
    rerender({ ...initial, options: { ...initial.options, restore: true } })
    await waitFor(() => expect(result.current.state.restored).toBe(true))
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['17', '18'])
    expect(repo.findComplexSearch).toHaveBeenCalledTimes(2)
  })

  it('단지와 공고 응답을 함께 적용하고 공고 실패 시 이전 검색을 보존한 채 재시도한다', async () => {
    const repo = repository()
    const pending = deferred<AnnouncementPage>()
    const announcementPage = toAnnouncementPage({ items: [], totalCount: 0, hasNext: false, nextCursor: null })
    const { result, rerender } = setup(repo)
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    repo.findComplexSearch.mockResolvedValue(snapshot([19]))
    repo.findAnnouncementPage.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(announcementPage)
    rerender({ ...initial, scope: area, announcements: {} })
    await act(async () => {})
    expect(result.current.state.scope).toEqual(region)
    expect(result.current.state.items[0]?.complexId).toBe('17')
    await act(async () => pending.reject(new Error('공고 조회 실패')))
    expect(result.current.state.status).toBe('error')
    expect(result.current.state.scope).toEqual(region)
    act(() => result.current.retry())
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    expect(result.current.state.scope).toEqual(area)
    expect(result.current.state.items[0]?.complexId).toBe('19')
    expect(result.current.state.announcementPage?.page).toBe(announcementPage)
    expect(repo.findAnnouncementPage).toHaveBeenLastCalledWith(null, 20, expect.any(AbortSignal),
      { applicationStatuses: ['BEFORE_APPLICATION', 'APPLYING'], regionCode: null, scope: area })
  })
})
