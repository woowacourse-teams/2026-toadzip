import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ComplexSearchFilters, PublicHousingRepository } from '../api/publicHousingRepository.ts'
import type { AppliedHousingMapResult } from '../map/useHousingMapResults.ts'
import type { ViewportSnapshot } from '../map/viewportPolicy.ts'
import type { ComplexPage, RawComplexListItem } from '../model/publicHousing.ts'
import { useComplexResults } from './useComplexResults.ts'

const VIEWPORT: ViewportSnapshot = {
  bounds: { southWestLat: 37.5, southWestLng: 126.9, northEastLat: 37.6, northEastLng: 127.0 },
  center: { latitude: 37.55, longitude: 126.95 },
  zoom: 14,
}
const NEXT_VIEWPORT: ViewportSnapshot = {
  ...VIEWPORT,
  bounds: { ...VIEWPORT.bounds, northEastLng: 127.1 },
}

describe('useComplexResults', () => {
  it.each([
    { ...VIEWPORT, zoom: 12 },
    { ...VIEWPORT, zoom: Number.NaN },
    { ...VIEWPORT, bounds: { ...VIEWPORT.bounds, northEastLat: 38 } },
    { ...VIEWPORT, bounds: { ...VIEWPORT.bounds, southWestLng: Number.NaN } },
  ])('요청 가능한 영역이 준비될 때까지 목록을 조회하지 않는다: %j', (viewport) => {
    const repository = createRepository()
    const { result } = renderResults(repository)
    expect(result.current.state.status).toBe('idle')
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    act(() => result.current.request(viewport))
    expect(repository.findComplexPage).not.toHaveBeenCalled()
    expect(result.current.hasAppliedViewport()).toBe(false)
  })

  it('동일 영역의 진행 중·완료된 요청을 중복하지 않고 빈 필터는 인자를 생략한다', async () => {
    const pending = deferred<ComplexPage>()
    const repository = createRepository()
    repository.findComplexPage.mockReturnValueOnce(pending.promise)
    const onViewportApplied = vi.fn(() => {
      expect(result.current.hasAppliedViewport()).toBe(true)
    })
    const { result } = renderResults(repository, { onViewportApplied })
    act(() => result.current.request(VIEWPORT))
    act(() => result.current.request({
      ...VIEWPORT,
      bounds: { ...VIEWPORT.bounds, southWestLat: 37.5000001 },
    }))
    expect(repository.findComplexPage).toHaveBeenCalledExactlyOnceWith(
      VIEWPORT.bounds, null, 20, expect.any(AbortSignal),
    )
    await act(async () => pending.resolve(page(['1'])))
    act(() => result.current.request(VIEWPORT))
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    expect(onViewportApplied).toHaveBeenCalledOnce()
    expect(result.current.state).toMatchObject({ status: 'ready', totalCount: 1 })
  })

  it.each(['success', 'error'] as const)('새 영역이 시작되면 이전 첫 페이지를 취소하고 늦은 %s를 무시한다', async (outcome) => {
    const first = deferred<ComplexPage>()
    const second = deferred<ComplexPage>()
    const repository = createRepository()
    repository.findComplexPage.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const onViewportApplied = vi.fn()
    const { result } = renderResults(repository, { onViewportApplied })
    act(() => result.current.request(VIEWPORT))
    const firstSignal = repository.findComplexPage.mock.calls[0][3]
    act(() => result.current.request(NEXT_VIEWPORT))
    expect(firstSignal.aborted).toBe(true)
    await act(async () => second.resolve(page(['2'])))
    await act(async () => {
      if (outcome === 'success') first.resolve(page(['1']))
      else first.reject(new Error('old request failed'))
    })
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['2'])
    expect(result.current.state.errorMessage).toBeNull()
    expect(result.current.state.status).toBe('ready')
    expect(onViewportApplied).toHaveBeenCalledOnce()
  })

  it('최초 요청을 취소하면 빈 성공 대신 대기로 복원하고 늦은 결과를 적용하지 않는다', async () => {
    const pending = deferred<ComplexPage>()
    const repository = createRepository()
    repository.findComplexPage.mockReturnValueOnce(pending.promise)
    const onViewportApplied = vi.fn()
    const { result } = renderResults(repository, { onViewportApplied })
    act(() => result.current.request(VIEWPORT))
    act(() => result.current.cancel())
    expect(repository.findComplexPage.mock.calls[0][3].aborted).toBe(true)
    expect(result.current.state.status).toBe('idle')
    await act(async () => pending.resolve(page(['1'])))
    expect(result.current.state.items).toEqual([])
    expect(result.current.hasAppliedViewport()).toBe(false)
    expect(onViewportApplied).not.toHaveBeenCalled()
  })

  it('새 조회 동안 기존 행과 수를 유지하고 취소하면 다시 더보기를 허용한다', async () => {
    const pending = deferred<ComplexPage>()
    const repository = createRepository()
    repository.findComplexPage
      .mockResolvedValueOnce(page(['1'], 'next'))
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(page(['2']))
    const { result } = renderResults(repository, { appliedMap: individualMap(['1', '2']) })
    await act(async () => result.current.request(VIEWPORT))
    act(() => result.current.request(NEXT_VIEWPORT))
    expect(result.current.state).toMatchObject({ status: 'loading', totalCount: 2 })
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['1'])
    act(() => result.current.cancel())
    expect(result.current.state.status).toBe('ready')
    await act(async () => result.current.loadMore())
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(
      VIEWPORT.bounds, 'next', 20, expect.any(AbortSignal),
    )
    await act(async () => pending.resolve(page(['3'])))
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['1', '2'])
  })

  it('첫 페이지 실패 재시도는 실패한 조건과 최신 지도 전체 수를 사용한다', async () => {
    const repository = createRepository()
    repository.findComplexPage
      .mockRejectedValueOnce(new Error('조회 실패'))
      .mockResolvedValueOnce(page(['1'], 'next'))
    const originalFilters = { regionCode: '11', minDeposit: 0 }
    const { result, rerender } = renderResults(repository, {
      filters: originalFilters,
      appliedMap: individualMap(['1'], VIEWPORT, originalFilters),
    })
    await act(async () => result.current.request(VIEWPORT))
    expect(result.current.state).toMatchObject({ status: 'error', errorMessage: '조회 실패' })
    rerender({
      filters: { regionCode: '41' },
      appliedMap: individualMap(['1', '2', '3'], VIEWPORT, originalFilters),
      paginationPaused: false,
    })
    await act(async () => result.current.retry())
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(
      VIEWPORT.bounds, null, 20, expect.any(AbortSignal), originalFilters,
    )
    expect(result.current.state).toMatchObject({ status: 'ready', totalCount: 3, errorMessage: null })
  })

  it('더보기 실패는 기존 행·필터·전체 수를 유지하고 실패한 cursor에서 재시도한다', async () => {
    const repository = createRepository()
    repository.findComplexPage
      .mockResolvedValueOnce(page(['1'], 'next'))
      .mockRejectedValueOnce({ failure: 'offline' })
      .mockResolvedValueOnce(page(['1', '2'], 'last'))
      .mockResolvedValueOnce(page(['3']))
    const filters = { minDeposit: 0 }
    const onViewportApplied = vi.fn()
    const { result } = renderResults(repository, {
      filters, appliedMap: individualMap(['1', '2', '3', '4'], VIEWPORT, filters), onViewportApplied,
    })
    await act(async () => result.current.request(VIEWPORT))
    await act(async () => result.current.loadMore())
    expect(result.current.state).toMatchObject({
      status: 'error', totalCount: 4, errorMessage: '잠시 후 다시 시도해 주세요.', nextCursor: 'next',
    })
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['1'])
    act(() => result.current.loadMore())
    expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    await act(async () => result.current.retry())
    expect(repository.findComplexPage).toHaveBeenLastCalledWith(
      VIEWPORT.bounds, 'next', 20, expect.any(AbortSignal), filters,
    )
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['1', '2'])
    expect(result.current.state.totalCount).toBe(4)
    await act(async () => result.current.loadMore())
    expect(result.current.state).toMatchObject({ totalCount: 3, nextCursor: null, hasNext: false })
    expect(onViewportApplied).toHaveBeenCalledOnce()
  })

  it.each(['success', 'error'] as const)('다음 페이지를 기다리다 새 영역을 요청하면 늦은 페이지 %s를 무시한다', async (outcome) => {
    const pending = deferred<ComplexPage>()
    const repository = createRepository()
    repository.findComplexPage
      .mockResolvedValueOnce(page(['1'], 'next'))
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(page(['3']))
    const { result } = renderResults(repository)
    await act(async () => result.current.request(VIEWPORT))
    act(() => result.current.loadMore())
    const pageSignal = repository.findComplexPage.mock.calls[1][3]
    act(() => result.current.loadMore())
    expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    await act(async () => result.current.request(NEXT_VIEWPORT))
    expect(pageSignal.aborted).toBe(true)
    await act(async () => {
      if (outcome === 'success') pending.resolve(page(['2']))
      else pending.reject(new Error('old page failed'))
    })
    expect(result.current.state).toMatchObject({ status: 'ready', totalCount: 1, errorMessage: null })
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['3'])
  })

  it('다음 페이지 취소는 기존 행과 cursor를 유지하고 취소된 응답이 도착해도 재요청 결과를 보존한다', async () => {
    const pending = deferred<ComplexPage>()
    const repository = createRepository()
    repository.findComplexPage
      .mockResolvedValueOnce(page(['1'], 'next'))
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(page(['3']))
    const { result } = renderResults(repository)
    await act(async () => result.current.request(VIEWPORT))
    act(() => result.current.loadMore())
    act(() => result.current.cancel())
    expect(repository.findComplexPage.mock.calls[1][3].aborted).toBe(true)
    expect(result.current.state).toMatchObject({ status: 'ready', nextCursor: 'next' })
    await act(async () => result.current.loadMore())
    await act(async () => pending.resolve(page(['2'])))
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['1', '3'])
  })

  it('지도 갱신 중에는 더보기와 실패한 페이지 재시도를 보류한다', async () => {
    const repository = createRepository()
    repository.findComplexPage
      .mockResolvedValueOnce(page(['1'], 'next'))
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(page(['2']))
    const { result, rerender } = renderResults(repository)
    await act(async () => result.current.request(VIEWPORT))
    rerender({ filters: {}, appliedMap: null, paginationPaused: true })
    act(() => result.current.loadMore())
    expect(repository.findComplexPage).toHaveBeenCalledOnce()
    rerender({ filters: {}, appliedMap: null, paginationPaused: false })
    await act(async () => result.current.loadMore())
    rerender({ filters: {}, appliedMap: null, paginationPaused: true })
    act(() => result.current.retry())
    expect(repository.findComplexPage).toHaveBeenCalledTimes(2)
    rerender({ filters: {}, appliedMap: null, paginationPaused: false })
    await act(async () => result.current.retry())
    expect(result.current.state.items.map((item) => item.complexId)).toEqual(['1', '2'])
  })

  it.each([
    { label: '일치하는 지도 중복 ID 제외', map: individualMap(['1', '1', '2']), expected: 2 },
    { label: '좌표 반올림이 같은 지도', map: individualMap(['1', '2'], {
      ...VIEWPORT, bounds: { ...VIEWPORT.bounds, southWestLat: 37.5000001 },
    }), expected: 2 },
    { label: '다른 지도 범위', map: individualMap(['1', '2'], NEXT_VIEWPORT), expected: null },
    { label: '다른 지도 필터', map: individualMap(['1', '2'], VIEWPORT, { regionCode: '11' }), expected: null },
    { label: '지도 응답 없음', map: null, expected: null },
    { label: '지역 집계 응답', map: {
      query: { bounds: VIEWPORT.bounds, zoom: VIEWPORT.zoom },
      result: { representation: 'AGGREGATE', resolvedStage: 1, nodes: [], policyVersion: 'test', regionDatasetVersion: 'test' },
    } satisfies AppliedHousingMapResult, expected: null },
  ])('다음 페이지가 있을 때 전체 수 계산: $label', async ({ map, expected }) => {
    const repository = createRepository()
    repository.findComplexPage.mockResolvedValueOnce(page(['1'], 'next'))
    const { result } = renderResults(repository, { appliedMap: map })
    await act(async () => result.current.request(VIEWPORT))
    expect(result.current.state.totalCount).toBe(expected)
  })

  it('마지막 첫 페이지는 지도 개수보다 실제 조회 결과 수를 사용한다', async () => {
    const { result } = renderResults(createRepository(), { appliedMap: individualMap(['1', '2', '3']) })
    await act(async () => result.current.request(VIEWPORT))
    expect(result.current.state.totalCount).toBe(1)
  })

  it.each(['first', 'next'] as const)('unmount에서 진행 중인 %s 페이지를 취소하고 늦은 성공을 적용하지 않는다', async (kind) => {
    const pending = deferred<ComplexPage>()
    const repository = createRepository()
    if (kind === 'next') repository.findComplexPage.mockResolvedValueOnce(page(['1'], 'next'))
    repository.findComplexPage.mockReturnValueOnce(pending.promise)
    const onViewportApplied = vi.fn()
    const { result, unmount } = renderResults(repository, { onViewportApplied })
    if (kind === 'next') {
      await act(async () => result.current.request(VIEWPORT))
      act(() => result.current.loadMore())
    } else {
      act(() => result.current.request(VIEWPORT))
    }
    const lastCall = repository.findComplexPage.mock.calls.at(-1)
    expect(lastCall).toBeDefined()
    const callbackCount = onViewportApplied.mock.calls.length
    unmount()
    expect(lastCall?.[3].aborted).toBe(true)
    await act(async () => pending.resolve(page(['2'])))
    expect(onViewportApplied).toHaveBeenCalledTimes(callbackCount)
  })
})

function renderResults(
  repository: Pick<PublicHousingRepository, 'findComplexPage'>,
  overrides: Partial<Parameters<typeof useComplexResults>[1]> = {},
) {
  return renderHook((options: Parameters<typeof useComplexResults>[1]) => useComplexResults(repository, options), {
    initialProps: { filters: {}, appliedMap: null, paginationPaused: false, ...overrides },
  })
}

function createRepository() {
  return { findComplexPage: vi.fn<PublicHousingRepository['findComplexPage']>().mockResolvedValue(page(['1'])) }
}

function page(ids: readonly string[], nextCursor: string | null = null): ComplexPage {
  const rawItems = ids.map((id): RawComplexListItem => ({
    complexId: Number(id), name: `단지 ${id}`, thumbnailImageUrl: null,
    regionName: null, rentalType: null, agency: null,
    exclusiveAreaMin: null, exclusiveAreaMax: null, depositMin: null, depositMax: null,
    monthlyRentMin: null, monthlyRentMax: null, representativeAnnouncement: null,
  }))
  return {
    items: rawItems.map((raw) => ({
      ...raw, complexId: String(raw.complexId), representativeAnnouncement: null, raw,
    })),
    hasNext: nextCursor !== null, nextCursor,
    raw: { items: rawItems, hasNext: nextCursor !== null, nextCursor },
  }
}

function individualMap(
  ids: readonly string[],
  viewport: ViewportSnapshot = VIEWPORT,
  filters: ComplexSearchFilters = {},
): AppliedHousingMapResult {
  return {
    query: { bounds: viewport.bounds, zoom: viewport.zoom, filters },
    result: {
      representation: 'INDIVIDUAL', resolvedStage: 4, policyVersion: 'test', regionDatasetVersion: 'test',
      nodes: page(ids).items.map((item) => {
        const raw = { ...item.raw, latitude: 37.55, longitude: 126.95 }
        return { ...raw, type: 'INDIVIDUAL', complexId: item.complexId, raw }
      }),
    },
  }
}

function deferred<Value>() {
  let resolve: (value: Value) => void = () => { throw new Error('Promise not initialized') }
  let reject: (reason: unknown) => void = () => { throw new Error('Promise not initialized') }
  const promise = new Promise<Value>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}
