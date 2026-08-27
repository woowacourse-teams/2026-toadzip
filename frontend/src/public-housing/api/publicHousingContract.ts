import type {
  HousingAgency,
  RawComplexListItem,
  RawComplexPage,
  RawMapComplex,
  RawMapComplexResponse,
  RawRepresentativeAnnouncement,
} from '../model/publicHousing.ts'

export class PublicHousingContractError extends Error {
  readonly path: string

  constructor(path: string) {
    super(`공공주택 API 응답 형식이 올바르지 않습니다: ${path}`)
    this.name = 'PublicHousingContractError'
    this.path = path
  }
}

export function decodeComplexPageEnvelope(value: unknown): RawComplexPage {
  const envelope = recordAt(value, '$')
  return decodeComplexPage(recordField(envelope, 'data', '$'), '$.data')
}

export function decodeMapComplexEnvelope(
  value: unknown,
): RawMapComplexResponse {
  const envelope = recordAt(value, '$')
  const data = recordAt(recordField(envelope, 'data', '$'), '$.data')
  const items = arrayAt(recordField(data, 'items', '$.data'), '$.data.items')

  return {
    items: items.map((item, index) =>
      decodeMapComplex(item, `$.data.items[${index}]`),
    ),
  }
}

function decodeComplexPage(value: unknown, path: string): RawComplexPage {
  const response = recordAt(value, path)
  const items = arrayAt(recordField(response, 'items', path), `${path}.items`)

  return {
    items: items.map((item, index) =>
      decodeComplexListItem(item, `${path}.items[${index}]`),
    ),
    nextCursor: nullableStringAt(
      recordField(response, 'nextCursor', path),
      `${path}.nextCursor`,
    ),
    hasNext: booleanAt(
      recordField(response, 'hasNext', path),
      `${path}.hasNext`,
    ),
  }
}

function decodeComplexListItem(
  value: unknown,
  path: string,
): RawComplexListItem {
  const item = recordAt(value, path)

  return {
    complexId: positiveSafeIntegerAt(
      recordField(item, 'complexId', path),
      `${path}.complexId`,
    ),
    thumbnailImageUrl: nullableStringAt(
      recordField(item, 'thumbnailImageUrl', path),
      `${path}.thumbnailImageUrl`,
    ),
    regionName: nullableStringAt(
      recordField(item, 'regionName', path),
      `${path}.regionName`,
    ),
    name: nullableStringAt(recordField(item, 'name', path), `${path}.name`),
    rentalType: nullableStringAt(
      recordField(item, 'rentalType', path),
      `${path}.rentalType`,
    ),
    agency: decodeNullableAgency(
      recordField(item, 'agency', path),
      `${path}.agency`,
    ),
    exclusiveAreaMin: nullableFiniteNumberAt(
      recordField(item, 'exclusiveAreaMin', path),
      `${path}.exclusiveAreaMin`,
    ),
    exclusiveAreaMax: nullableFiniteNumberAt(
      recordField(item, 'exclusiveAreaMax', path),
      `${path}.exclusiveAreaMax`,
    ),
    depositMin: nullableSafeIntegerAt(
      recordField(item, 'depositMin', path),
      `${path}.depositMin`,
    ),
    depositMax: nullableSafeIntegerAt(
      recordField(item, 'depositMax', path),
      `${path}.depositMax`,
    ),
    monthlyRentMin: nullableSafeIntegerAt(
      recordField(item, 'monthlyRentMin', path),
      `${path}.monthlyRentMin`,
    ),
    monthlyRentMax: nullableSafeIntegerAt(
      recordField(item, 'monthlyRentMax', path),
      `${path}.monthlyRentMax`,
    ),
    representativeAnnouncement: decodeNullableRepresentativeAnnouncement(
      recordField(item, 'representativeAnnouncement', path),
      `${path}.representativeAnnouncement`,
    ),
  }
}

function decodeMapComplex(value: unknown, path: string): RawMapComplex {
  const item = recordAt(value, path)

  return {
    complexId: positiveSafeIntegerAt(
      recordField(item, 'complexId', path),
      `${path}.complexId`,
    ),
    name: nullableStringAt(recordField(item, 'name', path), `${path}.name`),
    latitude: finiteNumberAt(
      recordField(item, 'latitude', path),
      `${path}.latitude`,
    ),
    longitude: finiteNumberAt(
      recordField(item, 'longitude', path),
      `${path}.longitude`,
    ),
    rentalType: nullableStringAt(
      recordField(item, 'rentalType', path),
      `${path}.rentalType`,
    ),
    agency: decodeNullableAgency(
      recordField(item, 'agency', path),
      `${path}.agency`,
    ),
    exclusiveAreaMin: nullableFiniteNumberAt(
      recordField(item, 'exclusiveAreaMin', path),
      `${path}.exclusiveAreaMin`,
    ),
    exclusiveAreaMax: nullableFiniteNumberAt(
      recordField(item, 'exclusiveAreaMax', path),
      `${path}.exclusiveAreaMax`,
    ),
    depositMin: nullableSafeIntegerAt(
      recordField(item, 'depositMin', path),
      `${path}.depositMin`,
    ),
    depositMax: nullableSafeIntegerAt(
      recordField(item, 'depositMax', path),
      `${path}.depositMax`,
    ),
    monthlyRentMin: nullableSafeIntegerAt(
      recordField(item, 'monthlyRentMin', path),
      `${path}.monthlyRentMin`,
    ),
    monthlyRentMax: nullableSafeIntegerAt(
      recordField(item, 'monthlyRentMax', path),
      `${path}.monthlyRentMax`,
    ),
  }
}

function decodeAgency(value: unknown, path: string): HousingAgency {
  const agency = recordAt(value, path)

  return {
    code: nullableStringAt(recordField(agency, 'code', path), `${path}.code`),
    name: nullableStringAt(recordField(agency, 'name', path), `${path}.name`),
  }
}

function decodeNullableAgency(
  value: unknown,
  path: string,
): HousingAgency | null {
  if (value === null) {
    return null
  }
  return decodeAgency(value, path)
}

function decodeNullableRepresentativeAnnouncement(
  value: unknown,
  path: string,
): RawRepresentativeAnnouncement | null {
  if (value === null) {
    return null
  }

  const announcement = recordAt(value, path)
  return {
    announcementId: positiveSafeIntegerAt(
      recordField(announcement, 'announcementId', path),
      `${path}.announcementId`,
    ),
    publicationType: nullableStringAt(
      recordField(announcement, 'publicationType', path),
      `${path}.publicationType`,
    ),
    applicationStatus: nullableStringAt(
      recordField(announcement, 'applicationStatus', path),
      `${path}.applicationStatus`,
    ),
    applicationEndAt: nullableDateStringAt(
      recordField(announcement, 'applicationEndAt', path),
      `${path}.applicationEndAt`,
    ),
    dDay: nullableSafeIntegerAt(
      recordField(announcement, 'dDay', path),
      `${path}.dDay`,
    ),
  }
}

function recordField(
  record: Record<string, unknown>,
  field: string,
  path: string,
): unknown {
  if (!Object.hasOwn(record, field)) {
    throw new PublicHousingContractError(`${path}.${field}`)
  }
  return record[field]
}

function recordAt(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new PublicHousingContractError(path)
  }
  return value as Record<string, unknown>
}

function arrayAt(value: unknown, path: string): readonly unknown[] {
  if (!Array.isArray(value)) {
    throw new PublicHousingContractError(path)
  }
  return value
}

function stringAt(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw new PublicHousingContractError(path)
  }
  return value
}

function nullableStringAt(value: unknown, path: string): string | null {
  if (value === null) {
    return null
  }
  return stringAt(value, path)
}

function booleanAt(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') {
    throw new PublicHousingContractError(path)
  }
  return value
}

function finiteNumberAt(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new PublicHousingContractError(path)
  }
  return value
}

function nullableFiniteNumberAt(value: unknown, path: string): number | null {
  if (value === null) {
    return null
  }
  return finiteNumberAt(value, path)
}

function positiveSafeIntegerAt(value: unknown, path: string): number {
  const integer = safeIntegerAt(value, path)
  if (integer <= 0) {
    throw new PublicHousingContractError(path)
  }
  return integer
}

function safeIntegerAt(value: unknown, path: string): number {
  if (!Number.isSafeInteger(value)) {
    throw new PublicHousingContractError(path)
  }
  return value as number
}

function nullableSafeIntegerAt(value: unknown, path: string): number | null {
  if (value === null) {
    return null
  }
  return safeIntegerAt(value, path)
}

function dateStringAt(value: unknown, path: string): string {
  const date = stringAt(value, path)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    throw new PublicHousingContractError(path)
  }
  return date
}

function nullableDateStringAt(value: unknown, path: string): string | null {
  if (value === null) {
    return null
  }
  return dateStringAt(value, path)
}
