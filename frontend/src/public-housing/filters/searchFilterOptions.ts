import type {
  AgencyCodeFilter,
  ApplicationStatusFilter,
  RecruitmentTypeFilter,
  RentalTypeFilter,
} from '../api/publicHousingRepository.ts'

export const RENTAL_TYPE_OPTIONS = [
  ['HAPPY_HOUSING', '행복주택'],
  ['NATIONAL_RENTAL', '국민임대'],
  ['PERMANENT_RENTAL', '영구임대'],
  ['PUBLIC_RENTAL_50Y', '50년 공공임대'],
  ['INTEGRATED_PUBLIC_RENTAL', '통합공공임대'],
  ['REDEVELOPMENT_RENTAL', '재개발임대'],
  ['ETC', '기타'],
] as const satisfies readonly (readonly [RentalTypeFilter, string])[]

export const APPLICATION_STATUS_OPTIONS = [
  ['BEFORE_APPLICATION', '공고중'],
  ['APPLYING', '접수중'],
  ['CLOSED', '접수마감'],
] as const satisfies readonly (readonly [ApplicationStatusFilter, string])[]

export const AGENCY_OPTIONS = [
  ['LH', 'LH'],
  ['SH', 'SH'],
  ['GH', 'GH'],
  ['ETC', '기타 기관'],
] as const satisfies readonly (readonly [AgencyCodeFilter, string])[]

export const RECRUITMENT_TYPE_OPTIONS = [
  ['NEW', '신규 모집'],
  ['WAITLIST', '예비입주자 모집'],
  ['ETC', '기타 모집'],
] as const satisfies readonly (readonly [RecruitmentTypeFilter, string])[]

export const ANNOUNCEMENT_STATUS_OPTIONS = APPLICATION_STATUS_OPTIONS.filter(
  ([value]) => value !== 'CLOSED',
)
