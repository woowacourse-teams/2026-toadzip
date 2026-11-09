import { getApiBaseUrl } from '../../api/apiBaseUrl'
export type HousingComplexCreateRequest = {
  name: string
  rentalType: string
  agencyCode: string
  address: {
    roadAddress: string
    pnu: string | null
    legalDongCode: string
    provinceCode: string
    cityCountyDistrictCode: string
    latitude: number
    longitude: number
  }
  totalHouseholdCount: number | null
  /** 입주 전 신규 단지는 준공일·건물 정보를 아직 모를 수 있다. */
  completionDate: string | null
  heatingType: string | null
  buildingType: string | null
  corridorType: string | null
  hasElevator: boolean | null
  totalParkingCount: number | null
  overviewImageUrl: string | null
  moveOutCountLastYear: number | null
}

export type HousingComplexCreateResponse = {
  housingComplexId: number
  name: string
  roadAddress: string
  pnu?: string
  rentalType?: string
  agencyCode?: string
}

export type AnnouncementCreateRequest = {
  housingComplexId: number
  name: string
  rentalType: string
  recruitmentType: string
  agencyCode: string
  postedDate: string
  applicationStartDate: string
  applicationEndDate: string
  winnerAnnouncementDate: string
  originalUrl: string
  /** 공고문에 나온 순서대로 한 곳 이상, 최대 20곳. */
  receptionPlaces: ReceptionPlaceCreateRequest[]
  supplyRow: {
    sourceComplexName: string
    sourceHousingTypeName: string
    supplyPnu: string
    expectedMoveInMonth: string | null
    supplyCategory: string
    totalSupplyHouseholdCount: number
  }
}

/** 인터넷 접수처는 연락처가 없을 수 있다. guidance는 그 접수처의 안내 한 줄이다. */
export type ReceptionPlaceCreateRequest = {
  name: string
  method: string
  address: string | null
  contact: string | null
  url: string | null
  guidance: string | null
}

export type AnnouncementCreateResponse = {
  announcementId: number
  supplyRowId: number
  housingComplexId: number
  name: string
}

type CsrfToken = {
  token: string
  headerName: string
}

type ApiEnvelope<T> = {
  data: T
}

export class AdminRegistrationApiError extends Error {
  readonly status: number
  readonly fieldErrors: Readonly<Record<string, string>>

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.fieldErrors = fieldErrors
    this.name = 'AdminRegistrationApiError'
  }
}

const apiBaseUrl = getApiBaseUrl()

export async function createHousingComplex(
  request: HousingComplexCreateRequest,
): Promise<HousingComplexCreateResponse> {
  const response = await postWithCsrf('/api/admin/housing-complexes', request)
  const body = await readJson(response)
  if (!isHousingComplexEnvelope(body)) {
    throw new Error('단지 등록 응답 형식이 올바르지 않습니다.')
  }
  return body.data
}

export async function createAnnouncement(
  request: AnnouncementCreateRequest,
): Promise<AnnouncementCreateResponse> {
  const response = await postWithCsrf('/api/admin/announcements', request)
  const body = await readJson(response)
  if (!isAnnouncementEnvelope(body)) {
    throw new Error('공고 등록 응답 형식이 올바르지 않습니다.')
  }
  return body.data
}

async function postWithCsrf(path: string, body: unknown): Promise<Response> {
  return postJsonWithCsrf(path, JSON.stringify(body))
}

async function postJsonWithCsrf(path: string, jsonBody: string): Promise<Response> {
  const csrfToken = await requestCsrfToken()
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      [csrfToken.headerName]: csrfToken.token,
    },
    body: jsonBody,
  })
  if (!response.ok) {
    throw await registrationError(response)
  }
  return response
}

async function requestCsrfToken(): Promise<CsrfToken> {
  const response = await fetch(`${apiBaseUrl}/api/admin/auth/csrf`, {
    credentials: 'include',
  })
  if (!response.ok) {
    throw await registrationError(response)
  }
  const body = await readJson(response)
  if (!isCsrfToken(body)) {
    throw new Error('CSRF 토큰 응답 형식이 올바르지 않습니다.')
  }
  return body
}

async function registrationError(response: Response): Promise<AdminRegistrationApiError> {
  const body = await readJson(response).catch(() => null)
  if (!isRecord(body)) {
    return new AdminRegistrationApiError(
      response.status,
      '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  }
  const message = typeof body.message === 'string'
    ? body.message
    : '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  return new AdminRegistrationApiError(response.status, message, fieldErrorsOf(body.errors))
}

async function readJson(response: Response): Promise<unknown> {
  return response.json() as Promise<unknown>
}

function fieldErrorsOf(value: unknown): Record<string, string> {
  if (!Array.isArray(value)) {
    return {}
  }
  const fieldErrors: Record<string, string> = {}
  for (const error of value) {
    if (isRecord(error) && typeof error.field === 'string' && typeof error.reason === 'string') {
      fieldErrors[error.field] = error.reason
    }
  }
  return fieldErrors
}

function isCsrfToken(value: unknown): value is CsrfToken {
  return isRecord(value)
    && typeof value.token === 'string'
    && typeof value.headerName === 'string'
}

function isHousingComplexEnvelope(value: unknown): value is ApiEnvelope<HousingComplexCreateResponse> {
  if (!isRecord(value) || !isRecord(value.data)) {
    return false
  }
  return typeof value.data.housingComplexId === 'number'
    && typeof value.data.name === 'string'
    && typeof value.data.roadAddress === 'string'
}

function isAnnouncementEnvelope(value: unknown): value is ApiEnvelope<AnnouncementCreateResponse> {
  if (!isRecord(value) || !isRecord(value.data)) {
    return false
  }
  return typeof value.data.announcementId === 'number'
    && typeof value.data.supplyRowId === 'number'
    && typeof value.data.housingComplexId === 'number'
    && typeof value.data.name === 'string'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
