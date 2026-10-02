import type { ComplexSearchFilters } from '../api/publicHousingRepository.ts'
import { provinceNameForRegionCode } from '../model/publicHousingRegion.ts'
import { formatHousingMoney } from '../presentation/housingMoney.ts'
import { AGENCY_OPTIONS, APPLICATION_STATUS_OPTIONS, RECRUITMENT_TYPE_OPTIONS, RENTAL_TYPE_OPTIONS } from './searchFilterOptions.ts'
import type { FilterTopic } from './complexFilterTopics.ts'

export function topicSummary(
  filters: ComplexSearchFilters,
  topic: FilterTopic,
  resolvedRegionName: string | null,
) {
  switch (topic) {
    case 'region': {
      if (!filters.regionCode) return null
      const name = provinceNameForRegionCode(filters.regionCode)
      const label = name === null
        ? filters.regionCode
        : filters.regionCode.length === 5
          ? resolvedRegionName ?? `${name} ${filters.regionCode}`
          : name
      return compactRegionLabel(label)
    }
    case 'rentalType':
      return optionsSummary(filters.rentalTypes, RENTAL_TYPE_OPTIONS)
    case 'applicationStatus':
      return optionsSummary(filters.applicationStatuses, APPLICATION_STATUS_OPTIONS)
    case 'agency':
      return optionsSummary(filters.agencyCodes, AGENCY_OPTIONS)
    case 'recruitmentType':
      return optionsSummary(filters.recruitmentTypes, RECRUITMENT_TYPE_OPTIONS)
    case 'price': {
      const deposit = rangeSummary(filters.minDeposit, filters.maxDeposit, formatHousingMoney)
      const rent = rangeSummary(filters.minMonthlyRent, filters.maxMonthlyRent, formatHousingMoney)
      return [
        deposit,
        rent === null ? null : `월 ${rent}`,
      ].filter((value): value is string => value !== null).join(' · ') || null
    }
    case 'exclusiveArea':
      return suffixedRangeSummary(
        filters.minExclusiveArea,
        filters.maxExclusiveArea,
        (value) => compact(value / 3.3),
        '평',
      )
    case 'builtYear':
      return suffixedRangeSummary(
        filters.builtYearFrom,
        filters.builtYearTo,
        String,
        '년',
      )
  }
}

function optionsSummary(
  values: readonly string[] | undefined,
  options: readonly (readonly [string, string])[],
) {
  if (!values?.length) return null
  const labels = values.map((selected) =>
    options.find(([value]) => value === selected)?.[1] ?? selected)
  return labels.length > 1 ? `${labels[0]} 외 ${labels.length - 1}` : labels[0]
}

const COMPACT_PROVINCE_NAMES = new Map<string, string>([
  ['서울특별시', '서울'],
  ['전남광주통합특별시', '광주'],
  ['부산광역시', '부산'],
  ['대구광역시', '대구'],
  ['인천광역시', '인천'],
  ['대전광역시', '대전'],
  ['울산광역시', '울산'],
  ['세종특별자치시', '세종'],
  ['경기도', '경기'],
  ['충청북도', '충북'],
  ['충청남도', '충남'],
  ['경상북도', '경북'],
  ['경상남도', '경남'],
  ['제주특별자치도', '제주'],
  ['강원특별자치도', '강원'],
  ['전북특별자치도', '전북'],
])

function compactRegionLabel(label: string) {
  const province = [...COMPACT_PROVINCE_NAMES.entries()].find(
    ([fullName]) => label === fullName || label.startsWith(`${fullName} `),
  )
  if (province === undefined) {
    return label
  }
  const [fullName, compactName] = province
  return `${compactName}${label.slice(fullName.length)}`
}

function rangeSummary(
  minimum: number | null | undefined,
  maximum: number | null | undefined,
  format: (value: number) => string,
) {
  if (minimum == null && maximum == null) return null
  if (minimum == null) return `${format(maximum as number)} 이하`
  if (maximum == null) return `${format(minimum)} 이상`
  return `${format(minimum)}~${format(maximum)}`
}

function suffixedRangeSummary(
  minimum: number | null | undefined,
  maximum: number | null | undefined,
  format: (value: number) => string,
  suffix: string,
) {
  if (minimum == null && maximum == null) return null
  if (minimum == null) return `${format(maximum as number)}${suffix} 이하`
  if (maximum == null) return `${format(minimum)}${suffix} 이상`
  return `${format(minimum)}~${format(maximum)}${suffix}`
}

export function formatDepositTick(value: number) {
  return value === 500_000_000 ? '5억+' : value === 0 ? '0' : formatHousingMoney(value)
}

export function formatMonthlyRentTick(value: number) {
  return value === 600_000 ? '60만원+' : value === 0 ? '0' : formatHousingMoney(value)
}

export function formatArea(value: number) {
  return `${compact(value / 3.3)}평`
}

export function formatAreaTick(value: number) {
  return value === 132 ? '40평+' : value === 0 ? '0' : formatArea(value)
}

function compact(value: number) {
  return String(Number(value.toFixed(1)))
}
