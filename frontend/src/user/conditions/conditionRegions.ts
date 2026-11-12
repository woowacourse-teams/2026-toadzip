import catalog from '../../public-housing/regions/regionCatalog.json'
import {
  PUBLIC_HOUSING_PROVINCE_OPTIONS,
  districtRegionOptionsForProvince,
  type PublicHousingRegion,
} from '../../public-housing/model/publicHousingRegion'

const regions: readonly PublicHousingRegion[] = catalog.items

export function provinceOptions(): readonly (readonly [string, string])[] {
  return PUBLIC_HOUSING_PROVINCE_OPTIONS
}

export function districtOptions(provinceCode: string): [string, string][] {
  return districtRegionOptionsForProvince(regions, provinceCode)
    .map((region) => [region.regionCode, region.districtName ?? region.displayName])
}

const SHORT_PROVINCE: Record<string, string> = {
  11: '서울', 12: '전남광주', 26: '부산', 27: '대구', 28: '인천', 30: '대전', 31: '울산', 36: '세종',
  41: '경기', 51: '강원', 43: '충북', 44: '충남', 52: '전북', 47: '경북', 48: '경남', 50: '제주',
}

export function regionLabel(code: string): string {
  const region = regions.find((item) => item.regionCode === code)
  if (!region) return code
  return [SHORT_PROVINCE[code.slice(0, 2)] ?? region.provinceName, region.districtName].filter(Boolean).join(' ')
}

export function provinceOf(code: string): string {
  return code.slice(0, 2)
}
