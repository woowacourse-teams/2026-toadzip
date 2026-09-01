import type {
  AgencyCodeFilter,
  AnnouncementSearchFilters,
  ApplicationStatusFilter,
  ComplexSearchFilters,
  RecruitmentTypeFilter,
  RentalTypeFilter,
  SharedSearchFilters,
} from '../api/publicHousingRepository.ts'

const RENTAL_TYPES = [
  'HAPPY_HOUSING',
  'NATIONAL_RENTAL',
  'PERMANENT_RENTAL',
  'PUBLIC_RENTAL_50Y',
  'INTEGRATED_PUBLIC_RENTAL',
  'REDEVELOPMENT_RENTAL',
  'ETC',
] as const satisfies readonly RentalTypeFilter[]

const APPLICATION_STATUSES = [
  'BEFORE_APPLICATION',
  'APPLYING',
  'CLOSED',
] as const satisfies readonly ApplicationStatusFilter[]

const AGENCY_CODES = [
  'LH',
  'SH',
  'GH',
  'ETC',
] as const satisfies readonly AgencyCodeFilter[]

const RECRUITMENT_TYPES = [
  'NEW',
  'WAITLIST',
  'ETC',
] as const satisfies readonly RecruitmentTypeFilter[]

const COMPLEX_KEYS = [
  'complexRegionCode',
  'complexRentalTypes',
  'complexApplicationStatuses',
  'complexAgencyCodes',
  'complexRecruitmentTypes',
  'complexMinDeposit',
  'complexMaxDeposit',
  'complexMinMonthlyRent',
  'complexMaxMonthlyRent',
  'complexMinExclusiveArea',
  'complexMaxExclusiveArea',
  'complexBuiltYearFrom',
  'complexBuiltYearTo',
] as const

const ANNOUNCEMENT_KEYS = [
  'announcementRegionCode',
  'announcementRentalTypes',
  'announcementApplicationStatuses',
  'announcementAgencyCodes',
  'announcementRecruitmentTypes',
] as const

export function parseComplexSearchFilters(
  search: URLSearchParams,
): ComplexSearchFilters {
  const shared = parseSharedFilters(search, 'complex')
  const [minDeposit, maxDeposit] = validRange(
    nonNegativeInteger(search.get('complexMinDeposit')),
    nonNegativeInteger(search.get('complexMaxDeposit')),
  )
  const [minMonthlyRent, maxMonthlyRent] = validRange(
    nonNegativeInteger(search.get('complexMinMonthlyRent')),
    nonNegativeInteger(search.get('complexMaxMonthlyRent')),
  )
  const [minExclusiveArea, maxExclusiveArea] = validRange(
    nonNegativeDecimal(search.get('complexMinExclusiveArea')),
    nonNegativeDecimal(search.get('complexMaxExclusiveArea')),
  )
  const [builtYearFrom, builtYearTo] = validRange(
    year(search.get('complexBuiltYearFrom')),
    year(search.get('complexBuiltYearTo')),
  )

  return {
    ...shared,
    ...optionalNumber('minDeposit', minDeposit),
    ...optionalNumber('maxDeposit', maxDeposit),
    ...optionalNumber('minMonthlyRent', minMonthlyRent),
    ...optionalNumber('maxMonthlyRent', maxMonthlyRent),
    ...optionalNumber('minExclusiveArea', minExclusiveArea),
    ...optionalNumber('maxExclusiveArea', maxExclusiveArea),
    ...optionalNumber('builtYearFrom', builtYearFrom),
    ...optionalNumber('builtYearTo', builtYearTo),
  }
}

export function parseAnnouncementSearchFilters(
  search: URLSearchParams,
): AnnouncementSearchFilters {
  return parseSharedFilters(search, 'announcement')
}

export function setComplexSearchFilters(
  current: URLSearchParams,
  filters: ComplexSearchFilters,
) {
  const next = new URLSearchParams(current)
  COMPLEX_KEYS.forEach((key) => next.delete(key))
  appendSharedFilters(next, 'complex', filters)
  setOptionalNumber(next, 'complexMinDeposit', filters.minDeposit)
  setOptionalNumber(next, 'complexMaxDeposit', filters.maxDeposit)
  setOptionalNumber(next, 'complexMinMonthlyRent', filters.minMonthlyRent)
  setOptionalNumber(next, 'complexMaxMonthlyRent', filters.maxMonthlyRent)
  setOptionalNumber(next, 'complexMinExclusiveArea', filters.minExclusiveArea)
  setOptionalNumber(next, 'complexMaxExclusiveArea', filters.maxExclusiveArea)
  setOptionalNumber(next, 'complexBuiltYearFrom', filters.builtYearFrom)
  setOptionalNumber(next, 'complexBuiltYearTo', filters.builtYearTo)
  return next
}

export function setAnnouncementSearchFilters(
  current: URLSearchParams,
  filters: AnnouncementSearchFilters,
) {
  const next = new URLSearchParams(current)
  ANNOUNCEMENT_KEYS.forEach((key) => next.delete(key))
  appendSharedFilters(next, 'announcement', filters)
  return next
}

export function searchFiltersSignature(filters: ComplexSearchFilters) {
  return JSON.stringify([
    filters.regionCode ?? null,
    filters.rentalTypes ?? [],
    filters.applicationStatuses ?? [],
    filters.agencyCodes ?? [],
    filters.recruitmentTypes ?? [],
    filters.minDeposit ?? null,
    filters.maxDeposit ?? null,
    filters.minMonthlyRent ?? null,
    filters.maxMonthlyRent ?? null,
    filters.minExclusiveArea ?? null,
    filters.maxExclusiveArea ?? null,
    filters.builtYearFrom ?? null,
    filters.builtYearTo ?? null,
  ])
}

export function hasSearchFilters(filters: ComplexSearchFilters) {
  return Boolean(
    filters.regionCode
    || filters.rentalTypes?.length
    || filters.applicationStatuses?.length
    || filters.agencyCodes?.length
    || filters.recruitmentTypes?.length
    || filters.minDeposit != null
    || filters.maxDeposit != null
    || filters.minMonthlyRent != null
    || filters.maxMonthlyRent != null
    || filters.minExclusiveArea != null
    || filters.maxExclusiveArea != null
    || filters.builtYearFrom != null
    || filters.builtYearTo != null
  )
}

function parseSharedFilters(
  search: URLSearchParams,
  prefix: 'announcement' | 'complex',
): SharedSearchFilters {
  const regionCode = validRegionCode(search.get(`${prefix}RegionCode`))
  const rentalTypes = enumValues(
    search,
    `${prefix}RentalTypes`,
    RENTAL_TYPES,
  )
  const applicationStatuses = enumValues(
    search,
    `${prefix}ApplicationStatuses`,
    APPLICATION_STATUSES,
  )
  const agencyCodes = enumValues(
    search,
    `${prefix}AgencyCodes`,
    AGENCY_CODES,
  )
  const recruitmentTypes = enumValues(
    search,
    `${prefix}RecruitmentTypes`,
    RECRUITMENT_TYPES,
  )

  return {
    ...(regionCode === null ? {} : { regionCode }),
    ...(rentalTypes.length === 0 ? {} : { rentalTypes }),
    ...(applicationStatuses.length === 0 ? {} : { applicationStatuses }),
    ...(agencyCodes.length === 0 ? {} : { agencyCodes }),
    ...(recruitmentTypes.length === 0 ? {} : { recruitmentTypes }),
  }
}

function appendSharedFilters(
  search: URLSearchParams,
  prefix: 'announcement' | 'complex',
  filters: SharedSearchFilters,
) {
  if (filters.regionCode) {
    search.set(`${prefix}RegionCode`, filters.regionCode)
  }
  appendRepeated(search, `${prefix}RentalTypes`, filters.rentalTypes)
  appendRepeated(
    search,
    `${prefix}ApplicationStatuses`,
    filters.applicationStatuses,
  )
  appendRepeated(search, `${prefix}AgencyCodes`, filters.agencyCodes)
  appendRepeated(
    search,
    `${prefix}RecruitmentTypes`,
    filters.recruitmentTypes,
  )
}

function enumValues<T extends string>(
  search: URLSearchParams,
  key: string,
  allowed: readonly T[],
) {
  const allowedValues = new Set<string>(allowed)
  return [...new Set(search.getAll(key))]
    .filter((value): value is T => allowedValues.has(value))
}

function appendRepeated(
  search: URLSearchParams,
  key: string,
  values: readonly string[] | undefined,
) {
  values?.forEach((value) => search.append(key, value))
}

function setOptionalNumber(
  search: URLSearchParams,
  key: string,
  value: number | null | undefined,
) {
  if (value !== null && value !== undefined) {
    search.set(key, plainDecimal(value))
  }
}

function plainDecimal(value: number) {
  if (Object.is(value, -0)) {
    return '0'
  }

  const serialized = String(value)
  const exponentSeparator = serialized.indexOf('e')
  if (exponentSeparator === -1) {
    return serialized
  }

  const coefficient = serialized.slice(0, exponentSeparator)
  const exponent = Number(serialized.slice(exponentSeparator + 1))
  const sign = coefficient.startsWith('-') ? '-' : ''
  const unsignedCoefficient = sign ? coefficient.slice(1) : coefficient
  const [integerPart, fractionPart = ''] = unsignedCoefficient.split('.')
  const digits = `${integerPart}${fractionPart}`
  const decimalPosition = integerPart.length + exponent

  if (decimalPosition <= 0) {
    return `${sign}0.${'0'.repeat(-decimalPosition)}${digits}`
  }
  if (decimalPosition >= digits.length) {
    return `${sign}${digits}${'0'.repeat(decimalPosition - digits.length)}`
  }
  return `${sign}${digits.slice(0, decimalPosition)}.${digits.slice(decimalPosition)}`
}

function validRegionCode(value: string | null) {
  return value !== null && /^(?:\d{2}|\d{5})$/.test(value) ? value : null
}

function nonNegativeInteger(value: string | null) {
  if (value === null || !/^(?:0|[1-9]\d*)$/.test(value)) {
    return null
  }
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}

function nonNegativeDecimal(value: string | null) {
  if (value === null || !/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value)) {
    return null
  }
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function year(value: string | null) {
  const parsed = nonNegativeInteger(value)
  return parsed !== null && parsed >= 1 && parsed <= 9999 ? parsed : null
}

function validRange(
  minimum: number | null,
  maximum: number | null,
): readonly [number | null, number | null] {
  if (minimum !== null && maximum !== null && minimum > maximum) {
    return [null, null]
  }
  return [minimum, maximum]
}

function optionalNumber<Key extends string>(key: Key, value: number | null) {
  return value === null ? {} : { [key]: value } as Record<Key, number>
}
