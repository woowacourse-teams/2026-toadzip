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

export type DesktopFilterTopic = FilterTopic | 'all'

export const TOPICS = [
  ['region', '지역'], ['rentalType', '임대유형'],
  ['applicationStatus', '모집상태'], ['price', '가격'],
  ['exclusiveArea', '전용면적'], ['builtYear', '준공년도'],
  ['agency', '공급기관'], ['recruitmentType', '모집유형'],
] as const satisfies readonly (readonly [FilterTopic, string])[]
export const DESKTOP_TOPICS = [['all', '전체'], ...TOPICS] as const
export const DESKTOP_PRIMARY_TOPICS = DESKTOP_TOPICS

export const DETAIL_FILTER_TOPICS = [
  ['region', '지역'],
  ['agency', '공급기관'],
  ['recruitmentType', '모집유형'],
] as const satisfies readonly (readonly [FilterTopic, string])[]

export const MOBILE_PRIMARY_TOPICS = DESKTOP_TOPICS
export const MOBILE_SHEET_TOPICS = TOPICS

export const POPOVER_WIDTHS = {
  all: 420,
  region: 320,
  rentalType: 320,
  applicationStatus: 320,
  agency: 320,
  recruitmentType: 320,
  price: 420,
  exclusiveArea: 380,
  builtYear: 320,
} as const satisfies Record<DesktopFilterTopic, number>

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
  return DESKTOP_TOPICS.find(([candidate]) => candidate === topic)?.[1] ?? null
}

export function desktopTopicFor(topic: DesktopFilterTopic | null): DesktopFilterTopic {
  return topic ?? 'all'
}
