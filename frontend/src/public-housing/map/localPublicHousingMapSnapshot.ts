import { decodeMapComplexEnvelope } from '../api/publicHousingContract.ts'
import { toMapComplexes } from '../api/publicHousingMapper.ts'
import type {
  LocalMapComplexSnapshot,
  LocalMapRegionSnapshot,
  LocalMapSnapshot,
} from './localMapMarkerResolver.ts'

export class LocalPublicHousingMapSnapshotError extends Error {
  readonly path: string

  constructor(path: string) {
    super(`로컬 지도 mock 형식이 올바르지 않습니다: ${path}`)
    this.name = 'LocalPublicHousingMapSnapshotError'
    this.path = path
  }
}

export function decodeLocalPublicHousingMapSnapshot(
  value: unknown,
): LocalMapSnapshot {
  const snapshot = recordAt(value, '$')
  const rawMapComplexes = decodeMapComplexEnvelope({
    data: { items: arrayField(snapshot, 'mapComplexItems', '$') },
  }).items
  const mapComplexes = toMapComplexes(rawMapComplexes)
  if (rawMapComplexes.length !== mapComplexes.length) {
    throw new LocalPublicHousingMapSnapshotError('$.mapComplexItems')
  }
  const regions = arrayField(snapshot, 'mapRegions', '$').map(
    (region, index) => decodeRegion(region, `$.mapRegions[${index}]`),
  )
  validateUniqueRegionCodes(regions)
  const complexes = joinComplexesToRegions(regions, mapComplexes)
  validateEveryComplexAssigned(complexes, mapComplexes)
  return {
    regions: regions.map(({ anchor, name, regionCode }) => ({
      anchor,
      name,
      regionCode,
    })),
    complexes,
  }
}

interface DecodedRegion extends LocalMapRegionSnapshot {
  readonly complexIds: readonly string[]
}

function decodeRegion(value: unknown, path: string): DecodedRegion {
  const region = recordAt(value, path)
  const anchorPath = `${path}.anchor`
  const anchor = recordAt(field(region, 'anchor', path), anchorPath)
  const complexIds = arrayField(region, 'complexIds', path).map(
    (complexId, index) => positiveIdAt(
      complexId,
      `${path}.complexIds[${index}]`,
    ),
  )
  if (complexIds.length === 0) {
    throw new LocalPublicHousingMapSnapshotError(`${path}.complexIds`)
  }
  return {
    regionCode: nonEmptyStringAt(
      field(region, 'regionCode', path),
      `${path}.regionCode`,
    ),
    name: nonEmptyStringAt(field(region, 'name', path), `${path}.name`),
    anchor: {
      latitude: coordinateAt(
        field(anchor, 'latitude', anchorPath),
        `${anchorPath}.latitude`,
        -90,
        90,
      ),
      longitude: coordinateAt(
        field(anchor, 'longitude', anchorPath),
        `${anchorPath}.longitude`,
        -180,
        180,
      ),
    },
    complexIds: uniqueIds(complexIds),
  }
}

function joinComplexesToRegions(
  regions: readonly DecodedRegion[],
  mapComplexes: ReturnType<typeof toMapComplexes>,
): LocalMapComplexSnapshot[] {
  const complexById = new Map(
    mapComplexes.map((complex) => [complex.complexId, complex]),
  )
  const assignedIds = new Set<string>()

  return regions.flatMap((region, regionIndex) => region.complexIds.map(
    (complexId, complexIndex) => {
      const path = `$.mapRegions[${regionIndex}].complexIds[${complexIndex}]`
      const complex = complexById.get(complexId)
      if (complex === undefined || assignedIds.has(complexId)) {
        throw new LocalPublicHousingMapSnapshotError(path)
      }
      assignedIds.add(complexId)
      return {
        complexId,
        regionCode: region.regionCode,
        name: complex.name,
        latitude: complex.latitude,
        longitude: complex.longitude,
      }
    },
  ))
}

function validateEveryComplexAssigned(
  complexes: readonly LocalMapComplexSnapshot[],
  mapComplexes: ReturnType<typeof toMapComplexes>,
) {
  if (complexes.length !== mapComplexes.length) {
    throw new LocalPublicHousingMapSnapshotError('$.mapRegions')
  }
}

function validateUniqueRegionCodes(regions: readonly DecodedRegion[]) {
  const regionCodes = new Set<string>()
  regions.forEach((region, index) => {
    if (regionCodes.has(region.regionCode)) {
      throw new LocalPublicHousingMapSnapshotError(
        `$.mapRegions[${index}].regionCode`,
      )
    }
    regionCodes.add(region.regionCode)
  })
}

function uniqueIds(ids: readonly string[]) {
  return [...new Set(ids)]
}

function recordAt(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new LocalPublicHousingMapSnapshotError(path)
  }
  return value as Record<string, unknown>
}

function arrayField(
  record: Record<string, unknown>,
  name: string,
  path: string,
): readonly unknown[] {
  const value = field(record, name, path)
  if (!Array.isArray(value)) {
    throw new LocalPublicHousingMapSnapshotError(`${path}.${name}`)
  }
  return value
}

function field(record: Record<string, unknown>, name: string, path: string) {
  if (!Object.hasOwn(record, name)) {
    throw new LocalPublicHousingMapSnapshotError(`${path}.${name}`)
  }
  return record[name]
}

function nonEmptyStringAt(value: unknown, path: string) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new LocalPublicHousingMapSnapshotError(path)
  }
  return value.trim()
}

function positiveIdAt(value: unknown, path: string) {
  if (!Number.isSafeInteger(value) || Number(value) <= 0) {
    throw new LocalPublicHousingMapSnapshotError(path)
  }
  return String(value)
}

function coordinateAt(
  value: unknown,
  path: string,
  minimum: number,
  maximum: number,
) {
  if (
    typeof value !== 'number'
    || !Number.isFinite(value)
    || value < minimum
    || value > maximum
  ) {
    throw new LocalPublicHousingMapSnapshotError(path)
  }
  return value
}
