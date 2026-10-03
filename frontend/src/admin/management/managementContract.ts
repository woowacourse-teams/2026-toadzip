export type ManagementResource = 'complexes' | 'announcements'

export type ManagementSummary = {
  id: number
  name: string
  subtitle: string
  provider: string
  rental: string
  deleted: boolean
  modified: boolean
  reviewRequired: boolean
  updatedAt: string | null
}

export type JsonValue = string | number | boolean | null | { [key: string]: JsonValue } | JsonValue[]
export type ManagementValues = { [key: string]: JsonValue }

export type ManagementHousingType = {
  id: number
  name: string
  exclusiveArea: number
  householdCount: number | null
}

export type ManagementSupplyRow = {
  id: number
  housingComplexId: number | null
  housingComplexName: string | null
  housingTypeId: number | null
  modified: boolean
  data: ManagementValues
}

export type ManagementDetailData = {
  summary: ManagementSummary
  sourceIdentifier: string
  data: ManagementValues
  housingTypes: ManagementHousingType[]
  announcements: ManagementSummary[]
  supplyRows: ManagementSupplyRow[]
  scheduleReviewed: boolean
  schedules: ManagementValues[]
}

export type ManagementPage = {
  items: ManagementSummary[]
  page: number
  hasNext: boolean
  totalElements: number
  totalPages: number
}

export type ManagementChange = {
  id: number
  action: string
  actor: string
  occurredAt: string
  beforeValue: string
  afterValue: string
}

export function parseManagementPage(value: unknown): ManagementPage {
  if (!isRecord(value) || !Array.isArray(value.items) || !value.items.every(isManagementSummary)
    || typeof value.page !== 'number' || typeof value.hasNext !== 'boolean'
    || typeof value.totalElements !== 'number' || !Number.isSafeInteger(value.totalElements) || value.totalElements < 0
    || typeof value.totalPages !== 'number' || !Number.isSafeInteger(value.totalPages) || value.totalPages < 0) {
    throw new Error('목록 응답이 올바르지 않습니다.')
  }
  return {
    items: value.items,
    page: value.page,
    hasNext: value.hasNext,
    totalElements: value.totalElements,
    totalPages: value.totalPages,
  }
}

export function parseManagementDetail(value: unknown): ManagementDetailData {
  if (!isRecord(value) || !isManagementSummary(value.summary) || typeof value.sourceIdentifier !== 'string'
    || !isManagementValues(value.data) || typeof value.data.version !== 'number') {
    throw new Error('상세 응답이 올바르지 않습니다.')
  }

  const housingTypes: ManagementHousingType[] = []
  if (Array.isArray(value.housingTypes)) {
    for (const item of value.housingTypes) {
      if (!isRecord(item) || typeof item.id !== 'number' || typeof item.name !== 'string'
        || typeof item.exclusiveArea !== 'number' || !isNullableNumber(item.householdCount)) {
        throw new Error('주택형 응답이 올바르지 않습니다.')
      }
      housingTypes.push({
        id: item.id, name: item.name, exclusiveArea: item.exclusiveArea, householdCount: item.householdCount,
      })
    }
  }

  const supplyRows: ManagementSupplyRow[] = []
  if (Array.isArray(value.supplyRows)) {
    for (const item of value.supplyRows) {
      if (!isRecord(item) || typeof item.id !== 'number' || !isManagementValues(item.data)
        || typeof item.modified !== 'boolean' || !isNullableNumber(item.housingComplexId)
        || !isNullableNumber(item.housingTypeId)
        || (item.housingComplexName !== null && typeof item.housingComplexName !== 'string')) {
        throw new Error('공급정보 응답이 올바르지 않습니다.')
      }
      supplyRows.push({
        id: item.id, data: item.data, modified: item.modified, housingComplexId: item.housingComplexId,
        housingTypeId: item.housingTypeId, housingComplexName: item.housingComplexName,
      })
    }
  }

  const announcements = value.announcements === undefined ? [] : value.announcements
  if (!Array.isArray(announcements) || !announcements.every(isManagementSummary)) {
    throw new Error('연결 공고 응답이 올바르지 않습니다.')
  }
  const schedules = value.schedules === undefined ? [] : value.schedules
  if (!Array.isArray(schedules) || !schedules.every(isManagementValues)) {
    throw new Error('접수 일정 응답이 올바르지 않습니다.')
  }

  return {
    summary: value.summary,
    data: value.data,
    sourceIdentifier: value.sourceIdentifier,
    housingTypes,
    announcements,
    supplyRows,
    scheduleReviewed: value.scheduleReviewed === true,
    schedules,
  }
}

export function parseManagementHistory(value: unknown): ManagementChange[] {
  if (!Array.isArray(value)) throw new Error('수정 이력 응답이 올바르지 않습니다.')
  return value.map((item) => {
    if (!isRecord(item) || typeof item.id !== 'number' || typeof item.action !== 'string'
      || typeof item.actor !== 'string' || typeof item.occurredAt !== 'string'
      || typeof item.beforeValue !== 'string' || typeof item.afterValue !== 'string') {
      throw new Error('수정 이력 응답이 올바르지 않습니다.')
    }
    return {
      id: item.id, action: item.action, actor: item.actor, occurredAt: item.occurredAt,
      beforeValue: item.beforeValue, afterValue: item.afterValue,
    }
  })
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNullableNumber(value: unknown): value is number | null {
  return value === null || typeof value === 'number'
}

function isManagementSummary(value: unknown): value is ManagementSummary {
  return isRecord(value) && typeof value.id === 'number' && typeof value.name === 'string'
    && typeof value.subtitle === 'string' && typeof value.provider === 'string' && typeof value.rental === 'string'
    && typeof value.deleted === 'boolean' && typeof value.modified === 'boolean' && typeof value.reviewRequired === 'boolean'
    && (value.updatedAt === null || typeof value.updatedAt === 'string')
}

function isJsonValue(value: unknown): value is JsonValue {
  return value === null || typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number'
    || (Array.isArray(value) && value.every(isJsonValue)) || (isRecord(value) && Object.values(value).every(isJsonValue))
}

function isManagementValues(value: unknown): value is ManagementValues {
  return isRecord(value) && Object.values(value).every(isJsonValue)
}
