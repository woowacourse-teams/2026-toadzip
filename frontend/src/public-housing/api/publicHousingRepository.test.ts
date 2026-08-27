import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MapBounds } from '../model/publicHousing.ts'
import {
  decodeComplexDetailEnvelope,
  decodeComplexPageEnvelope,
  PublicHousingContractError,
} from './publicHousingContract.ts'
import {
  createHttpPublicHousingRepository,
  PublicHousingHttpError,
} from './publicHousingRepository.ts'

const BOUNDS: MapBounds = {
  southWestLat: 37.4,
  southWestLng: 126.8,
  northEastLat: 37.6,
  northEastLng: 127.1,
}

const LIST_ITEM = {
  complexId: 17,
  thumbnailImageUrl: null,
  regionName: '서울특별시 중구',
  name: '행복 단지',
  rentalType: 'HAPPY_HOUSING',
  agency: { code: 'LH', name: '한국토지주택공사' },
  exclusiveAreaMin: 0,
  exclusiveAreaMax: 44.87,
  depositMin: 0,
  depositMax: null,
  monthlyRentMin: 200000,
  monthlyRentMax: 300000,
  representativeAnnouncement: {
    announcementId: 117,
    publicationType: 'ORIGINAL',
    applicationStatus: 'APPLYING',
    applicationEndAt: '2026-08-27',
    dDay: 0,
  },
}

const COMPLEX_DETAIL = {
  complexId: 17,
  name: '행복 단지',
  rentalType: 'HAPPY_HOUSING',
  agency: { code: 'LH', name: '한국토지주택공사' },
  address: {
    regionName: '서울특별시 중구',
    roadAddress: '서울특별시 중구 세종대로 110',
    latitude: 37.5,
    longitude: 126.9,
  },
  completionDate: null,
  buildingType: 'APARTMENT',
  hasElevator: false,
  heatingType: 'INDIVIDUAL',
  corridorType: 'STAIR',
  moveOutCountLastYear: 0,
  totalHouseholdCount: 100,
  totalParkingCount: 0,
  images: [],
  overviewImageUrl: null,
  housingTypes: [
    {
      housingTypeId: 101,
      name: null,
      exclusiveArea: 36.12,
      supplyArea: null,
      floorPlanImageUrl: null,
      floorPlan3dImageUrl: null,
      isDuplex: false,
      maintenanceFee: 0,
      currentSupplyConditions: [
        {
          target: null,
          deposit: 0,
          monthlyRent: null,
          convertibleDeposit: null,
        },
      ],
    },
  ],
  currentAnnouncements: [
    {
      announcementId: 201,
      title: null,
      publicationType: 'ORIGINAL',
      applicationStatus: 'APPLYING',
      targets: [],
      applicationStartAt: null,
      applicationEndAt: '2026-08-27',
      dDay: 0,
      actualCompetitionRate: 0,
    },
  ],
}

afterEach(() => {
  vi.unstubAllEnvs()
  vi.resetModules()
})

describe('공공주택 HTTP repository', () => {
  it('단지 상세의 full DTO를 보존하고 중첩 ID를 canonical string으로 변환한다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ data: COMPLEX_DETAIL }))
    const repository = createRepository(fetchMock, 'https://api.example.test')
    const controller = new AbortController()

    const detail = await repository.findComplexDetail('17', controller.signal)

    expect(fetchMock).toHaveBeenCalledWith(
      'https://api.example.test/api/v1/complexes/17',
      expect.objectContaining({ signal: controller.signal }),
    )
    expect(detail).toMatchObject({
      complexId: '17',
      completionDate: null,
      hasElevator: false,
      moveOutCountLastYear: 0,
      totalParkingCount: 0,
      images: [],
      housingTypes: [
        {
          housingTypeId: '101',
          isDuplex: false,
          maintenanceFee: 0,
          currentSupplyConditions: [{ deposit: 0 }],
        },
      ],
      currentAnnouncements: [
        {
          announcementId: '201',
          targets: [],
          dDay: 0,
          actualCompetitionRate: 0,
        },
      ],
    })
    expect(detail.raw).toEqual(COMPLEX_DETAIL)
  })

  it('canonical positive Java Long이 아닌 상세 ID는 요청하지 않는다', async () => {
    const fetchMock = vi.fn()
    const repository = createRepository(fetchMock, '')

    for (const invalidId of [
      '0',
      '-1',
      '01',
      '1e3',
      '9223372036854775808',
    ]) {
      await expect(
        repository.findComplexDetail(
          invalidId,
          new AbortController().signal,
        ),
      ).rejects.toBeInstanceOf(RangeError)
    }
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('Java Long 범위의 canonical ID는 JS safe integer보다 커도 경로로 전달한다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ data: COMPLEX_DETAIL }))
    const repository = createRepository(fetchMock, '')

    await repository.findComplexDetail(
      '9223372036854775807',
      new AbortController().signal,
    )

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/complexes/9223372036854775807',
      expect.any(Object),
    )
  })

  it('없는 단지 상세 404를 계약 오류와 구분되는 HTTP 오류로 전달한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          code: 'COMPLEX_NOT_FOUND',
          message: '단지를 찾을 수 없습니다.',
          traceId: 'trace-id',
        },
        404,
      ),
    )
    const repository = createRepository(fetchMock, '')

    const error = await repository
      .findComplexDetail('999', new AbortController().signal)
      .catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(PublicHousingHttpError)
    expect(error).not.toBeInstanceOf(PublicHousingContractError)
    expect(error).toMatchObject({
      status: 404,
      code: 'COMPLEX_NOT_FOUND',
      message: '단지를 찾을 수 없습니다.',
    })
  })

  it('bounds, cursor, size와 AbortSignal로 목록을 조회하고 ID를 문자열로 변환한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        data: {
          items: [LIST_ITEM],
          nextCursor: 'opaque-cursor',
          hasNext: true,
        },
      }),
    )
    const repository = createRepository(fetchMock, 'https://api.example.test')
    const controller = new AbortController()

    const page = await repository.findComplexPage(
      BOUNDS,
      'opaque+/cursor',
      20,
      controller.signal,
    )

    const [requestUrl, requestInit] = fetchMock.mock.calls[0] ?? []
    const url = new URL(String(requestUrl))
    expect(url.pathname).toBe('/api/v1/complexes')
    expect(Object.fromEntries(url.searchParams)).toEqual({
      southWestLat: '37.4',
      southWestLng: '126.8',
      northEastLat: '37.6',
      northEastLng: '127.1',
      cursor: 'opaque+/cursor',
      size: '20',
    })
    expect(requestInit).toEqual(
      expect.objectContaining({ signal: controller.signal }),
    )
    expect(page.items[0]).toMatchObject({
      complexId: '17',
      exclusiveAreaMin: 0,
      depositMin: 0,
      depositMax: null,
      representativeAnnouncement: {
        announcementId: '117',
        dDay: 0,
      },
    })
    expect(page.raw.items[0]).toEqual(LIST_ITEM)
  })

  it('지도 응답 순서를 보존하되 범위를 벗어난 개별 좌표만 제외한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        data: {
          items: [
            mapItem({ complexId: 9, latitude: 37.5, longitude: 126.9 }),
            mapItem({ complexId: 7, latitude: 91, longitude: 126.91 }),
            mapItem({ complexId: 3, latitude: 37.51, longitude: 126.92 }),
          ],
        },
      }),
    )
    const repository = createRepository(fetchMock, '')

    const complexes = await repository.findMapComplexes(
      BOUNDS,
      new AbortController().signal,
    )

    expect(complexes.map((complex) => complex.complexId)).toEqual(['9', '3'])
    expect(complexes[0]).toMatchObject({
      latitude: 37.5,
      longitude: 126.9,
      depositMin: 0,
      depositMax: null,
    })
  })

  it('서버 오류의 공개 필드를 HTTP 오류로 전달한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          code: 'INVALID_MAP_BOUNDS',
          message: '지도 범위 좌표가 올바르지 않습니다.',
          traceId: 'trace-id',
          errors: [],
        },
        400,
      ),
    )
    const repository = createRepository(fetchMock, '')

    const request = repository.findMapComplexes(
      BOUNDS,
      new AbortController().signal,
    )

    const error = await request.catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(PublicHousingHttpError)
    expect(error).toMatchObject({
      name: 'PublicHousingHttpError',
      status: 400,
      code: 'INVALID_MAP_BOUNDS',
      message: '지도 범위 좌표가 올바르지 않습니다.',
      traceId: 'trace-id',
    })
  })

  it('성공 응답이 JSON이 아니면 계약 오류로 처리한다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response('not-json', { status: 200 }))
    const repository = createRepository(fetchMock, '')

    await expect(
      repository.findMapComplexes(BOUNDS, new AbortController().signal),
    ).rejects.toBeInstanceOf(PublicHousingContractError)
  })

  it('목록 크기는 백엔드 계약인 1부터 50 사이만 요청한다', async () => {
    const fetchMock = vi.fn()
    const repository = createRepository(fetchMock, '')

    await expect(
      repository.findComplexPage(
        BOUNDS,
        null,
        51,
        new AbortController().signal,
      ),
    ).rejects.toBeInstanceOf(RangeError)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('개발 환경에서 API 주소가 없으면 로컬 백엔드를 사용한다', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_API_BASE_URL', '')
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ data: { items: [], nextCursor: null, hasNext: false } }),
    )
    const { createHttpPublicHousingRepository: createRepositoryFromEnv } =
      await import('./publicHousingRepository.ts')
    const repository = createRepositoryFromEnv({
      fetcher: fetchMock as unknown as typeof globalThis.fetch,
    })

    await repository.findComplexPage(
      BOUNDS,
      null,
      20,
      new AbortController().signal,
    )

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringMatching(/^http:\/\/localhost:8080\/api\/v1\/complexes\?/),
      expect.any(Object),
    )
  })

  it('프로덕션에서 API 주소가 없으면 same-origin을 사용한다', async () => {
    vi.stubEnv('DEV', false)
    vi.stubEnv('VITE_API_BASE_URL', '')
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ data: { items: [], nextCursor: null, hasNext: false } }),
    )
    const { createHttpPublicHousingRepository: createRepositoryFromEnv } =
      await import('./publicHousingRepository.ts')
    const repository = createRepositoryFromEnv({
      fetcher: fetchMock as unknown as typeof globalThis.fetch,
    })

    await repository.findComplexPage(
      BOUNDS,
      null,
      20,
      new AbortController().signal,
    )

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringMatching(/^\/api\/v1\/complexes\?/),
      expect.any(Object),
    )
  })
})

describe('공공주택 응답 계약', () => {
  it('상세의 중첩 ID도 safe positive integer로 검증한다', () => {
    expect(() =>
      decodeComplexDetailEnvelope({
        data: {
          ...COMPLEX_DETAIL,
          housingTypes: [
            {
              ...COMPLEX_DETAIL.housingTypes[0],
              housingTypeId: Number.MAX_SAFE_INTEGER + 1,
            },
          ],
        },
      }),
    ).toThrowError(
      expect.objectContaining<Partial<PublicHousingContractError>>({
        path: '$.data.housingTypes[0].housingTypeId',
      }),
    )
  })

  it('상세의 null, false, 0과 빈 배열을 그대로 보존한다', () => {
    const decoded = decodeComplexDetailEnvelope({ data: COMPLEX_DETAIL })

    expect(decoded).toMatchObject({
      completionDate: null,
      hasElevator: false,
      moveOutCountLastYear: 0,
      totalParkingCount: 0,
      images: [],
      housingTypes: [
        {
          name: null,
          isDuplex: false,
          maintenanceFee: 0,
          currentSupplyConditions: [{ target: null, deposit: 0 }],
        },
      ],
      currentAnnouncements: [
        { title: null, targets: [], dDay: 0, actualCompetitionRate: 0 },
      ],
    })
  })

  it('빈 배열, null과 false를 빈 성공 응답으로 보존한다', () => {
    const decoded = decodeComplexPageEnvelope({
      data: { items: [], nextCursor: null, hasNext: false },
    })

    expect(decoded).toEqual({ items: [], nextCursor: null, hasNext: false })
  })

  it('표시 속성의 명시적인 null을 추정값으로 바꾸지 않는다', () => {
    const decoded = decodeComplexPageEnvelope({
      data: {
        items: [
          {
            ...LIST_ITEM,
            agency: null,
            name: null,
            regionName: null,
            rentalType: null,
            representativeAnnouncement: {
              ...LIST_ITEM.representativeAnnouncement,
              applicationEndAt: null,
              applicationStatus: null,
              publicationType: null,
            },
          },
        ],
        nextCursor: null,
        hasNext: false,
      },
    })

    expect(decoded.items[0]).toMatchObject({
      agency: null,
      name: null,
      regionName: null,
      rentalType: null,
      representativeAnnouncement: {
        applicationEndAt: null,
        applicationStatus: null,
        publicationType: null,
      },
    })
  })

  it('data envelope가 없으면 계약 오류로 거절한다', () => {
    expect(() =>
      decodeComplexPageEnvelope({
        items: [],
        nextCursor: null,
        hasNext: false,
      }),
    ).toThrowError(
      expect.objectContaining<Partial<PublicHousingContractError>>({
        path: '$.data',
      }),
    )
  })

  it('safe positive integer가 아닌 ID를 거절한다', () => {
    expect(() =>
      decodeComplexPageEnvelope({
        data: {
          items: [
            { ...LIST_ITEM, complexId: Number.MAX_SAFE_INTEGER + 1 },
          ],
          nextCursor: null,
          hasNext: false,
        },
      }),
    ).toThrowError(
      expect.objectContaining<Partial<PublicHousingContractError>>({
        path: '$.data.items[0].complexId',
      }),
    )
  })

  it('nullable 필드가 누락된 경우 null로 추정하지 않는다', () => {
    const { depositMax: _omitted, ...missingDepositMax } = LIST_ITEM

    expect(() =>
      decodeComplexPageEnvelope({
        data: {
          items: [missingDepositMax],
          nextCursor: null,
          hasNext: false,
        },
      }),
    ).toThrowError(
      expect.objectContaining<Partial<PublicHousingContractError>>({
        path: '$.data.items[0].depositMax',
      }),
    )
  })
})

function createRepository(fetchMock: ReturnType<typeof vi.fn>, apiBaseUrl: string) {
  return createHttpPublicHousingRepository({
    apiBaseUrl,
    fetcher: fetchMock as unknown as typeof globalThis.fetch,
  })
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    status,
  })
}

function mapItem({
  complexId,
  latitude,
  longitude,
}: {
  complexId: number
  latitude: number
  longitude: number
}) {
  return {
    complexId,
    name: `단지 ${complexId}`,
    latitude,
    longitude,
    rentalType: 'HAPPY_HOUSING',
    agency: { code: 'LH', name: '한국토지주택공사' },
    exclusiveAreaMin: null,
    exclusiveAreaMax: null,
    depositMin: 0,
    depositMax: null,
    monthlyRentMin: null,
    monthlyRentMax: null,
  }
}
