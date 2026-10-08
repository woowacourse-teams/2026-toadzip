import catalog from '../regions/regionCatalog.json'

export const PUBLIC_HOUSING_PROVINCE_OPTIONS = [
  ['11', '서울특별시'],
  ['12', '전남광주통합특별시'],
  ['26', '부산광역시'],
  ['27', '대구광역시'],
  ['28', '인천광역시'],
  ['30', '대전광역시'],
  ['31', '울산광역시'],
  ['36', '세종특별자치시'],
  ['41', '경기도'],
  ['51', '강원특별자치도'],
  ['43', '충청북도'],
  ['44', '충청남도'],
  ['52', '전북특별자치도'],
  ['47', '경상북도'],
  ['48', '경상남도'],
  ['50', '제주특별자치도'],
] as const

export interface PublicHousingRegion {
  readonly regionCode: string
  readonly provinceName: string
  readonly districtName: string | null
  readonly displayName: string
}

export function districtRegionOptionsForProvince(
  regions: readonly PublicHousingRegion[],
  provinceCode: string,
): readonly PublicHousingRegion[] {
  const provinceDistricts = regions.filter((region) =>
    region.regionCode.length === 5
    && region.regionCode.startsWith(provinceCode),
  )

  return provinceDistricts.filter((candidate) =>
    !provinceDistricts.some((parent) => isParentCity(parent, candidate)),
  ).sort((left, right) => compareRegionCodes(left.regionCode, right.regionCode))
}

function isParentCity(
  parent: PublicHousingRegion,
  candidate: PublicHousingRegion,
) {
  return parent.regionCode !== candidate.regionCode
    && parent.regionCode.endsWith('0')
    && parent.regionCode.slice(0, 4) === candidate.regionCode.slice(0, 4)
    && parent.districtName !== null
    && candidate.districtName !== null
    && candidate.districtName.startsWith(`${parent.districtName} `)
}

export function provinceNameForRegionCode(regionCode: string) {
  return PUBLIC_HOUSING_PROVINCE_OPTIONS.find(
    ([code]) => code === regionCode.slice(0, 2),
  )?.[1] ?? null
}

const catalogByCode = new Map(catalog.items.map((region) => [region.regionCode, region]))

export function compareRegionCodes(left: string, right: string) {
  const leftKey = catalogByCode.get(left)?.sortKey ?? `99.${left}`
  const rightKey = catalogByCode.get(right)?.sortKey ?? `99.${right}`
  return leftKey.localeCompare(rightKey) || left.localeCompare(right)
}

export function findCatalogRegion(code: string): PublicHousingRegion | null {
  return catalogByCode.get(code) ?? null
}

export function neighborhoodRegionOptionsForDistrict(
  regions: readonly PublicHousingRegion[], districtCode: string,
): readonly PublicHousingRegion[] {
  if (!districtCode) return []
  const district = regions.find((region) => region.regionCode === districtCode)
  return regions.filter((region) => region.regionCode.length === 10
    && (region.regionCode.startsWith(districtCode)
      || (district?.districtName != null && districtCode.endsWith('0')
        && region.regionCode.startsWith(districtCode.slice(0, 4))
        && region.districtName?.startsWith(`${district.districtName} `))))
    .sort((left, right) => compareRegionCodes(left.regionCode, right.regionCode))
}

export function neighborhoodLabel(region: PublicHousingRegion, districtCode: string) {
  if (region.regionCode.startsWith(districtCode)) return region.displayName.split(' ').at(-1) ?? region.displayName
  const parent = findCatalogRegion(districtCode)
  return parent ? region.displayName.slice(parent.displayName.length).trim() : region.displayName
}
