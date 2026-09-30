import type { MapBounds } from '../model/publicHousing.ts'
import type {
  Position,
  RegionBoundary,
  RegionBoundaryMetadata,
} from './regionBoundary.ts'

const CACHE_LIMIT = 8
// Six-decimal bbox output may round a vertex by roughly 0.1 meters.
const BOUNDS_TOLERANCE = 0.000001

interface RepositoryOptions {
  readonly version: string
  readonly findMetadata: (
    regionCode: string,
  ) => RegionBoundaryMetadata | null | undefined
  readonly fetcher?: typeof globalThis.fetch
}

export interface RegionBoundaryRepository {
  find(regionCode: string, signal: AbortSignal): Promise<RegionBoundary>
}

export class RegionBoundaryContractError extends Error {
  readonly path: string

  constructor(path: string) {
    super(`지역 경계 데이터 형식이 올바르지 않습니다: ${path}`)
    this.name = 'RegionBoundaryContractError'
    this.path = path
  }
}

export function createRegionBoundaryRepository(
  options: RepositoryOptions,
): RegionBoundaryRepository {
  const fetcher = options.fetcher ?? globalThis.fetch
  const cache = new Map<string, RegionBoundary>()

  return {
    async find(regionCode, signal) {
      signal.throwIfAborted()
      const cached = cache.get(regionCode)
      if (cached) {
        cache.delete(regionCode)
        cache.set(regionCode, cached)
        return cached
      }

      const metadata = options.findMetadata(regionCode)
      if (!metadata) {
        throw new RangeError('제공되는 지역 경계가 없습니다.')
      }
      if (metadata.regionCode !== regionCode) {
        throw new RegionBoundaryContractError('metadata.regionCode')
      }
      const response = await fetcher(metadata.path, {
        headers: { Accept: 'application/geo+json' },
        signal,
      })
      signal.throwIfAborted()
      if (!response.ok) {
        throw new Error(`지역 경계를 불러오지 못했습니다. (HTTP ${response.status})`)
      }

      let payload: unknown
      try {
        payload = await response.json()
      } catch (error) {
        signal.throwIfAborted()
        if (error instanceof Error && error.name === 'AbortError') {
          throw error
        }
        throw new RegionBoundaryContractError('$ (invalid JSON)')
      }
      signal.throwIfAborted()
      const boundary = decodeBoundary(payload, metadata, options.version)
      cache.delete(regionCode)
      cache.set(regionCode, boundary)
      if (cache.size > CACHE_LIMIT) {
        const oldestCode = cache.keys().next().value
        if (oldestCode !== undefined) {
          cache.delete(oldestCode)
        }
      }
      return boundary
    },
  }
}

function decodeBoundary(
  value: unknown,
  metadata: RegionBoundaryMetadata,
  version: string,
): RegionBoundary {
  const feature = recordAt(value, '$')
  if (feature.type !== 'Feature') {
    throw new RegionBoundaryContractError('$.type')
  }
  const properties = recordAt(feature.properties, '$.properties')
  if (properties.regionCode !== metadata.regionCode) {
    throw new RegionBoundaryContractError('$.properties.regionCode')
  }
  if (properties.version !== version) {
    throw new RegionBoundaryContractError('$.properties.version')
  }
  const geometry = recordAt(feature.geometry, '$.geometry')
  const coordinates = arrayAt(geometry.coordinates, '$.geometry.coordinates')
  let polygons: RegionBoundary['polygons']
  if (geometry.type === 'Polygon') {
    polygons = [decodePolygon(coordinates, '$.geometry.coordinates')]
  } else if (geometry.type === 'MultiPolygon' && coordinates.length > 0) {
    polygons = coordinates.map((polygon, index) => decodePolygon(
      polygon,
      `$.geometry.coordinates[${index}]`,
    ))
  } else {
    throw new RegionBoundaryContractError('$.geometry.type')
  }

  const { southWestLng, southWestLat, northEastLng, northEastLat } = metadata.bounds
  const bounds = decodeBounds(
    [southWestLng, southWestLat, northEastLng, northEastLat],
    'metadata.bounds',
  )
  verifyContainment(polygons, bounds, 'metadata.bounds')
  if (Object.hasOwn(feature, 'bbox')) {
    verifyContainment(polygons, decodeBounds(feature.bbox, '$.bbox'), '$.bbox')
  }
  return { regionCode: metadata.regionCode, version, polygons }
}

function decodePolygon(value: unknown, path: string): readonly (readonly Position[])[] {
  const rings = arrayAt(value, path)
  if (rings.length === 0) {
    throw new RegionBoundaryContractError(path)
  }
  return rings.map((ring, index) => {
    const ringPath = `${path}[${index}]`
    const positions = arrayAt(ring, ringPath)
    if (positions.length < 4) {
      throw new RegionBoundaryContractError(ringPath)
    }
    const decoded = positions.map((position, positionIndex) => decodePosition(
      position,
      `${ringPath}[${positionIndex}]`,
    ))
    const first = decoded[0]
    const last = decoded[decoded.length - 1]
    if (first[0] !== last[0] || first[1] !== last[1]) {
      throw new RegionBoundaryContractError(ringPath)
    }
    return decoded
  })
}

function decodePosition(value: unknown, path: string): Position {
  const position = arrayAt(value, path)
  const [longitude, latitude] = position
  if (
    position.length !== 2
    || typeof longitude !== 'number'
    || !Number.isFinite(longitude)
    || longitude < -180 || longitude > 180
    || typeof latitude !== 'number'
    || !Number.isFinite(latitude)
    || latitude < -90 || latitude > 90
  ) {
    throw new RegionBoundaryContractError(path)
  }
  return [longitude, latitude]
}

function decodeBounds(value: unknown, path: string): MapBounds {
  const bbox = arrayAt(value, path)
  if (bbox.length !== 4) {
    throw new RegionBoundaryContractError(path)
  }
  const [southWestLng, southWestLat] = decodePosition(bbox.slice(0, 2), path)
  const [northEastLng, northEastLat] = decodePosition(bbox.slice(2, 4), path)
  if (southWestLng > northEastLng || southWestLat > northEastLat) {
    throw new RegionBoundaryContractError(path)
  }
  return { southWestLng, southWestLat, northEastLng, northEastLat }
}

function verifyContainment(
  polygons: RegionBoundary['polygons'],
  bounds: MapBounds,
  path: string,
) {
  for (const polygon of polygons) {
    for (const ring of polygon) {
      for (const [longitude, latitude] of ring) {
        if (
          longitude < bounds.southWestLng - BOUNDS_TOLERANCE
          || longitude > bounds.northEastLng + BOUNDS_TOLERANCE
          || latitude < bounds.southWestLat - BOUNDS_TOLERANCE
          || latitude > bounds.northEastLat + BOUNDS_TOLERANCE
        ) {
          throw new RegionBoundaryContractError(path)
        }
      }
    }
  }
}

function recordAt(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new RegionBoundaryContractError(path)
  }
  return value as Record<string, unknown>
}

function arrayAt(value: unknown, path: string): readonly unknown[] {
  if (!Array.isArray(value)) {
    throw new RegionBoundaryContractError(path)
  }
  return value
}
