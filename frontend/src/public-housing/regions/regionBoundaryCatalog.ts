import type { RegionBoundaryMetadata } from './regionBoundary.ts'
import index from './regionBoundaryIndex.json'
import { createRegionBoundaryRepository } from './regionBoundaryRepository.ts'
import { provinceNameForRegionCode } from '../model/publicHousingRegion.ts'

const metadataByCode = new Map<string, RegionBoundaryMetadata>(
  index.regions.map((metadata) => [metadata.regionCode, metadata]),
)

export function findRegionBoundaryMetadata(regionCode: string): RegionBoundaryMetadata | null {
  return metadataByCode.get(regionCode) ?? null
}

export function findRegionBoundaryName(regionCode: string): string | null {
  return metadataByCode.get(regionCode)?.name
    ?? index.unavailable.find((region) => region.regionCode === regionCode)?.name
    ?? (regionCode.length === 2 ? provinceNameForRegionCode(regionCode) : null)
    ?? null
}

export function findRegionSearchBounds(regionCode: string) {
  const metadata = metadataByCode.get(regionCode)
  if (metadata) return metadata.bounds
  if (regionCode.length !== 2) return null
  const members = index.regions.filter((region) => region.regionCode.startsWith(regionCode))
  if (members.length === 0) return null
  return members.reduce((bounds, region) => ({
    southWestLat: Math.min(bounds.southWestLat, region.bounds.southWestLat),
    southWestLng: Math.min(bounds.southWestLng, region.bounds.southWestLng),
    northEastLat: Math.max(bounds.northEastLat, region.bounds.northEastLat),
    northEastLng: Math.max(bounds.northEastLng, region.bounds.northEastLng),
  }), members[0].bounds)
}

export const regionBoundaryRepository = createRegionBoundaryRepository({
  version: index.version,
  findMetadata: findRegionBoundaryMetadata,
})
