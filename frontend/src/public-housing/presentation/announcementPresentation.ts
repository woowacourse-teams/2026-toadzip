import { rentalTypeLabel } from './rentalTypeLabel.ts'
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

function recruitmentTypeLabel(value: string | null) {
  return codeLabel(value, {
    ETC: '기타 모집',
    NEW: '신규',
    WAITLIST: '예비',
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
