import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ComplexSearchFilters, PublicHousingRepository } from '../api/publicHousingRepository'
import { createSnapshotPublicHousingRepositories } from '../api/snapshotPublicHousingRepository'
import type { ComplexPage } from '../model/publicHousing'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from '../testing/minimalPublicHousingSnapshot'
import { useRegionComplexResults } from './useRegionComplexResults'

async function fixture() {
  const { repository } = createSnapshotPublicHousingRepositories(MINIMAL_PUBLIC_HOUSING_SNAPSHOT)
  const page = await repository.findComplexPage(null, null, 20, new AbortController().signal, { regionCode: '11' })
  const findComplexPage = vi.fn<PublicHousingRepository['findComplexPage']>().mockResolvedValue(page)
  return { page, repository: { ...repository, findComplexPage } }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

describe('useRegionComplexResults', () => {
  it('지역이 없으면 조회하지 않고 명시적 지역 필터는 검색 지역을 바꾸지 않는다', async () => {
    const { repository } = await fixture()
    const { result, rerender } = renderHook(({ region, filters }) => useRegionComplexResults(repository, region, filters), {
      initialProps: { region: null as string | null, filters: { regionCode: '41' } as ComplexSearchFilters },
    })
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    expect(result.current.state.status).toBe('idle')
    rerender({ region: '11', filters: { regionCode: '41', agencyCodes: ['LH'] } })
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    expect(repository.findComplexPage).toHaveBeenCalledExactlyOnceWith(null, null, 20, expect.any(AbortSignal), { regionCode: '11', agencyCodes: ['LH'] })
    rerender({ region: '11', filters: { regionCode: '26', agencyCodes: ['LH'] } })
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
  })

  it('검색 지역 변경·해제 뒤 늦게 끝난 이전 응답은 다시 목록을 열지 않는다', async () => {
    const { page, repository } = await fixture()
    const old = deferred<ComplexPage>()
    const current = deferred<ComplexPage>()
    repository.findComplexPage.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const { result, rerender } = renderHook(({ region }) => useRegionComplexResults(repository, region, {}), {
      initialProps: { region: '11' as string | null },
    })
    const signal = repository.findComplexPage.mock.calls[0][3]
    rerender({ region: '41' })
    expect(signal?.aborted).toBe(true)
    await act(async () => old.resolve(page))
    expect(result.current.state.items).toEqual([])
    expect(result.current.state.status).toBe('loading')
    rerender({ region: null })
    expect(repository.findComplexPage.mock.calls[1][3]?.aborted).toBe(true)
    await act(async () => current.resolve(page))
    expect(result.current.state.items).toEqual([])
    expect(result.current.state.status).toBe('idle')
  })

  it('공통 필터 변경은 이전 페이지를 취소하고 새 지역 조건 첫 페이지로 시작한다', async () => {
    const { page, repository } = await fixture()
    const old = deferred<ComplexPage>()
    repository.findComplexPage.mockReturnValueOnce(old.promise)
    const { result, rerender } = renderHook(({ filters }) => useRegionComplexResults(repository, '11', filters), {
      initialProps: { filters: {} as ComplexSearchFilters },
    })
    rerender({ filters: { minDeposit: 100000000 } })
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    await act(async () => old.resolve({ ...page, items: [] }))
    expect(result.current.state.items).toHaveLength(1)
    expect(repository.findComplexPage.mock.calls[0][3]?.aborted).toBe(true)
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(null, null, 20, expect.any(AbortSignal), { regionCode: '11', minDeposit: 100000000 })
  })

  it('더보기 실패는 기존 행을 유지하고 같은 커서를 재시도하며 중복 ID를 합친다', async () => {
    const { page, repository } = await fixture()
    const last = { ...page.items[0], complexId: '18' }
    repository.findComplexPage
      .mockResolvedValueOnce({ ...page, hasNext: true, nextCursor: 'page-2' })
      .mockRejectedValueOnce(new Error('일시 오류'))
      .mockResolvedValueOnce({ ...page, items: [...page.items, last, last] })
    const { result } = renderHook(() => useRegionComplexResults(repository, '11', {}))
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    act(() => { result.current.loadMore(); result.current.loadMore() })
    await waitFor(() => expect(result.current.state.status).toBe('error'))
    expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    expect(result.current.state.items).toHaveLength(1)
    act(() => result.current.retry())
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(null, 'page-2', 20, expect.any(AbortSignal), { regionCode: '11' })
    expect(result.current.state.items.map(({ complexId }) => complexId)).toEqual(['17', '18'])
    expect(result.current.state.totalCount).toBe(2)
  })
})
