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
  ['43', '충청북도'],
  ['44', '충청남도'],
  ['47', '경상북도'],
  ['48', '경상남도'],
  ['50', '제주특별자치도'],
  ['51', '강원특별자치도'],
  ['52', '전북특별자치도'],
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
  )
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
