import { describe, expect, it, vi } from 'vitest'
import type { MapBounds } from '../model/publicHousing.ts'
import { PublicHousingContractError } from './publicHousingContract.ts'
import {
  createHttpPublicHousingRepository,
  PublicHousingHttpError,
} from './publicHousingRepository.ts'
import {
  createHttpHousingMapRepository,
  type HousingMapQuery,
} from './housingMapRepository.ts'

const BOUNDS: MapBounds = {
  southWestLat: 37.4,
  southWestLng: 126.8,
  northEastLat: 37.6,
  northEastLng: 127.1,
}

const FILTERS = {
  agencyCodes: ['LH'],
  applicationStatuses: ['APPLYING'],
  builtYearFrom: 2015,
  builtYearTo: 2026,
  maxDeposit: 30_000_000,
  maxExclusiveArea: 60,
  maxMonthlyRent: 500_000,
  minDeposit: 1_000_000,
  minExclusiveArea: 20,
  minMonthlyRent: 100_000,
  recruitmentTypes: ['NEW', 'WAITLIST'],
  regionCode: '41',
  rentalTypes: ['NATIONAL_RENTAL', 'HAPPY_HOUSING'],
} as const

describe('지도 v2 HTTP repository', () => {
  it('bounds, zoom, 직전 단계, 필터와 AbortSignal로 조회한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(aggregateResponse())
    const repository = createRepository(fetchMock)
    const controller = new AbortController()

    await repository.findMap({
      bounds: BOUNDS,
      zoom: 10.25,
      previousResolvedStage: 2,
      filters: FILTERS,
    }, controller.signal)

    const [requestUrl, requestInit] = fetchMock.mock.calls[0] ?? []
    const url = new URL(String(requestUrl))
    expect(url.pathname).toBe('/api/v2/complexes/map')
    expect(url.searchParams.get('zoom')).toBe('10.25')
    expect(url.searchParams.get('previousResolvedStage')).toBe('2')
    expect(requestInit).toEqual(
      expect.objectContaining({ signal: controller.signal }),
    )
  })

  it('v1 지도와 같은 bounds 및 필터 query 직렬화를 사용한다', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(v1MapResponse())
      .mockResolvedValueOnce(aggregateResponse())
    const v1Repository = createHttpPublicHousingRepository({
      apiBaseUrl: 'https://api.example.test',
      fetcher: fetchMock as unknown as typeof globalThis.fetch,
    })
    const v2Repository = createRepository(fetchMock)
    const signal = new AbortController().signal

    await v1Repository.findMapComplexes(BOUNDS, signal, FILTERS)
    await v2Repository.findMap({
      bounds: BOUNDS,
      zoom: 9,
      previousResolvedStage: 1,
      filters: FILTERS,
    }, signal)

    const [v1Url] = fetchMock.mock.calls[0] ?? []
    const [v2Url] = fetchMock.mock.calls[1] ?? []
    const v1Params = entries(new URL(String(v1Url)).searchParams)
    const v2Search = new URL(String(v2Url)).searchParams
    v2Search.delete('zoom')
    v2Search.delete('previousResolvedStage')

    expect(entries(v2Search)).toEqual(v1Params)
  })

  it.each([
    ['생략하면', undefined],
    ['null이면', null],
  ] as const)('직전 단계를 %s query에서 제외한다', async (_label, stage) => {
    const fetchMock = vi.fn().mockResolvedValue(aggregateResponse())
    const repository = createRepository(fetchMock)

    await repository.findMap({
      bounds: BOUNDS,
      zoom: 9,
      previousResolvedStage: stage,
    }, new AbortController().signal)

    const [requestUrl] = fetchMock.mock.calls[0] ?? []
    const search = new URL(String(requestUrl)).searchParams
    expect(search.has('previousResolvedStage')).toBe(false)
  })

  it.each([
    ['zoom', Number.NaN],
    ['zoom', Number.POSITIVE_INFINITY],
    ['zoom', -0.01],
    ['previousResolvedStage', 0],
    ['previousResolvedStage', 5],
  ])('잘못된 %s 값은 요청 전에 거부한다', async (field, value) => {
    const fetchMock = vi.fn()
    const repository = createRepository(fetchMock)

    const invalidQuery = field === 'zoom'
      ? { bounds: BOUNDS, zoom: value }
      : { bounds: BOUNDS, zoom: 9, previousResolvedStage: value }
    const query = invalidQuery as unknown as HousingMapQuery

    await expect(repository.findMap(
      query,
      new AbortController().signal,
    )).rejects.toBeInstanceOf(RangeError)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('HTTP 오류의 공개 정보를 기존 repository 오류로 전달한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      code: 'INVALID_MAP_ZOOM',
      message: '지도 확대 수준이 올바르지 않습니다.',
      traceId: 'trace-id',
    }, 400))
    const repository = createRepository(fetchMock)

    const error = await repository.findMap(
      { bounds: BOUNDS, zoom: 9 },
      new AbortController().signal,
    ).catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(PublicHousingHttpError)
    expect(error).toMatchObject({
      status: 400,
      code: 'INVALID_MAP_ZOOM',
      traceId: 'trace-id',
    })
  })

  it('성공 응답이 JSON이 아니면 계약 오류로 처리한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('not-json', { status: 200 }),
    )
    const repository = createRepository(fetchMock)

    await expect(repository.findMap(
      { bounds: BOUNDS, zoom: 9 },
      new AbortController().signal,
    )).rejects.toBeInstanceOf(PublicHousingContractError)
  })
})

function createRepository(fetchMock: ReturnType<typeof vi.fn>) {
  return createHttpHousingMapRepository({
    apiBaseUrl: 'https://api.example.test',
    fetcher: fetchMock as unknown as typeof globalThis.fetch,
  })
}

function aggregateResponse() {
  return jsonResponse({
    data: {
      resolvedStage: 1,
      representation: 'AGGREGATE',
      policyVersion: '2026-09-02-v1',
      regionDatasetVersion: '2026-07-01',
      nodes: [],
    },
  })
}

function v1MapResponse() {
  return jsonResponse({ data: { items: [] } })
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    status,
  })
}

function entries(search: URLSearchParams) {
  return [...search.entries()].sort(([leftKey, leftValue], [rightKey, rightValue]) =>
    `${leftKey}:${leftValue}`.localeCompare(`${rightKey}:${rightValue}`),
  )
}
