import type {
  AgencyCodeFilter,
  AnnouncementSearchFilters,
  ApplicationStatusFilter,
  ComplexSearchFilters,
  RecruitmentTypeFilter,
  RentalTypeFilter,
} from '../api/publicHousingRepository.ts'
import { TOPIC_KEYS, type FilterTopic } from './complexFilterTopics.ts'

export function announcementFiltersFromForm(data: FormData): AnnouncementSearchFilters {
  return {
    ...topicDraftFromForm('region', data),
    ...topicDraftFromForm('rentalType', data),
    ...topicDraftFromForm('applicationStatus', data),
    ...topicDraftFromForm('agency', data),
    ...topicDraftFromForm('recruitmentType', data),
  }
}

export function topicDraftFromForm(topic: FilterTopic, data: FormData) {
  switch (topic) {
    case 'region': {
      const regionCode = textValue(data, 'neighborhoodCode')
        || textValue(data, 'districtCode')
        || textValue(data, 'provinceCode')
      return regionCode === '' ? {} : { regionCode }
    }
    case 'rentalType':
      return optionalValues('rentalTypes', formValues<RentalTypeFilter>(data, 'rentalTypes'))
    case 'applicationStatus':
      return optionalValues('applicationStatuses', formValues<ApplicationStatusFilter>(data, 'applicationStatuses'))
    case 'agency':
      return optionalValues('agencyCodes', formValues<AgencyCodeFilter>(data, 'agencyCodes'))
    case 'recruitmentType':
      return optionalValues('recruitmentTypes', formValues<RecruitmentTypeFilter>(data, 'recruitmentTypes'))
    case 'price':
      return numbersFromForm(data, TOPIC_KEYS.price)
    case 'exclusiveArea':
      return numbersFromForm(data, TOPIC_KEYS.exclusiveArea)
    case 'builtYear':
      return numbersFromForm(data, TOPIC_KEYS.builtYear)
  }
}

export function topicsDraftFromForm(
  topics: readonly (readonly [FilterTopic, string])[],
  data: FormData,
) {
  return topics.reduce<ComplexSearchFilters>(
    (draft, [topic]) => ({ ...draft, ...topicDraftFromForm(topic, data) }),
    {},
  )
}

export function replaceTopic(
  filters: ComplexSearchFilters,
  topic: FilterTopic,
  draft: ComplexSearchFilters,
) {
  const next = { ...filters }
  TOPIC_KEYS[topic].forEach((key) => delete next[key])
  return { ...next, ...draft }
}

export function replaceTopics(
  filters: ComplexSearchFilters,
  topics: readonly (readonly [FilterTopic, string])[],
  draft: ComplexSearchFilters,
): ComplexSearchFilters {
  const next = topics.reduce(
    (current, [topic]) => replaceTopic(current, topic, {}),
    filters,
  )
  return { ...next, ...draft }
}

export function topicRangeError(topic: FilterTopic, draft: ComplexSearchFilters) {
  return topic === 'builtYear'
    && draft.builtYearFrom != null
    && draft.builtYearTo != null
    && draft.builtYearFrom > draft.builtYearTo
    ? '최소 준공년도는 최대 준공년도보다 클 수 없습니다.'
    : null
}

function optionalValues<Value extends string>(
  key:
    | 'rentalTypes'
    | 'applicationStatuses'
    | 'agencyCodes'
    | 'recruitmentTypes',
  values: readonly Value[],
): ComplexSearchFilters {
  return values.length === 0 ? {} : { [key]: values } as ComplexSearchFilters
}

function numbersFromForm(
  data: FormData,
  names: readonly (keyof ComplexSearchFilters)[],
) {
  return Object.fromEntries(names.flatMap((name) => {
    const value = textValue(data, name)
    return value === '' ? [] : [[name, Number(value)]]
  })) as ComplexSearchFilters
}

function textValue(data: FormData, name: string) {
  const value = data.get(name)
  return typeof value === 'string' ? value.trim() : ''
}

function formValues<Value extends string>(data: FormData, name: string) {
  return data.getAll(name).filter(
    (value): value is Value => typeof value === 'string',
  )
}
