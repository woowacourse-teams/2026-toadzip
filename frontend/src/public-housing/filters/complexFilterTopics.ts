import type { ComplexSearchFilters } from '../api/publicHousingRepository.ts'

export type FilterTopic =
  | 'region'
  | 'rentalType'
  | 'applicationStatus'
  | 'agency'
  | 'recruitmentType'
  | 'price'
  | 'exclusiveArea'
  | 'builtYear'

export type DesktopFilterTopic =
  | Exclude<FilterTopic, 'region' | 'agency' | 'recruitmentType'>
  | 'detail'

export const TOPICS = [
  ['region', '지역'],
  ['rentalType', '임대유형'],
  ['applicationStatus', '모집상태'],
  ['agency', '공급기관'],
  ['recruitmentType', '모집유형'],
  ['price', '가격'],
  ['exclusiveArea', '전용면적'],
  ['builtYear', '준공년도'],
] as const satisfies readonly (readonly [FilterTopic, string])[]

export const DESKTOP_PRIMARY_TOPICS = [
  ['rentalType', '임대유형'],
  ['applicationStatus', '모집상태'],
  ['price', '가격'],
  ['exclusiveArea', '전용면적'],
  ['builtYear', '준공년도'],
] as const satisfies readonly (readonly [DesktopFilterTopic, string])[]

export const DESKTOP_TOPICS = [
  ...DESKTOP_PRIMARY_TOPICS,
  ['detail', '상세 필터'],
] as const satisfies readonly (readonly [DesktopFilterTopic, string])[]

export const DETAIL_FILTER_TOPICS = [
  ['region', '지역'],
  ['agency', '공급기관'],
  ['recruitmentType', '모집유형'],
] as const satisfies readonly (readonly [FilterTopic, string])[]

export const MOBILE_PRIMARY_TOPICS = [
  ['region', '지역'],
  ['rentalType', '임대유형'],
  ['price', '가격'],
] as const satisfies readonly (readonly [FilterTopic, string])[]

export const MOBILE_SHEET_TOPICS = [
  ['region', '지역'],
  ['rentalType', '임대유형'],
  ['price', '가격'],
  ['exclusiveArea', '전용면적'],
  ['applicationStatus', '모집상태'],
  ['agency', '공급기관'],
  ['recruitmentType', '모집유형'],
  ['builtYear', '준공년도'],
] as const satisfies readonly (readonly [FilterTopic, string])[]

export const POPOVER_WIDTHS = {
  region: 320,
  rentalType: 320,
  applicationStatus: 320,
  agency: 320,
  recruitmentType: 320,
  price: 420,
  exclusiveArea: 380,
  builtYear: 320,
} as const satisfies Record<FilterTopic, number>

export const TOPIC_KEYS = {
  region: ['regionCode'],
  rentalType: ['rentalTypes'],
  applicationStatus: ['applicationStatuses'],
  agency: ['agencyCodes'],
  recruitmentType: ['recruitmentTypes'],
  price: [
    'minDeposit',
    'maxDeposit',
    'minMonthlyRent',
    'maxMonthlyRent',
  ],
  exclusiveArea: ['minExclusiveArea', 'maxExclusiveArea'],
  builtYear: ['builtYearFrom', 'builtYearTo'],
} as const satisfies Record<
  FilterTopic,
  readonly (keyof ComplexSearchFilters)[]
>

export function desktopTopicLabel(topic: DesktopFilterTopic | null) {
  if (topic === 'detail') {
    return '상세'
  }
  return TOPICS.find(([candidate]) => candidate === topic)?.[1] ?? null
}

export function desktopTopicFor(topic: FilterTopic | null): DesktopFilterTopic {
  switch (topic) {
    case null:
    case 'region':
    case 'agency':
    case 'recruitmentType':
      return 'detail'
    default:
      return topic
  }
}
