import type {
  HousingMapResult,
  HousingMapStage,
} from '../model/housingMap.ts'
import type { MapBounds } from '../model/publicHousing.ts'
import { decodeHousingMapEnvelope } from './housingMapContract.ts'
import {
  createComplexSearchParams,
  type ComplexSearchFilters,
  type PublicHousingRepositoryOptions,
  requestPublicHousingJson,
  resolvePublicHousingApiBaseUrl,
} from './publicHousingRepository.ts'

const HOUSING_MAP_PATH = '/api/v2/complexes/map'

export interface HousingMapQuery {
  readonly bounds: MapBounds
  readonly zoom: number
  readonly previousResolvedStage?: HousingMapStage | null
  readonly filters?: ComplexSearchFilters
}

export interface HousingMapRepository {
  findMap(
    query: HousingMapQuery,
    signal: AbortSignal,
  ): Promise<HousingMapResult>
}

export function createHttpHousingMapRepository(
  options: PublicHousingRepositoryOptions = {},
): HousingMapRepository {
  const apiBaseUrl = options.apiBaseUrl ?? resolvePublicHousingApiBaseUrl()
  const fetcher = options.fetcher ?? globalThis.fetch

  return {
    async findMap(query, signal) {
      const search = mapSearchParams(query)
      const payload = await requestPublicHousingJson(
        fetcher,
        `${apiBaseUrl}${HOUSING_MAP_PATH}?${search.toString()}`,
        signal,
      )
      return decodeHousingMapEnvelope(payload)
    },
  }
}

export const housingMapRepository = createHttpHousingMapRepository()

function mapSearchParams(query: HousingMapQuery): URLSearchParams {
  validateZoom(query.zoom)
  validatePreviousStage(query.previousResolvedStage)
  const search = createComplexSearchParams(query.bounds, query.filters)
  search.set('zoom', String(query.zoom))
  if (query.previousResolvedStage !== null
    && query.previousResolvedStage !== undefined) {
    search.set('previousResolvedStage', String(query.previousResolvedStage))
  }
  return search
}

function validateZoom(zoom: number) {
  if (!Number.isFinite(zoom) || zoom < 0) {
    throw new RangeError('지도 확대 수준은 0 이상의 유한한 숫자여야 합니다.')
  }
}

function validatePreviousStage(stage: HousingMapStage | null | undefined) {
  if (stage === null || stage === undefined) {
    return
  }
  if (stage < 1 || stage > 4 || !Number.isInteger(stage)) {
    throw new RangeError('직전 지도 단계는 1부터 4 사이의 정수여야 합니다.')
  }
}
