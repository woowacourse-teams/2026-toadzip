import type { HousingAnnouncementCardData } from '../components/HousingAnnouncementCard.tsx'
import type { AnnouncementListItem } from '../model/publicHousing.ts'

export function toHousingAnnouncementCardData(
  announcement: AnnouncementListItem,
): HousingAnnouncementCardData {
  return {
    agencyLabel: announcement.agency?.code
      ?? announcement.agency?.name
      ?? null,
    announcementId: announcement.announcementId,
    applicationEndAt: announcement.applicationEndAt,
    applicationStartAt: announcement.applicationStartAt,
    applicationStatus: announcement.applicationStatus,
    dDay: announcement.dDay,
    recruitmentTypeLabel: recruitmentTypeLabel(
      announcement.recruitmentType,
    ),
    regionNames: announcement.regionNames,
    rentalTypeLabel: rentalTypeLabel(announcement.rentalType),
    supplyHouseholdCount: announcement.supplyHouseholdCount,
    title: announcement.title,
    viewCount: announcement.viewCount,
  }
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
  })
}

function recruitmentTypeLabel(value: string | null) {
  return codeLabel(value, {
    ETC: '기타 모집',
    NEW: '신규 입주자',
    WAITLIST: '예비입주자',
  })
}

function codeLabel(
  value: string | null,
  labels: Readonly<Record<string, string>>,
) {
  if (value === null) {
    return null
  }
  return labels[value] ?? null
}
