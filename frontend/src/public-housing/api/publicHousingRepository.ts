import type {
  AnnouncementDetail,
  AnnouncementPage,
  ComplexDetail,
  ComplexPage,
  MapBounds,
  MapComplex,
} from '../model/publicHousing.ts'
import {
  decodeAnnouncementDetailEnvelope,
  decodeAnnouncementPageEnvelope,
  decodeComplexDetailEnvelope,
  decodeComplexPageEnvelope,
  decodeMapComplexEnvelope,
  PublicHousingContractError,
} from './publicHousingContract.ts'
import {
  toAnnouncementDetail,
  toAnnouncementPage,
  toComplexDetail,
  toComplexPage,
  toMapComplexes,
} from './publicHousingMapper.ts'

const COMPLEXES_PATH = '/api/v1/complexes'
const ANNOUNCEMENTS_PATH = '/api/v1/announcements'
const MAX_JAVA_LONG = 9_223_372_036_854_775_807n

export interface PublicHousingRepositoryOptions {
  readonly apiBaseUrl?: string
  readonly fetcher?: typeof globalThis.fetch
}

interface ErrorBody {
  readonly code: string | null
  readonly message: string | null
  readonly traceId: string | null
}

export type RentalTypeFilter =
  | 'HAPPY_HOUSING'
  | 'NATIONAL_RENTAL'
  | 'PERMANENT_RENTAL'
  | 'PUBLIC_RENTAL_50Y'
  | 'INTEGRATED_PUBLIC_RENTAL'
  | 'REDEVELOPMENT_RENTAL'
  | 'ETC'

export type ApplicationStatusFilter =
  | 'BEFORE_APPLICATION'
  | 'APPLYING'
  | 'CLOSED'

export type AgencyCodeFilter = 'LH' | 'SH' | 'GH' | 'ETC'

export type RecruitmentTypeFilter = 'NEW' | 'WAITLIST' | 'ETC'

export interface SharedSearchFilters {
  readonly agencyCodes?: readonly AgencyCodeFilter[]
  readonly applicationStatuses?: readonly ApplicationStatusFilter[]
  readonly recruitmentTypes?: readonly RecruitmentTypeFilter[]
  readonly regionCode?: string | null
  readonly rentalTypes?: readonly RentalTypeFilter[]
}

export interface ComplexSearchFilters extends SharedSearchFilters {
  readonly builtYearFrom?: number | null
  readonly builtYearTo?: number | null
  readonly maxDeposit?: number | null
  readonly maxExclusiveArea?: number | null
  readonly maxMonthlyRent?: number | null
  readonly minDeposit?: number | null
  readonly minExclusiveArea?: number | null
  readonly minMonthlyRent?: number | null
}

export type AnnouncementSearchFilters = SharedSearchFilters
export interface PublicHousingRepository {
  findComplexPage(
    bounds: MapBounds,
    cursor: string | null,
    size: number,
    signal: AbortSignal,
    filters?: ComplexSearchFilters,
  ): Promise<ComplexPage>
  findMapComplexes(
    bounds: MapBounds,
    signal: AbortSignal,
    filters?: ComplexSearchFilters,
  ): Promise<readonly MapComplex[]>
  findComplexDetail(
    complexId: string,
    signal: AbortSignal,
  ): Promise<ComplexDetail>
  findAnnouncementPage(
    cursor: string | null,
    size: number,
    signal: AbortSignal,
    filters?: AnnouncementSearchFilters,
  ): Promise<AnnouncementPage>
  findAnnouncementDetail(
    announcementId: string,
    signal: AbortSignal,
  ): Promise<AnnouncementDetail>
}

export class PublicHousingHttpError extends Error {
  readonly status: number
  readonly code: string | null
  readonly traceId: string | null

  constructor(status: number, body: ErrorBody) {
    super(body.message ?? '공공주택 정보를 불러오지 못했습니다.')
    this.name = 'PublicHousingHttpError'
    this.status = status
    this.code = body.code
    this.traceId = body.traceId
  }
}

export function createHttpPublicHousingRepository(
  options: PublicHousingRepositoryOptions = {},
): PublicHousingRepository {
  const apiBaseUrl = options.apiBaseUrl ?? resolvePublicHousingApiBaseUrl()
  const fetcher = options.fetcher ?? globalThis.fetch

  return {
    async findComplexPage(bounds, cursor, size, signal, filters = {}) {
      validatePageSize(size)
      const search = createComplexSearchParams(bounds, filters)
      if (cursor !== null) {
        search.set('cursor', cursor)
      }
      search.set('size', String(size))
      const payload = await requestPublicHousingJson(
        fetcher,
        `${apiBaseUrl}${COMPLEXES_PATH}?${search.toString()}`,
        signal,
      )
      return toComplexPage(decodeComplexPageEnvelope(payload))
    },

    async findMapComplexes(bounds, signal, filters = {}) {
      const search = createComplexSearchParams(bounds, filters)
      const payload = await requestPublicHousingJson(
        fetcher,
        `${apiBaseUrl}${COMPLEXES_PATH}/map?${search.toString()}`,
        signal,
      )
      return toMapComplexes(decodeMapComplexEnvelope(payload).items)
    },

    async findComplexDetail(complexId, signal) {
      validateCanonicalId(complexId, '단지')
      const payload = await requestPublicHousingJson(
        fetcher,
        `${apiBaseUrl}${COMPLEXES_PATH}/${complexId}`,
        signal,
      )
      return toComplexDetail(decodeComplexDetailEnvelope(payload))
    },

    async findAnnouncementPage(cursor, size, signal, filters = {}) {
      validatePageSize(size)
      const search = new URLSearchParams({ size: String(size) })
      appendSharedFilters(search, filters)
      if (cursor !== null) {
        search.set('cursor', cursor)
      }
      const payload = await requestPublicHousingJson(
        fetcher,
        `${apiBaseUrl}${ANNOUNCEMENTS_PATH}?${search.toString()}`,
        signal,
      )
      return toAnnouncementPage(decodeAnnouncementPageEnvelope(payload))
    },

    async findAnnouncementDetail(announcementId, signal) {
      validateCanonicalId(announcementId, '공고')
      const payload = await requestPublicHousingJson(
        fetcher,
        `${apiBaseUrl}${ANNOUNCEMENTS_PATH}/${announcementId}`,
        signal,
      )
      return toAnnouncementDetail(decodeAnnouncementDetailEnvelope(payload))
    },
  }
}

export const publicHousingRepository = createHttpPublicHousingRepository()

export async function requestPublicHousingJson(
  fetcher: typeof globalThis.fetch,
  url: string,
  signal: AbortSignal,
): Promise<unknown> {
  const response = await fetcher(url, {
    headers: { Accept: 'application/json' },
    signal,
  })

  if (!response.ok) {
    throw new PublicHousingHttpError(response.status, await decodeErrorBody(response))
  }

  try {
    return (await response.json()) as unknown
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    throw new PublicHousingContractError(
      error instanceof Error ? '$ (invalid JSON)' : '$',
    )
  }
}

async function decodeErrorBody(response: Response): Promise<ErrorBody> {
  let value: unknown
  try {
    value = (await response.json()) as unknown
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    value = null
  }
  if (!isRecord(value)) {
    return { code: null, message: null, traceId: null }
  }

  return {
    code: nullableString(value.code),
    message: nullableString(value.message),
    traceId: nullableString(value.traceId),
  }
}

function isAbortError(error: unknown) {
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && error.name === 'AbortError'
}

export function createComplexSearchParams(
  bounds: MapBounds,
  filters: ComplexSearchFilters = {},
): URLSearchParams {
  validateBounds(bounds)
  const search = new URLSearchParams({
    southWestLat: String(bounds.southWestLat),
    southWestLng: String(bounds.southWestLng),
    northEastLat: String(bounds.northEastLat),
    northEastLng: String(bounds.northEastLng),
  })
  appendComplexFilters(search, filters)
  return search
}

function appendComplexFilters(
  search: URLSearchParams,
  filters: ComplexSearchFilters,
) {
  appendSharedFilters(search, filters)
  appendOptionalNumber(search, 'minDeposit', filters.minDeposit)
  appendOptionalNumber(search, 'maxDeposit', filters.maxDeposit)
  appendOptionalNumber(search, 'minMonthlyRent', filters.minMonthlyRent)
  appendOptionalNumber(search, 'maxMonthlyRent', filters.maxMonthlyRent)
  appendOptionalNumber(search, 'minExclusiveArea', filters.minExclusiveArea)
  appendOptionalNumber(search, 'maxExclusiveArea', filters.maxExclusiveArea)
  appendOptionalNumber(search, 'builtYearFrom', filters.builtYearFrom)
  appendOptionalNumber(search, 'builtYearTo', filters.builtYearTo)
}

function appendSharedFilters(
  search: URLSearchParams,
  filters: SharedSearchFilters,
) {
  if (filters.regionCode) {
    search.set('regionCode', filters.regionCode)
  }
  appendRepeated(search, 'rentalTypes', filters.rentalTypes)
  appendRepeated(
    search,
    'applicationStatuses',
    filters.applicationStatuses,
  )
  appendRepeated(search, 'agencyCodes', filters.agencyCodes)
  appendRepeated(search, 'recruitmentTypes', filters.recruitmentTypes)
}

function appendRepeated(
  search: URLSearchParams,
  key: string,
  values: readonly string[] | undefined,
) {
  values?.forEach((value) => search.append(key, value))
}

function appendOptionalNumber(
  search: URLSearchParams,
  key: string,
  value: number | null | undefined,
) {
  if (value !== null && value !== undefined) {
    search.set(key, String(value))
  }
}

function validateBounds(bounds: MapBounds) {
  const coordinates = [
    bounds.southWestLat,
    bounds.southWestLng,
    bounds.northEastLat,
    bounds.northEastLng,
  ]
  if (coordinates.some((coordinate) => !Number.isFinite(coordinate))) {
    throw new RangeError('지도 범위 좌표는 유한한 숫자여야 합니다.')
  }
}

function validatePageSize(size: number) {
  if (!Number.isInteger(size) || size < 1 || size > 50) {
    throw new RangeError('목록 크기는 1부터 50 사이의 정수여야 합니다.')
  }
}

function validateCanonicalId(id: string, entity: string) {
  if (
    !/^[1-9]\d*$/.test(id) ||
    id.length > 19 ||
    BigInt(id) > MAX_JAVA_LONG
  ) {
    throw new RangeError(`${entity} ID는 양의 Java Long 정수 문자열이어야 합니다.`)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function nullableString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

export function resolvePublicHousingApiBaseUrl(): string {
  const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL
  if (configuredApiBaseUrl) {
    return configuredApiBaseUrl
  }
  if (import.meta.env.DEV) {
    return 'http://localhost:8080'
  }
  return ''
}
