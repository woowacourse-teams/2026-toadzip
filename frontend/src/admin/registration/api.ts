export type HousingComplexCreateRequest = {
  name: string
  rentalType: string
  agencyCode: string
  address: {
    roadAddress: string
    pnu: string
    legalDongCode: string
    provinceCode: string
    cityCountyDistrictCode: string
    latitude: number
    longitude: number
  }
  totalHouseholdCount: number
  completionDate: string
  heatingType: string
  buildingType: string
  corridorType: string
  hasElevator: boolean
  totalParkingCount: number
  overviewImageUrl: string | null
  moveOutCountLastYear: number
}

export type HousingComplexCreateResponse = {
  housingComplexId: number
  name: string
  roadAddress: string
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
  receptionPlace: {
    name: string
    method: string
    address: string | null
    contact: string
    url: string | null
  }
  supplyRow: {
    sourceComplexName: string
    sourceHousingTypeName: string
    supplyPnu: string
    expectedMoveInMonth: string | null
    supplyCategory: string
    totalSupplyHouseholdCount: number
  }
}

export type AnnouncementCreateResponse = {
  announcementId: number
  supplyRowId: number
  housingComplexId: number
  name: string
}

export type AnnouncementImportCandidate = {
  housingComplexId: number
  name: string
  roadAddress: string
  rentalType: string
  agencyCode: string
}

export type AnnouncementImportSupplyRowMatch = {
  supplyRowIndex: number
  sourceComplexName: string
  pnu: string
  status: 'AUTO_SELECTED' | 'SELECTION_REQUIRED' | 'NOT_FOUND'
  suggestedHousingComplexId: number | null
  candidates: AnnouncementImportCandidate[]
}

export type AnnouncementImportValidationResponse = {
  schemaVersion: string
  jsonHash: string
  registerable: boolean
  duplicated: boolean
  errors: Array<{ path: string; reason: string }>
  warnings: Array<{ path: string; reason: string }>
  unresolvedFields: Array<{ path: string; reason: string }>
  supplyRows: AnnouncementImportSupplyRowMatch[]
}

export type AnnouncementImportCreateResponse = {
  importId: number
  announcementId: number
  supplyRowCount: number
  scheduleCount: number
  attachmentCount: number
  supplyTargetCount: number
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

const apiBaseUrl = resolveApiBaseUrl()

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

export async function validateAnnouncementImport(
  importJson: string,
): Promise<AnnouncementImportValidationResponse> {
  const response = await postJsonWithCsrf('/api/admin/announcement-imports/validate', importJson)
  const body = await readJson(response)
  if (!isAnnouncementImportValidationEnvelope(body)) {
    throw new Error('공고 JSON 검증 응답 형식이 올바르지 않습니다.')
  }
  return body.data
}

export async function createAnnouncementImport(
  importJson: string,
  complexSelections: Array<{ supplyRowIndex: number; housingComplexId: number }>,
): Promise<AnnouncementImportCreateResponse> {
  const registrationJson = `{"importData":${importJson},"complexSelections":${JSON.stringify(complexSelections)}}`
  const response = await postJsonWithCsrf('/api/admin/announcement-imports', registrationJson)
  const body = await readJson(response)
  if (!isAnnouncementImportCreateEnvelope(body)) {
    throw new Error('공고 JSON 등록 응답 형식이 올바르지 않습니다.')
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

function isAnnouncementImportValidationEnvelope(
  value: unknown,
): value is ApiEnvelope<AnnouncementImportValidationResponse> {
  if (!isRecord(value) || !isRecord(value.data)) {
    return false
  }
  const data = value.data
  return typeof data.schemaVersion === 'string'
    && typeof data.jsonHash === 'string'
    && typeof data.registerable === 'boolean'
    && typeof data.duplicated === 'boolean'
    && isIssueArray(data.errors)
    && isIssueArray(data.warnings)
    && isIssueArray(data.unresolvedFields)
    && Array.isArray(data.supplyRows)
    && data.supplyRows.every(isSupplyRowMatch)
}

function isAnnouncementImportCreateEnvelope(
  value: unknown,
): value is ApiEnvelope<AnnouncementImportCreateResponse> {
  if (!isRecord(value) || !isRecord(value.data)) {
    return false
  }
  const data = value.data
  return typeof data.importId === 'number'
    && typeof data.announcementId === 'number'
    && typeof data.supplyRowCount === 'number'
    && typeof data.scheduleCount === 'number'
    && typeof data.attachmentCount === 'number'
    && typeof data.supplyTargetCount === 'number'
}

function isSupplyRowMatch(value: unknown): value is AnnouncementImportSupplyRowMatch {
  if (!isRecord(value)) {
    return false
  }
  return typeof value.supplyRowIndex === 'number'
    && typeof value.sourceComplexName === 'string'
    && typeof value.pnu === 'string'
    && isMatchStatus(value.status)
    && (value.suggestedHousingComplexId === null || typeof value.suggestedHousingComplexId === 'number')
    && Array.isArray(value.candidates)
    && value.candidates.every(isImportCandidate)
}

function isMatchStatus(value: unknown): value is AnnouncementImportSupplyRowMatch['status'] {
  return value === 'AUTO_SELECTED' || value === 'SELECTION_REQUIRED' || value === 'NOT_FOUND'
}

function isImportCandidate(value: unknown): value is AnnouncementImportCandidate {
  return isRecord(value)
    && typeof value.housingComplexId === 'number'
    && typeof value.name === 'string'
    && typeof value.roadAddress === 'string'
    && typeof value.rentalType === 'string'
    && typeof value.agencyCode === 'string'
}

function isIssueArray(value: unknown): value is Array<{ path: string; reason: string }> {
  return Array.isArray(value) && value.every((issue) => isRecord(issue)
    && typeof issue.path === 'string'
    && typeof issue.reason === 'string')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function resolveApiBaseUrl(): string {
  const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL
  if (configuredApiBaseUrl) {
    return configuredApiBaseUrl
  }
  if (import.meta.env.DEV) {
    return 'http://localhost:8080'
  }
  return ''
}
