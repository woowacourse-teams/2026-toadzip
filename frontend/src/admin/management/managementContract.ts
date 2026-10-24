import { isVerificationStatus, type VerificationStatus } from './complexVerificationContract'

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
  complex?: ManagementComplexSummary | null
  announcement?: ManagementAnnouncementSummary | null
}

export type ManagementComplexSummary = {
  sourceIdentifier: string
  completionDate: string | null
  totalHouseholdCount: number
  totalParkingCount: number
  heatingType: string | null
  buildingType: string | null
  corridorType: string | null
  hasElevator: boolean | null
  moveOutCountLastYear: number | null
  verificationStatus?: VerificationStatus
  reviewedFieldCount?: number
}

export type ManagementAnnouncementSummary = {
  sourceIdentifier: string
  originalUrl: string
  recruitmentType: string
  postedDate: string
  applicationStartDate: string
  applicationEndDate: string
  winnerAnnouncementDate: string
  /** ORIGINAL·CORRECTION·CANCELLATION. 이전 서버 응답에는 없다. */
  publicationType?: string
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
  sharedRecruitment?: { id: number; householdCount: number | null } | null
  id: number
  housingComplexId: number | null
  housingComplexName: string | null
  housingTypeId: number | null
  modified: boolean
  // 정제가 소유하는 원천 표기다. 관리자는 data의 공고 표시명만 수정한다.
  sourceComplexName: string
  sourceHousingTypeName: string
  data: ManagementValues
}

export type ManagementDetailData = {
  attachments?: { attachmentId: number; fileName: string; fileType: string; fileUrl: string }[]
  summary: ManagementSummary
  sourceIdentifier: string
  data: ManagementValues
  housingTypes: ManagementHousingType[]
  announcements: ManagementSummary[]
  supplyRows: ManagementSupplyRow[]
  supplyTargets?: { id: number; supplyRowId: number; data: ManagementValues }[]
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

function parseSharedRecruitment(value: unknown): { id: number; householdCount: number | null } {
  if (!isRecord(value) || typeof value.id !== 'number' || !Number.isSafeInteger(value.id) || value.id <= 0
    || !isNullableNumber(value.householdCount) || (value.householdCount != null && (!Number.isSafeInteger(value.householdCount) || value.householdCount < 0))) {
    throw new Error('공통 모집수 응답이 올바르지 않습니다.')
  }
  return { id: value.id, householdCount: value.householdCount }
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
        || (item.housingComplexName !== null && typeof item.housingComplexName !== 'string')
        || typeof item.sourceComplexName !== 'string' || typeof item.sourceHousingTypeName !== 'string') {
        throw new Error('공급정보 응답이 올바르지 않습니다.')
      }
      supplyRows.push({
        ...(item.sharedRecruitment != null ? { sharedRecruitment: parseSharedRecruitment(item.sharedRecruitment) } : {}),
        id: item.id, data: item.data, modified: item.modified, housingComplexId: item.housingComplexId,
        housingTypeId: item.housingTypeId, housingComplexName: item.housingComplexName,
        sourceComplexName: item.sourceComplexName, sourceHousingTypeName: item.sourceHousingTypeName,
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
  const supplyTargets = value.supplyTargets === undefined ? [] : value.supplyTargets
  if (!Array.isArray(supplyTargets) || !supplyTargets.every(item => isRecord(item)
    && Number.isSafeInteger(item.id) && Number.isSafeInteger(item.supplyRowId) && isManagementValues(item.data))) {
    throw new Error('공급대상 응답이 올바르지 않습니다.')
  }
  const attachments = value.attachments === undefined ? [] : value.attachments
  if (!Array.isArray(attachments) || !attachments.every(item => isRecord(item)
    && isCount(item.attachmentId) && item.attachmentId > 0 && typeof item.fileName === 'string'
    && typeof item.fileType === 'string' && typeof item.fileUrl === 'string')) {
    throw new Error('공고 첨부파일 응답이 올바르지 않습니다.')
  }

  return {
    summary: value.summary,
    data: value.data,
    sourceIdentifier: value.sourceIdentifier,
    housingTypes,
    announcements,
    supplyRows,
    ...(value.attachments === undefined ? {} : { attachments: attachments.map(item => ({ attachmentId: Number(item.attachmentId), fileName: String(item.fileName),
      fileType: String(item.fileType), fileUrl: String(item.fileUrl) })) }),
    ...(value.supplyTargets === undefined ? {} : {
      supplyTargets: supplyTargets.map(item => ({ id: Number(item.id), supplyRowId: Number(item.supplyRowId), data: item.data as ManagementValues })),
    }),
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
    && (value.complex === undefined || value.complex === null || isManagementComplexSummary(value.complex))
    && (value.announcement === undefined || value.announcement === null || isManagementAnnouncementSummary(value.announcement))
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function isManagementComplexSummary(value: unknown): value is ManagementComplexSummary {
  return isRecord(value) && typeof value.sourceIdentifier === 'string' && isNullableString(value.completionDate)
    && isCount(value.totalHouseholdCount) && isCount(value.totalParkingCount)
    && isNullableString(value.heatingType) && isNullableString(value.buildingType) && isNullableString(value.corridorType)
    && (value.hasElevator === null || typeof value.hasElevator === 'boolean')
    && (value.moveOutCountLastYear === null || isCount(value.moveOutCountLastYear))
    && (value.verificationStatus === undefined || isVerificationStatus(value.verificationStatus))
    && (value.reviewedFieldCount === undefined || isCount(value.reviewedFieldCount) && value.reviewedFieldCount <= 6)
}

function isManagementAnnouncementSummary(value: unknown): value is ManagementAnnouncementSummary {
  return isRecord(value) && typeof value.sourceIdentifier === 'string' && typeof value.originalUrl === 'string'
    && typeof value.recruitmentType === 'string' && typeof value.postedDate === 'string'
    && typeof value.applicationStartDate === 'string' && typeof value.applicationEndDate === 'string'
    && typeof value.winnerAnnouncementDate === 'string'
    && (value.publicationType === undefined || typeof value.publicationType === 'string')
}

function isJsonValue(value: unknown): value is JsonValue {
  return value === null || typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number'
    || (Array.isArray(value) && value.every(isJsonValue)) || (isRecord(value) && Object.values(value).every(isJsonValue))
}

function isManagementValues(value: unknown): value is ManagementValues {
  return isRecord(value) && Object.values(value).every(isJsonValue)
}
