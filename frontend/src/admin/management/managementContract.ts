import { applicationStatuses, attentionReasons } from './listFilterLabels'
import { isVerificationStatus, type VerificationStatus } from './complexVerificationContract'
import { parseSupplySheet, type SupplySheet } from '../supply/supplySheet'

export type ManagementResource = 'complexes' | 'announcements'

export type ManagementSummary = {
  id: number
  name: string
  subtitle: string
  provider: string
  /** 미확정 공고는 공급유형이 아직 없다. */
  rental: string | null
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
  totalHouseholdCount: number | null
  /** 입주 전 단지는 주차대수를 모를 수 있다. */
  totalParkingCount: number | null
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
  /** 미확정 공고는 모집유형·일정이 아직 없다. */
  recruitmentType: string | null
  postedDate: string
  applicationStartDate: string | null
  applicationEndDate: string | null
  winnerAnnouncementDate: string | null
  /** ORIGINAL·CORRECTION·CANCELLATION. 이전 서버 응답에는 없다. */
  publicationType?: string
  /** LH_SOURCE·ADMIN_ENTRY·GH_SOURCE·SH_SOURCE·MYHOME_LEGACY. 이전 서버 응답에는 없다. */
  origin?: string
  /** LH_SOURCE·ANNOUNCEMENT_DOCUMENT. 이전 서버 응답에는 없다. 지금 서버는 supplyConfirmed에서 만든다. */
  supplyOwner?: string
  /** 공고문으로 확인한 공고 공급표가 있는지. false면 공급정보는 기관이 보낸 확인 전 정보다. 이전 서버 응답에는 없다. */
  supplyConfirmed?: boolean
  /** UNCONFIRMED·PUBLISHED. 미확정 공고는 사용자에게 보이지 않는다. 이전 서버 응답에는 없다. */
  applicationStatus?: string | null
  attentionReasons?: string[] | null
  releaseStatus?: string
}

export type JsonValue = string | number | boolean | null | { [key: string]: JsonValue } | JsonValue[]
export type ManagementValues = { [key: string]: JsonValue }

export type ManagementHousingType = {
  id: number
  name: string
  exclusiveArea: number
  householdCount: number | null
  /** 관리자가 휴지통으로 옮긴 주택형. 공개·후보 조회에서는 빠진다. */
  deleted?: boolean
}

export type ManagementDetailData = {
  attachments?: { attachmentId: number; fileName: string; fileType: string; fileUrl: string }[]
  summary: ManagementSummary
  sourceIdentifier: string
  data: ManagementValues
  housingTypes: ManagementHousingType[]
  announcements: ManagementSummary[]
  /** 공고의 공급표. 단지 상세에는 없어 빈 공급표다. */
  supplySheet: SupplySheet
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
        deleted: item.deleted === true,
      })
    }
  }

  const supplySheet = value.supplySheet === undefined || value.supplySheet === null
    ? { housingTypes: [], targets: [], cells: [], quotas: [], leaseTerms: [] } : parseSupplySheet(value.supplySheet)

  const announcements = value.announcements === undefined ? [] : value.announcements
  if (!Array.isArray(announcements) || !announcements.every(isManagementSummary)) {
    throw new Error('연결 공고 응답이 올바르지 않습니다.')
  }
  const schedules = value.schedules === undefined ? [] : value.schedules
  if (!Array.isArray(schedules) || !schedules.every(isManagementValues)) {
    throw new Error('접수 일정 응답이 올바르지 않습니다.')
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
    supplySheet,
    ...(value.attachments === undefined ? {} : { attachments: attachments.map(item => ({ attachmentId: Number(item.attachmentId), fileName: String(item.fileName),
      fileType: String(item.fileType), fileUrl: String(item.fileUrl) })) }),
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
    && typeof value.subtitle === 'string' && typeof value.provider === 'string' && isNullableString(value.rental)
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
    && (value.totalHouseholdCount === null || isCount(value.totalHouseholdCount)) && (value.totalParkingCount === null || isCount(value.totalParkingCount))
    && isNullableString(value.heatingType) && isNullableString(value.buildingType) && isNullableString(value.corridorType)
    && (value.hasElevator === null || typeof value.hasElevator === 'boolean')
    && (value.moveOutCountLastYear === null || isCount(value.moveOutCountLastYear))
    && (value.verificationStatus === undefined || isVerificationStatus(value.verificationStatus))
    && (value.reviewedFieldCount === undefined || isCount(value.reviewedFieldCount) && value.reviewedFieldCount <= 6)
}

function isManagementAnnouncementSummary(value: unknown): value is ManagementAnnouncementSummary {
  return isRecord(value) && typeof value.sourceIdentifier === 'string' && typeof value.originalUrl === 'string'
    && isNullableString(value.recruitmentType) && typeof value.postedDate === 'string'
    && isNullableString(value.applicationStartDate) && isNullableString(value.applicationEndDate)
    && isNullableString(value.winnerAnnouncementDate)
    && (value.publicationType === undefined || typeof value.publicationType === 'string')
    && (value.origin === undefined || typeof value.origin === 'string')
    && (value.supplyOwner === undefined || typeof value.supplyOwner === 'string')
    && (value.supplyConfirmed === undefined || typeof value.supplyConfirmed === 'boolean')
    && (value.releaseStatus === undefined || typeof value.releaseStatus === 'string')
    && (value.applicationStatus == null || typeof value.applicationStatus === 'string' && Object.hasOwn(applicationStatuses, value.applicationStatus))
    && (value.attentionReasons == null || Array.isArray(value.attentionReasons) && value.attentionReasons.every(reason => typeof reason === 'string' && reason !== 'ANY' && Object.hasOwn(attentionReasons, reason)))
}

function isJsonValue(value: unknown): value is JsonValue {
  return value === null || typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number'
    || (Array.isArray(value) && value.every(isJsonValue)) || (isRecord(value) && Object.values(value).every(isJsonValue))
}

function isManagementValues(value: unknown): value is ManagementValues {
  return isRecord(value) && Object.values(value).every(isJsonValue)
}
