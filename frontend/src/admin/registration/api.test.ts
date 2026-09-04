import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AnnouncementCreateRequest, HousingComplexCreateRequest } from './api'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('관리자 데이터 등록 API', () => {
  it('동적 CSRF 헤더와 세션 쿠키를 포함해 단지를 등록한다', async () => {
    const fetchMock = prepareFetch({
      housingComplexId: 42,
      name: '두꺼비 행복주택',
      roadAddress: '서울시 중구 세종대로 1',
    })
    const { createHousingComplex } = await import('./api.ts')
    const request = housingRequest()

    await expect(createHousingComplex(request)).resolves.toEqual({
      housingComplexId: 42,
      name: '두꺼비 행복주택',
      roadAddress: '서울시 중구 세종대로 1',
    })

    expectCsrfRequest(fetchMock)
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      'http://localhost:8080/api/admin/housing-complexes',
      {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          'X-CUSTOM-CSRF': 'csrf-token',
        },
        body: JSON.stringify(request),
      },
    )
  })

  it('동적 CSRF 헤더와 세션 쿠키를 포함해 공고를 등록한다', async () => {
    const fetchMock = prepareFetch({
      announcementId: 7,
      supplyRowId: 9,
      housingComplexId: 42,
      name: '입주자 모집',
    })
    const { createAnnouncement } = await import('./api.ts')
    const request = announcementRequest()

    await expect(createAnnouncement(request)).resolves.toEqual({
      announcementId: 7,
      supplyRowId: 9,
      housingComplexId: 42,
      name: '입주자 모집',
    })

    expectCsrfRequest(fetchMock)
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      'http://localhost:8080/api/admin/announcements',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          'X-CUSTOM-CSRF': 'csrf-token',
        },
        body: JSON.stringify(request),
      }),
    )
  })

  it('검증 오류의 필드별 사유를 보존한다', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_API_BASE_URL', '')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-CSRF' }))
      .mockResolvedValueOnce(
        jsonResponse(
          {
            code: 'VALIDATION_FAILED',
            message: '요청값이 올바르지 않습니다.',
            errors: [{ field: 'name', reason: '필수 값입니다.' }],
          },
          400,
        ),
      )
    vi.stubGlobal('fetch', fetchMock)
    const { AdminRegistrationApiError, createHousingComplex } = await import('./api.ts')

    const error = await createHousingComplex(housingRequest()).catch((caught) => caught)

    expect(error).toBeInstanceOf(AdminRegistrationApiError)
    expect(error).toMatchObject({
      status: 400,
      message: '요청값이 올바르지 않습니다.',
      fieldErrors: { name: '필수 값입니다.' },
    })
  })
})

function prepareFetch(data: unknown) {
  vi.stubEnv('DEV', true)
  vi.stubEnv('VITE_API_BASE_URL', '')
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(
      jsonResponse({ token: 'csrf-token', headerName: 'X-CUSTOM-CSRF' }),
    )
    .mockResolvedValueOnce(jsonResponse({ data }, 201))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function expectCsrfRequest(fetchMock: ReturnType<typeof vi.fn>) {
  expect(fetchMock).toHaveBeenNthCalledWith(
    1,
    'http://localhost:8080/api/admin/auth/csrf',
    { credentials: 'include' },
  )
}

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response
}

function housingRequest(): HousingComplexCreateRequest {
  return {
    name: '두꺼비 행복주택',
    rentalType: 'HAPPY_HOUSING',
    agencyCode: 'LH',
    address: {
      roadAddress: '서울시 중구 세종대로 1',
      pnu: '1114010100100010000',
      legalDongCode: '11140101',
      provinceCode: '11',
      cityCountyDistrictCode: '140',
      latitude: 37.5665,
      longitude: 126.978,
    },
    totalHouseholdCount: 100,
    completionDate: '2026-01-01',
    heatingType: 'INDIVIDUAL',
    buildingType: 'APARTMENT',
    corridorType: 'STAIR',
    hasElevator: true,
    totalParkingCount: 80,
    overviewImageUrl: null,
    moveOutCountLastYear: 5,
  }
}

function announcementRequest(): AnnouncementCreateRequest {
  return {
    housingComplexId: 42,
    name: '입주자 모집',
    rentalType: 'HAPPY_HOUSING',
    recruitmentType: 'NEW',
    agencyCode: 'LH',
    postedDate: '2026-08-01',
    applicationStartDate: '2026-08-10',
    applicationEndDate: '2026-08-14',
    winnerAnnouncementDate: '2026-09-01',
    originalUrl: 'https://example.com/announcement',
    receptionPlace: {
      name: 'LH 청약센터',
      method: 'ONLINE',
      address: null,
      contact: '1600-1004',
      url: null,
    },
    supplyRow: {
      sourceComplexName: '원문 단지',
      sourceHousingTypeName: '36A',
      supplyPnu: '1114010100100010000',
      expectedMoveInMonth: '2027-03',
      supplyCategory: 'NEW_SUPPLY',
      totalSupplyHouseholdCount: 20,
    },
  }
}
