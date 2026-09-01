import type { PublicHousingRegion } from '../model/publicHousingRegion.ts'

const REGIONS_PATH = '/api/v1/regions'

interface RepositoryOptions {
  readonly apiBaseUrl?: string
  readonly fetcher?: typeof globalThis.fetch
}

interface ErrorBody {
  readonly code: string | null
  readonly message: string | null
  readonly traceId: string | null
}

export interface PublicHousingRegionRepository {
  search(
    keyword: string,
    signal: AbortSignal,
  ): Promise<readonly PublicHousingRegion[]>
}

export class PublicHousingRegionHttpError extends Error {
  readonly status: number
  readonly code: string | null
  readonly traceId: string | null

  constructor(status: number, body: ErrorBody) {
    super(body.message ?? '지역 정보를 불러오지 못했습니다.')
    this.name = 'PublicHousingRegionHttpError'
    this.status = status
    this.code = body.code
    this.traceId = body.traceId
  }
}

export class PublicHousingRegionContractError extends Error {
  readonly path: string

  constructor(path: string) {
    super(`지역 API 응답 형식이 올바르지 않습니다: ${path}`)
    this.name = 'PublicHousingRegionContractError'
    this.path = path
  }
}

export function createHttpPublicHousingRegionRepository(
  options: RepositoryOptions = {},
): PublicHousingRegionRepository {
  const apiBaseUrl = options.apiBaseUrl ?? resolveApiBaseUrl()
  const fetcher = options.fetcher ?? globalThis.fetch

  return {
    async search(keyword, signal) {
      const search = new URLSearchParams({ keyword })
      const response = await fetcher(
        `${apiBaseUrl}${REGIONS_PATH}?${search.toString()}`,
        {
          headers: { Accept: 'application/json' },
          signal,
        },
      )
      if (!response.ok) {
        throw new PublicHousingRegionHttpError(
          response.status,
          await decodeErrorBody(response),
        )
      }
      try {
        return decodePublicHousingRegionEnvelope(await response.json())
      } catch (error) {
        if (
          error instanceof PublicHousingRegionContractError
          || isAbortError(error)
        ) {
          throw error
        }
        throw new PublicHousingRegionContractError('$ (invalid JSON)')
      }
    },
  }
}

export const publicHousingRegionRepository =
  createHttpPublicHousingRegionRepository()

async function decodeErrorBody(response: Response): Promise<ErrorBody> {
  let value: unknown
  try {
    value = (await response.json()) as unknown
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    return { code: null, message: null, traceId: null }
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

export function decodePublicHousingRegionEnvelope(
  value: unknown,
): readonly PublicHousingRegion[] {
  const envelope = recordAt(value, '$')
  const data = recordAt(fieldAt(envelope, 'data', '$'), '$.data')
  const items = arrayAt(fieldAt(data, 'items', '$.data'), '$.data.items')

  return items.map((item, index) => decodeRegion(
    item,
    `$.data.items[${index}]`,
  ))
}

function decodeRegion(value: unknown, path: string): PublicHousingRegion {
  const region = recordAt(value, path)
  return {
    regionCode: regionCodeAt(
      fieldAt(region, 'regionCode', path),
      `${path}.regionCode`,
    ),
    provinceName: nonEmptyStringAt(
      fieldAt(region, 'provinceName', path),
      `${path}.provinceName`,
    ),
    districtName: nullableNonEmptyStringAt(
      fieldAt(region, 'districtName', path),
      `${path}.districtName`,
    ),
    displayName: nonEmptyStringAt(
      fieldAt(region, 'displayName', path),
      `${path}.displayName`,
    ),
  }
}

function recordAt(value: unknown, path: string): Record<string, unknown> {
  if (!isRecord(value)) {
    throw new PublicHousingRegionContractError(path)
  }
  return value
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function fieldAt(
  value: Record<string, unknown>,
  field: string,
  path: string,
): unknown {
  if (!Object.hasOwn(value, field)) {
    throw new PublicHousingRegionContractError(`${path}.${field}`)
  }
  return value[field]
}

function arrayAt(value: unknown, path: string): readonly unknown[] {
  if (!Array.isArray(value)) {
    throw new PublicHousingRegionContractError(path)
  }
  return value
}

function nonEmptyStringAt(value: unknown, path: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new PublicHousingRegionContractError(path)
  }
  return value
}

function nullableString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function nullableNonEmptyStringAt(
  value: unknown,
  path: string,
): string | null {
  if (value === null) {
    return null
  }
  return nonEmptyStringAt(value, path)
}

function regionCodeAt(value: unknown, path: string): string {
  const regionCode = nonEmptyStringAt(value, path)
  if (!/^(?:\d{2}|\d{5})$/.test(regionCode)) {
    throw new PublicHousingRegionContractError(path)
  }
  return regionCode
}

function isAbortError(error: unknown) {
  return typeof error === 'object'
    && error !== null
    && 'name' in error
    && error.name === 'AbortError'
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
