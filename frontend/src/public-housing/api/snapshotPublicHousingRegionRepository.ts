import type { PublicHousingRegion } from '../model/publicHousingRegion.ts'
import { provinceNameForRegionCode } from '../model/publicHousingRegion.ts'
import type { PublicHousingRegionRepository } from './publicHousingRegionRepository.ts'
import type { PublicHousingSnapshotV1 } from './snapshotPublicHousingRepository.ts'

export function createSnapshotPublicHousingRegionRepository(
  snapshot: PublicHousingSnapshotV1,
): PublicHousingRegionRepository {
  const regions = collectSnapshotRegions(snapshot)

  return {
    async search(keyword, signal) {
      throwIfAborted(signal)
      const normalizedKeyword = keyword.trim().toLocaleLowerCase('ko-KR')
      if (normalizedKeyword.length === 0) {
        return regions
      }
      return regions.filter((region) => [
        region.regionCode,
        region.provinceName,
        region.districtName ?? '',
        region.displayName,
      ].some((value) => value.toLocaleLowerCase('ko-KR').includes(
        normalizedKeyword,
      )))
    },
  }
}

function collectSnapshotRegions(
  snapshot: PublicHousingSnapshotV1,
): readonly PublicHousingRegion[] {
  const regionsByCode = new Map<string, PublicHousingRegion>()
  const complexNamesById = new Map(snapshot.complexListItems.map((item) => [
    String(item.complexId),
    item.regionName,
  ]))

  for (const [complexId, regionCode] of Object.entries(
    snapshot.complexRegionCodes,
  )) {
    addNamedRegion(regionsByCode, regionCode, complexNamesById.get(complexId))
  }

  const announcementRegionsById = new Map(
    snapshot.announcementListItems.map((item) => [
      String(item.announcementId),
      item.regionNames,
    ]),
  )
  for (const [announcementId, regionCodes] of Object.entries(
    snapshot.announcementRegionCodes,
  )) {
    const regionNames = announcementRegionsById.get(announcementId) ?? []
    if (regionCodes.length === regionNames.length) {
      regionCodes.forEach((regionCode, index) => {
        addNamedRegion(regionsByCode, regionCode, regionNames[index])
      })
      continue
    }

    for (const regionCode of regionCodes) {
      const provinceName = provinceNameForRegionCode(regionCode)
      const matchingNames = regionNames.filter((regionName) =>
        provinceName !== null && regionName.startsWith(provinceName),
      )
      if (matchingNames.length === 1) {
        addNamedRegion(regionsByCode, regionCode, matchingNames[0])
      }
    }
  }

  for (const [parentCode, descendantCodes] of Object.entries(
    snapshot.regionCodeDescendants,
  )) {
    if (regionsByCode.has(parentCode)) {
      continue
    }
    const child = descendantCodes
      .map((regionCode) => regionsByCode.get(regionCode))
      .find((region) => region?.districtName?.includes(' '))
    const parentDistrictName = child?.districtName?.split(' ')[0]
    if (child !== undefined && parentDistrictName !== undefined) {
      regionsByCode.set(parentCode, {
        regionCode: parentCode,
        provinceName: child.provinceName,
        districtName: parentDistrictName,
        displayName: `${child.provinceName} ${parentDistrictName}`,
      })
    }
  }

  return [...regionsByCode.values()].sort((left, right) =>
    left.regionCode.localeCompare(right.regionCode),
  )
}

function addNamedRegion(
  regionsByCode: Map<string, PublicHousingRegion>,
  regionCode: string,
  regionName: string | null | undefined,
) {
  if (regionsByCode.has(regionCode) || regionName === null
    || regionName === undefined) {
    return
  }
  const provinceName = provinceNameForRegionCode(regionCode)
  if (provinceName === null || !regionName.startsWith(provinceName)) {
    return
  }
  const districtName = regionName.slice(provinceName.length).trim()
  if (districtName.length === 0) {
    return
  }
  regionsByCode.set(regionCode, {
    regionCode,
    provinceName,
    districtName,
    displayName: `${provinceName} ${districtName}`,
  })
}

function throwIfAborted(signal: AbortSignal) {
  if (signal.aborted) {
    throw new DOMException('The operation was aborted.', 'AbortError')
  }
}
