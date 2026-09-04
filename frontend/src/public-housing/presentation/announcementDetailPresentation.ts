import type {
  HousingAnnouncementDetailAttachment,
  HousingAnnouncementDetailData,
  HousingAnnouncementDetailReceptionPlace,
  HousingAnnouncementDetailSchedule,
  HousingAnnouncementDetailSupplyRow,
} from '../components/HousingAnnouncementDetailPanel.tsx'
import type {
  AnnouncementDetail,
  AnnouncementSupplyRow,
} from '../model/publicHousing.ts'

export interface HousingAnnouncementSupplyComplexGroup {
  readonly key: string
  readonly complexId: string | null
  readonly name: string
  readonly address: string | null
  readonly totalHouseholdCount: number | null
  readonly overviewImageUrl: string | null
  readonly supplyHouseholdCount: number | null
  readonly rows: readonly HousingAnnouncementDetailSupplyRow[]
}

export function toHousingAnnouncementDetailData(
  detail: AnnouncementDetail,
): HousingAnnouncementDetailData {
  return {
    agencyCode: detail.agency?.code ?? null,
    agencyName: detail.agency?.name ?? null,
    announcementId: detail.announcementId,
    applicationEndAt: detail.applicationEndAt,
    applicationStartAt: detail.applicationStartAt,
    applicationStatus: detail.applicationStatus,
    applicationStatusLabel: applicationStatusLabel(detail.applicationStatus),
    attachments: detail.attachments.map(toAttachment),
    correctionOrCancellationReason: detail.correctionOrCancellationReason,
    dDay: detail.dDay,
    documentLinkUrl: detail.documentLinkUrl,
    publicationTypeLabel: publicationTypeLabel(detail.publicationType),
    publishedAt: detail.publishedAt,
    receptionPlaces: detail.receptionPlaces.map(toReceptionPlace),
    recruitmentTypeLabel: recruitmentTypeLabel(detail.recruitmentType),
    regionNames: detail.regionNames,
    rentalTypeLabel: rentalTypeLabel(detail.rentalType),
    schedules: detail.schedules.map(toSchedule),
    supplyComplexCount: detail.supplyComplexCount,
    supplyHouseholdCount: detail.supplyHouseholdCount,
    supplyRows: detail.supplyRows.map(toSupplyRow),
    targets: detail.targets,
    title: detail.title,
    viewCount: detail.viewCount,
    winnerAnnouncementAt: detail.winnerAnnouncementAt,
  }
}

export function groupAnnouncementSupplyRows(
  rows: readonly HousingAnnouncementDetailSupplyRow[],
): readonly HousingAnnouncementSupplyComplexGroup[] {
  const groups = new Map<string, HousingAnnouncementDetailSupplyRow[]>()
  rows.forEach((row) => {
    const key = supplyComplexKey(row)
    const current = groups.get(key) ?? []
    groups.set(key, [...current, row])
  })

  return [...groups.entries()].map(([key, groupedRows]) => {
    const first = groupedRows[0]
    if (!first) {
      throw new Error('공급 행 그룹에는 한 개 이상의 행이 필요합니다.')
    }
    const complex = first.complex
    return {
      address: complex?.address ?? null,
      complexId: complex?.complexId ?? null,
      key,
      name: complex?.name
        ?? first.sourceComplexName
        ?? '단지명 정보 확인 중',
      overviewImageUrl: complex?.overviewImageUrl ?? null,
      rows: groupedRows,
      supplyHouseholdCount: sumNullable(
        groupedRows.map((row) => row.totalSupplyHouseholdCount),
      ),
      totalHouseholdCount: complex?.totalHouseholdCount ?? null,
    }
  })
}

function toReceptionPlace(
  place: AnnouncementDetail['receptionPlaces'][number],
): HousingAnnouncementDetailReceptionPlace {
  return {
    address: place.address,
    methodLabel: receptionMethodLabel(place.method),
    name: place.name,
    phoneNumber: place.phoneNumber,
    url: place.url,
  }
}

function toSchedule(
  schedule: AnnouncementDetail['schedules'][number],
): HousingAnnouncementDetailSchedule {
  return {
    endAt: schedule.endAt,
    name: schedule.name,
    scheduleId: schedule.scheduleId,
    startAt: schedule.startAt,
    type: schedule.type,
    typeLabel: scheduleTypeLabel(schedule.type),
  }
}

function toAttachment(
  attachment: AnnouncementDetail['attachments'][number],
): HousingAnnouncementDetailAttachment {
  return {
    attachmentId: attachment.attachmentId,
    fileName: attachment.fileName,
    fileTypeLabel: attachmentTypeLabel(attachment.fileType),
    fileUrl: attachment.fileUrl,
  }
}

function toSupplyRow(
  row: AnnouncementSupplyRow,
): HousingAnnouncementDetailSupplyRow {
  return {
    complex: row.complex,
    housingType: row.housingType,
    occupancyExpectedYearMonth: row.occupancyExpectedYearMonth,
    sourceComplexName: row.sourceComplexName,
    sourceHousingTypeName: row.sourceHousingTypeName,
    supplyRowId: row.supplyRowId,
    supplyTypeLabel: supplyTypeLabel(row.supplyType),
    targets: row.targets,
    totalSupplyHouseholdCount: row.totalSupplyHouseholdCount,
  }
}

function supplyComplexKey(row: HousingAnnouncementDetailSupplyRow) {
  if (row.complex) {
    return `complex:${row.complex.complexId}`
  }
  return `source:${row.sourceComplexName ?? 'unknown'}`
}

function sumNullable(values: readonly (number | null)[]) {
  const known = values.filter((value): value is number => value !== null)
  if (known.length === 0) {
    return null
  }
  return known.reduce((total, value) => total + value, 0)
}

function applicationStatusLabel(value: string | null) {
  return codeLabel(value, {
    APPLYING: '접수중',
    BEFORE_APPLICATION: '접수예정',
    CANCELLED: '공고취소',
    CLOSED: '접수마감',
  }, '접수상태 정보 확인 중')
}

function publicationTypeLabel(value: string | null) {
  return codeLabel(value, {
    CANCELLATION: '취소공고',
    CORRECTION: '정정공고',
    ORIGINAL: '원공고',
  }, '공고유형 정보 확인 중')
}

function rentalTypeLabel(value: string | null) {
  return codeLabel(value, {
    ETC: '기타 공공임대',
    HAPPY_HOUSING: '행복주택',
    INTEGRATED_PUBLIC_RENTAL: '통합공공임대',
    NATIONAL_RENTAL: '국민임대',
    PERMANENT_RENTAL: '영구임대',
    PUBLIC_RENTAL_50Y: '50년 공공임대',
    REDEVELOPMENT_RENTAL: '재개발임대',
  }, '임대유형 정보 확인 중')
}

function recruitmentTypeLabel(value: string | null) {
  return codeLabel(value, {
    ETC: '기타 모집',
    NEW: '신규 입주자 모집',
    WAITLIST: '예비입주자 모집',
  }, '모집유형 정보 확인 중')
}

function scheduleTypeLabel(value: string | null) {
  return codeLabel(value, {
    APPLICATION: '접수 기간',
    CONTRACT: '계약',
    DOCUMENT_SUBMISSION: '서류 제출',
    ETC: '기타 일정',
    MOVE_IN: '입주',
    WINNER_ANNOUNCEMENT: '당첨자 발표',
  }, '일정')
}

function receptionMethodLabel(value: string | null) {
  return codeLabel(value, {
    ETC: '기타',
    MAIL: '우편',
    ONLINE: '온라인',
    VISIT: '방문',
  }, '접수방법 정보 확인 중')
}

function attachmentTypeLabel(value: string | null) {
  return codeLabel(value, {
    ANNOUNCEMENT: '공고문',
    CANCELLATION: '취소공고문',
    CORRECTION: '정정공고문',
    ETC: '기타 첨부파일',
    REFERENCE: '참고자료',
  }, '첨부파일')
}

function supplyTypeLabel(value: string | null) {
  return codeLabel(value, {
    NEW: '신규공급',
    RESUPPLY: '재공급',
  }, '공급구분 정보 확인 중')
}

function codeLabel(
  value: string | null,
  labels: Readonly<Record<string, string>>,
  fallback: string,
) {
  if (value === null) {
    return fallback
  }
  return labels[value] ?? fallback
}
