import { StrictMode, useEffect } from 'react'
import { act, render, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { HousingMapRepository } from '../api/housingMapRepository.ts'
import type {
  HousingMapAggregateResult,
  HousingMapIndividualResult,
} from '../model/housingMap.ts'
import type { MapBounds } from '../model/publicHousing.ts'
import { useHousingMapResults } from './useHousingMapResults.ts'

const bounds: MapBounds = {
  southWestLat: 37.4,
  southWestLng: 126.8,
  northEastLat: 37.7,
  northEastLng: 127.2,
}

const aggregateResult: HousingMapAggregateResult = {
  nodes: [],
  policyVersion: 'map-clustering-v1',
  regionDatasetVersion: '2026-07-01',
  representation: 'AGGREGATE',
  resolvedStage: 2,
}

const individualResult: HousingMapIndividualResult = {
  nodes: [],
  policyVersion: 'map-clustering-v1',
  regionDatasetVersion: '2026-07-01',
  representation: 'INDIVIDUAL',
  resolvedStage: 4,
}

describe('useHousingMapResults', () => {
  it('정책을 바꾸면 이전 단계와 캐시를 섞지 않고 현재 영역을 새 정책으로 요청한다', async () => {
    const repository = createRepository()
    repository.findMap.mockResolvedValue(aggregateResult)
    const { result, rerender } = renderHook(({ profile }: { profile: 'DEFAULT' | 'MOBILE' }) =>
      useHousingMapResults(repository, profile), { initialProps: { profile: 'DEFAULT' } })
    await act(async () => { result.current.request(viewport(12)) })
    rerender({ profile: 'MOBILE' })
    await act(async () => { result.current.request(viewport(12)) })
    expect(repository.findMap).toHaveBeenCalledTimes(2)
    expect(repository.findMap.mock.calls[1]?.[0]).toMatchObject({ clusteringProfile: 'MOBILE', zoom: 12 })
    expect(repository.findMap.mock.calls[1]?.[0]).not.toHaveProperty('previousResolvedStage')
  })
  afterEach(() => vi.restoreAllMocks())

  it('최초 요청에는 직전 단계가 없고 다음 요청에는 마지막 확정 단계를 사용한다', async () => {
    const repository = createRepository()
    repository.findMap
      .mockResolvedValueOnce(aggregateResult)
      .mockResolvedValueOnce(individualResult)
    const { result } = renderHook(() => useHousingMapResults(repository))

    act(() => result.current.request(viewport(8)))
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    act(() => result.current.request(viewport(14)))
    await waitFor(() => expect(repository.findMap).toHaveBeenCalledTimes(2))

    expect(repository.findMap.mock.calls[0]?.[0]).not.toHaveProperty(
      'previousResolvedStage',
    )
    expect(repository.findMap.mock.calls[1]?.[0]).toMatchObject({
      previousResolvedStage: 2,
      zoom: 14,
    })
  })

  it('같은 viewport와 필터의 중복 요청은 보내지 않는다', async () => {
    const repository = createRepository()
    repository.findMap.mockResolvedValue(aggregateResult)
    const { result } = renderHook(() => useHousingMapResults(repository))
    const outcomes: string[] = []

    act(() => {
      outcomes.push(result.current.request(viewport(8), { regionCode: '41' }))
      outcomes.push(result.current.request(viewport(8), { regionCode: '41' }))
    })
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    act(() => {
      outcomes.push(result.current.request(viewport(8), { regionCode: '41' }))
    })

    expect(repository.findMap).toHaveBeenCalledOnce()
    expect(outcomes).toEqual(['started', 'pending', 'applied'])
  })

  it('새 요청이 이전 요청을 취소하고 늦은 응답을 버린다', async () => {
    const first = deferred<HousingMapAggregateResult>()
    const second = deferred<HousingMapIndividualResult>()
    const repository = createRepository()
    repository.findMap
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    const { result } = renderHook(() => useHousingMapResults(repository))

    act(() => result.current.request(viewport(8)))
    const firstSignal = repository.findMap.mock.calls[0]?.[1]
    act(() => result.current.request(viewport(14)))

    expect(firstSignal?.aborted).toBe(true)
    await act(() => second.resolve(individualResult))
    await waitFor(() => expect(result.current.state.applied?.result)
      .toBe(individualResult))
    await act(() => first.resolve(aggregateResult))

    expect(result.current.state.applied?.result).toBe(individualResult)
  })

  it('다른 영역 조회를 마친 뒤 돌아오면 이전 결과를 로딩 없이 즉시 재사용한다', async () => {
    const repository = createRepository()
    const otherResult = { ...aggregateResult, nodes: [] }
    repository.findMap.mockResolvedValueOnce(aggregateResult).mockResolvedValueOnce(otherResult)
    const { result } = renderHook(() => useHousingMapResults(repository))
    const first = viewport(8)
    const second = { ...first, bounds: { ...bounds, southWestLng: 126.7 } }

    await act(async () => { result.current.request(first) })
    await act(async () => { result.current.request(second) })
    expect(result.current.state.applied?.result).toBe(otherResult)

    act(() => { expect(result.current.request(first)).toBe('applied') })

    expect(result.current.state.status).toBe('ready')
    expect(result.current.state.applied).toEqual({ query: { bounds, zoom: 8 }, result: aggregateResult })
    expect(result.current.state.applied?.result).toBe(aggregateResult)
    expect(repository.findMap).toHaveBeenCalledTimes(2)
  })

  it('필터별 결과를 구분하고 이전 필터로 돌아오면 해당 결과를 재사용한다', async () => {
    const repository = createRepository()
    const filteredResult = { ...aggregateResult, nodes: [] }
    repository.findMap.mockResolvedValueOnce(aggregateResult).mockResolvedValueOnce(filteredResult)
    const { result } = renderHook(() => useHousingMapResults(repository))

    await act(async () => { result.current.request(viewport(8)) })
    await act(async () => { result.current.request(viewport(8), { minDeposit: 100000000 }) })
    expect(result.current.state.applied?.result).toBe(filteredResult)
    act(() => { expect(result.current.request(viewport(8))).toBe('applied') })

    expect(result.current.state.applied?.result).toBe(aggregateResult)
    expect(repository.findMap).toHaveBeenCalledTimes(2)
  })

  it('줌이나 직전 집계 단계가 다르면 조회하고 이미 확인한 단계 전환만 재사용한다', async () => {
    const repository = createRepository()
    repository.findMap.mockResolvedValueOnce(aggregateResult)
      .mockResolvedValueOnce(individualResult)
      .mockResolvedValueOnce(aggregateResult)
    const { result } = renderHook(() => useHousingMapResults(repository))

    await act(async () => { result.current.request(viewport(8)) })
    await act(async () => { result.current.request(viewport(14)) })
    // A different previous stage may change the result at a zoom boundary.
    await act(async () => { expect(result.current.request(viewport(8))).toBe('started') })
    expect(repository.findMap).toHaveBeenLastCalledWith(
      { bounds, zoom: 8, previousResolvedStage: 4 }, expect.any(AbortSignal),
    )
    act(() => { expect(result.current.request(viewport(14))).toBe('applied') })
    expect(result.current.state.applied?.result).toBe(individualResult)
    act(() => { expect(result.current.request(viewport(8))).toBe('applied') })
    expect(result.current.state.applied?.result).toBe(aggregateResult)
    expect(repository.findMap).toHaveBeenCalledTimes(3)
  })

  it('저장된 영역으로 복귀하면 진행 중인 요청을 취소하고 늦은 응답을 캐시에 넣지 않는다', async () => {
    const pending = deferred<HousingMapAggregateResult>()
    const repository = createRepository()
    const otherResult = { ...aggregateResult, nodes: [] }
    repository.findMap.mockResolvedValueOnce(aggregateResult)
      .mockResolvedValueOnce(otherResult)
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(otherResult)
    const { result } = renderHook(() => useHousingMapResults(repository))

    await act(async () => { result.current.request(viewport(8)) })
    await act(async () => { result.current.request(viewport(9)) })
    act(() => result.current.request(viewport(10)))
    const pendingSignal = repository.findMap.mock.calls[2]?.[1]
    act(() => { expect(result.current.request(viewport(8))).toBe('applied') })
    expect(pendingSignal.aborted).toBe(true)
    await act(() => pending.resolve(otherResult))
    expect(result.current.state.applied?.result).toBe(aggregateResult)
    await act(async () => { expect(result.current.request(viewport(10))).toBe('started') })
    expect(repository.findMap).toHaveBeenCalledTimes(4)
  })

  it('재사용해도 최초 수신 시각부터 5분이 지나면 이전 결과를 유지하며 다시 조회한다', async () => {
    let now = 1000
    vi.spyOn(Date, 'now').mockImplementation(() => now)
    const refreshed = deferred<HousingMapAggregateResult>()
    const updatedResult = { ...aggregateResult, nodes: [] }
    const repository = createRepository()
    repository.findMap.mockResolvedValueOnce(aggregateResult).mockReturnValueOnce(refreshed.promise)
    const { result } = renderHook(() => useHousingMapResults(repository))

    await act(async () => { result.current.request(viewport(8)) })
    now += 4 * 60 * 1000
    act(() => { expect(result.current.request(viewport(8))).toBe('applied') })
    now += 60 * 1000
    act(() => { expect(result.current.request(viewport(8))).toBe('started') })
    expect(result.current.state.status).toBe('loading')
    expect(result.current.state.applied?.result).toBe(aggregateResult)
    await act(() => refreshed.resolve(updatedResult))
    expect(result.current.state.status).toBe('ready')
    expect(result.current.state.applied?.result).toBe(updatedResult)
  })

  it('최근 사용한 20개 결과만 남기고 밀려난 영역은 다시 조회한다', async () => {
    const repository = createRepository()
    repository.findMap.mockResolvedValue(aggregateResult)
    const { result } = renderHook(() => useHousingMapResults(repository))
    const views = Array.from({ length: 21 }, (_, index) => ({
      ...viewport(8), bounds: { ...bounds, southWestLng: 126.8 - index * 0.01 },
    }))
    for (const view of views.slice(0, 20)) {
      await act(async () => { result.current.request(view) })
    }
    act(() => { expect(result.current.request(views[0])).toBe('applied') })
    await act(async () => { result.current.request(views[20]) })
    act(() => { expect(result.current.request(views[0])).toBe('applied') })
    await act(async () => { expect(result.current.request(views[1])).toBe('started') })

    expect(repository.findMap).toHaveBeenCalledTimes(22)
  })

  it.each(['policyVersion', 'regionDatasetVersion'] as const)(
    '%s가 바뀐 응답을 받으면 이전 버전의 결과는 재사용하지 않는다', async (version) => {
      const repository = createRepository()
      const updatedResult = { ...aggregateResult, [version]: 'new-version' }
      repository.findMap.mockResolvedValueOnce(aggregateResult).mockResolvedValue(updatedResult)
      const { result } = renderHook(() => useHousingMapResults(repository))

      await act(async () => { result.current.request(viewport(8)) })
      await act(async () => { result.current.request(viewport(9)) })
      await act(async () => { expect(result.current.request(viewport(8))).toBe('started') })

      expect(result.current.state.applied?.result).toBe(updatedResult)
      expect(repository.findMap).toHaveBeenCalledTimes(3)
    },
  )

  it('적용된 영역으로 복귀하면 다른 영역의 pending 요청을 취소한다', async () => {
    const pending = deferred<HousingMapIndividualResult>()
    const repository = createRepository()
    repository.findMap
      .mockResolvedValueOnce(aggregateResult)
      .mockReturnValueOnce(pending.promise)
    const { result } = renderHook(() => useHousingMapResults(repository))

    act(() => result.current.request(viewport(8)))
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    act(() => result.current.request(viewport(14)))
    const pendingSignal = repository.findMap.mock.calls[1]?.[1]
    act(() => result.current.request(viewport(8)))

    expect(pendingSignal?.aborted).toBe(true)
    expect(result.current.state.status).toBe('ready')
    await act(() => pending.resolve(individualResult))
    expect(result.current.state.applied?.result).toBe(aggregateResult)
    expect(result.current.retry()).toBe(false)
  })

  it('취소한 요청의 응답을 반영하지 않고 기존 결과를 유지한다', async () => {
    const pending = deferred<HousingMapIndividualResult>()
    const repository = createRepository()
    repository.findMap
      .mockResolvedValueOnce(aggregateResult)
      .mockReturnValueOnce(pending.promise)
    const { result } = renderHook(() => useHousingMapResults(repository))

    act(() => result.current.request(viewport(8)))
    await waitFor(() => expect(result.current.state.status).toBe('ready'))
    act(() => result.current.request(viewport(14)))
    act(() => result.current.cancel())
    await act(() => pending.resolve(individualResult))

    expect(result.current.state.status).toBe('ready')
    expect(result.current.state.applied?.result).toBe(aggregateResult)
  })

  it('실패한 요청을 같은 조건으로 다시 시도한다', async () => {
    const repository = createRepository()
    repository.findMap
      .mockRejectedValueOnce(new Error('지도 조회 실패'))
      .mockResolvedValueOnce(aggregateResult)
    const { result } = renderHook(() => useHousingMapResults(repository))

    act(() => result.current.request(viewport(8), { rentalTypes: ['HAPPY_HOUSING'] }))
    await waitFor(() => expect(result.current.state.status).toBe('error'))
    act(() => result.current.retry())
    await waitFor(() => expect(result.current.state.status).toBe('ready'))

    expect(repository.findMap).toHaveBeenCalledTimes(2)
    expect(repository.findMap.mock.calls[1]?.[0]).toEqual(
      repository.findMap.mock.calls[0]?.[0],
    )
  })

  it('유효하지 않은 viewport는 요청하지 않는다', () => {
    const repository = createRepository()
    const { result } = renderHook(() => useHousingMapResults(repository))

    act(() => result.current.request({
      ...viewport(8),
      zoom: Number.NaN,
    }))

    expect(repository.findMap).not.toHaveBeenCalled()
    expect(result.current.state.status).toBe('idle')
  })

  it('StrictMode가 effect를 다시 실행해도 동일한 초기 요청을 재개한다', async () => {
    const repository = createRepository()
    repository.findMap
      .mockImplementationOnce((_query, signal: AbortSignal) => {
        return new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(abortError()))
        })
      })
      .mockResolvedValueOnce(aggregateResult)

    render(
      <StrictMode>
        <InitialMapRequest repository={repository} />
      </StrictMode>,
    )

    await waitFor(() => expect(repository.findMap).toHaveBeenCalledTimes(2))
  })
})

function InitialMapRequest({ repository }: {
  readonly repository: HousingMapRepository
}) {
  const { request } = useHousingMapResults(repository)

  useEffect(() => {
    request(viewport(8))
  }, [request])

  return null
}

function viewport(zoom: number) {
  return {
    bounds,
    center: { latitude: 37.55, longitude: 127 },
    zoom,
  }
}

function createRepository() {
  return {
    findMap: vi.fn(),
  } as unknown as HousingMapRepository & {
    findMap: ReturnType<typeof vi.fn>
  }
}

function deferred<T>() {
  let resolvePromise: (value: T) => void = () => {
    throw new Error('Promise resolve 함수가 준비되지 않았습니다.')
  }
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve
  })
  return { promise, resolve: resolvePromise }
}

function abortError() {
  return new DOMException('요청이 취소되었습니다.', 'AbortError')
}
