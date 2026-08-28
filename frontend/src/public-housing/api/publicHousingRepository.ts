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

interface RepositoryOptions {
  readonly apiBaseUrl?: string
  readonly fetcher?: typeof globalThis.fetch
}

interface ErrorBody {
  readonly code: string | null
  readonly message: string | null
  readonly traceId: string | null
}

export interface PublicHousingRepository {
  findComplexPage(
    bounds: MapBounds,
    cursor: string | null,
    size: number,
    signal: AbortSignal,
  ): Promise<ComplexPage>
  findMapComplexes(
    bounds: MapBounds,
    signal: AbortSignal,
  ): Promise<readonly MapComplex[]>
  findComplexDetail(
    complexId: string,
    signal: AbortSignal,
  ): Promise<ComplexDetail>
  findAnnouncementPage(
    cursor: string | null,
    size: number,
    signal: AbortSignal,
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
  options: RepositoryOptions = {},
): PublicHousingRepository {
  const apiBaseUrl = options.apiBaseUrl ?? resolveApiBaseUrl()
  const fetcher = options.fetcher ?? globalThis.fetch

  return {
    async findComplexPage(bounds, cursor, size, signal) {
      validatePageSize(size)
      const search = boundsSearchParams(bounds)
      if (cursor !== null) {
        search.set('cursor', cursor)
      }
      search.set('size', String(size))

      const payload = await requestJson(
        fetcher,
        `${apiBaseUrl}${COMPLEXES_PATH}?${search.toString()}`,
        signal,
      )
      return toComplexPage(decodeComplexPageEnvelope(payload))
    },

    async findMapComplexes(bounds, signal) {
      const search = boundsSearchParams(bounds)
      const payload = await requestJson(
        fetcher,
        `${apiBaseUrl}${COMPLEXES_PATH}/map?${search.toString()}`,
        signal,
      )
      return toMapComplexes(decodeMapComplexEnvelope(payload).items)
    },

    async findComplexDetail(complexId, signal) {
      validateCanonicalId(complexId, '단지')
      const payload = await requestJson(
        fetcher,
        `${apiBaseUrl}${COMPLEXES_PATH}/${complexId}`,
        signal,
      )
      return toComplexDetail(decodeComplexDetailEnvelope(payload))
    },

    async findAnnouncementPage(cursor, size, signal) {
      validatePageSize(size)
      const search = new URLSearchParams({ size: String(size) })
      if (cursor !== null) {
        search.set('cursor', cursor)
      }
      const payload = await requestJson(
        fetcher,
        `${apiBaseUrl}${ANNOUNCEMENTS_PATH}?${search.toString()}`,
        signal,
      )
      return toAnnouncementPage(decodeAnnouncementPageEnvelope(payload))
    },

    async findAnnouncementDetail(announcementId, signal) {
      validateCanonicalId(announcementId, '공고')
      const payload = await requestJson(
        fetcher,
        `${apiBaseUrl}${ANNOUNCEMENTS_PATH}/${announcementId}`,
        signal,
      )
      return toAnnouncementDetail(decodeAnnouncementDetailEnvelope(payload))
    },
  }
}

export const publicHousingRepository = createHttpPublicHousingRepository()

async function requestJson(
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

function boundsSearchParams(bounds: MapBounds): URLSearchParams {
  validateBounds(bounds)
  return new URLSearchParams({
    southWestLat: String(bounds.southWestLat),
    southWestLng: String(bounds.southWestLng),
    northEastLat: String(bounds.northEastLat),
    northEastLng: String(bounds.northEastLng),
  })
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
