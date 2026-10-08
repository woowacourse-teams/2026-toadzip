import type { RegionBoundaryMetadata } from './regionBoundary.ts'
import index from './regionBoundaryIndex.json'
import points from './regionRepresentativePoints.json'

const representativePoints: Readonly<Record<string, { latitude: number; longitude: number }>> = points
import { createRegionBoundaryRepository } from './regionBoundaryRepository.ts'

const metadataByCode = new Map<string, RegionBoundaryMetadata>(
  index.regions.map((metadata) => [metadata.regionCode, {
    ...metadata, representativePoint: representativePoints[metadata.regionCode],
  }]),
)

export function findRegionBoundaryMetadata(regionCode: string): RegionBoundaryMetadata | null {
  return metadataByCode.get(regionCode) ?? null
}

export function findRegionBoundaryName(regionCode: string): string | null {
  return metadataByCode.get(regionCode)?.name
    ?? index.unavailable.find((region) => region.regionCode === regionCode)?.name
    ?? null
}

export const regionBoundaryRepository = createRegionBoundaryRepository({
  version: index.version,
  findMetadata: findRegionBoundaryMetadata,
})
