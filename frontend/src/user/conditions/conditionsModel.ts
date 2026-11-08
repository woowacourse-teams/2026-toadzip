// 이름은 백엔드 basiccomparison(ProfileField, ValueState, BasicStatus, RuleReading.Status, PathKind, GuidanceKind)과 같게 둔다.

export const PROFILE_FIELDS = [
  'BIRTH_DATE',
  'RESIDENCE_REGION',
  'CURRENT_REGION_RESIDENCE_MONTHS',
  'CURRENT_LEASE_END_DATE',
  'IS_UNIVERSITY_STUDENT',
  'IS_JOB_SEEKER',
  'APPLICANT_OWNS_HOUSE',
  'APPLICANT_OWNS_CAR',
  'APPLICANT_CAR_VALUE',
  'APPLICANT_ASSETS',
  'APPLICANT_MONTHLY_INCOME',
  'FAMILY_TYPE',
  'IS_HOUSEHOLD_SEPARATED',
  'PARENTS_MONTHLY_INCOME',
  'REGISTERED_FAMILY_COUNT',
  'MARRIAGE_EVENT',
  'HAS_CHILDREN_OR_PREGNANCY',
  'CHILDREN_COUNT_INCLUDING_FETUS',
  'YOUNGEST_CHILD',
  'IS_DUAL_INCOME',
  'FAMILY_OWNS_HOUSE',
  'FAMILY_OWNS_CAR',
  'FAMILY_CAR_VALUE',
  'FAMILY_TOTAL_ASSETS',
  'HOUSEHOLD_MONTHLY_INCOME',
] as const

export type ProfileField = typeof PROFILE_FIELDS[number]
export type FamilyType = 'UNMARRIED' | 'SINGLE_PARENT' | 'COUPLE_OR_ENGAGED'
export type DateEventKind = 'REGISTERED' | 'PLANNED' | 'BORN' | 'EXPECTED'
export interface DateEvent { readonly kind: DateEventKind; readonly date: string }
export type FactValue = boolean | number | string | DateEvent
export interface Fact { readonly state: 'PROVIDED' | 'UNKNOWN' | 'NOT_APPLICABLE'; readonly value?: FactValue }
export type FamilyDetailsState = 'NOT_STARTED' | 'PROVIDED' | 'SKIPPED'

export interface ConditionProfile {
  readonly familyDetailsState: FamilyDetailsState
  readonly facts: Partial<Record<ProfileField, Fact>>
}

export interface StoredConditionProfile {
  readonly version: number
  readonly savedAt: string | null
  readonly profile: ConditionProfile
}

export type CheckStatus = 'SATISFIED' | 'NOT_SATISFIED' | 'INPUT_NEEDED' | 'DIRECT_CHECK_REQUIRED'
export type TypeStatus = 'MATCHED' | 'PARTIALLY_COMPARED' | 'INPUT_NEEDED' | 'NOT_MATCHED' | 'NOT_EVALUATED'
/** 지원 가능한 유형을 앞에 둔다. */
export const TYPE_STATUS_ORDER: Record<TypeStatus, number> = { MATCHED: 0, PARTIALLY_COMPARED: 1, INPUT_NEEDED: 2, NOT_MATCHED: 3, NOT_EVALUATED: 4 }
export const CHECK_STATUS_ORDER: Record<CheckStatus, number> = { NOT_SATISFIED: 0, INPUT_NEEDED: 1, DIRECT_CHECK_REQUIRED: 2, SATISFIED: 3 }
export type MissingReason = 'LATER' | 'FAMILY_SKIPPED' | 'UNANSWERED'
export type Coverage = 'GENERAL' | 'COMMON_ONLY'

export interface EvidenceRef {
  readonly page: number
  readonly location: string | null
  readonly fileName: string | null
}

export interface CheckView {
  readonly id: string
  readonly label: string
  readonly status: CheckStatus
  /** 내 값 문구. 입력 필요·직접 확인이면 null */
  readonly mine: string | null
  /** "기준" 뒤 문구. 직접 확인이면 안내 문장 */
  readonly criterion: string
  readonly basis: string | null
  readonly evidence: EvidenceRef | null
  /** 이 항목을 고치거나 채울 때 여는 입력 */
  readonly fields: readonly ProfileField[]
  readonly missingReason: MissingReason | null
}

export type GuidanceKind = 'REQUIRED_QUALIFICATION' | 'REQUIRED_CHECK' | 'OTHER_ROUTE' | 'PRIORITY_OPPORTUNITY' | 'PROCEDURE'

export interface GuidanceView {
  readonly kind: GuidanceKind
  readonly title: string
  readonly body: string
  readonly evidence: EvidenceRef | null
}

export interface TypeView {
  readonly key: string
  readonly label: string
  readonly coverage: Coverage
  readonly status: TypeStatus
  readonly units: string
  readonly checks: readonly CheckView[]
  readonly guidances: readonly GuidanceView[]
}

export interface RequiredQualification {
  readonly title: string
  readonly body: string
  readonly items: readonly string[]
  readonly evidence: EvidenceRef | null
}

export type ComparisonAvailability = 'AVAILABLE' | 'NOT_STRUCTURED' | 'OUT_OF_SCOPE'

export interface ComparisonView {
  readonly announcementId: string
  readonly availability: ComparisonAvailability
  readonly referenceDate: string
  readonly requiredQualification: RequiredQualification | null
  readonly types: readonly TypeView[]
}

export interface ListConditionResult {
  readonly announcementId: string
  readonly availability: ComparisonAvailability
  readonly best: { readonly label: string; readonly coverage: Coverage; readonly status: TypeStatus } | null
  readonly generalMatched: boolean
}

export interface AnnouncementRef {
  readonly announcementId: string
  readonly title: string | null
  readonly rentalType: string | null
  readonly applicationStatus: string | null
  readonly targets: readonly string[]
}

export interface ConditionsRepository {
  loadProfile(signal?: AbortSignal): Promise<StoredConditionProfile>
  saveProfile(request: { readonly expectedVersion: number; readonly profile: ConditionProfile }): Promise<StoredConditionProfile>
  loadComparison(ref: AnnouncementRef, signal?: AbortSignal): Promise<ComparisonView>
  loadListResults(refs: readonly AnnouncementRef[], signal?: AbortSignal): Promise<ReadonlyMap<string, ListConditionResult>>
}

export class ConditionsSessionError extends Error {
  constructor(message = '로그인이 필요해요.') {
    super(message)
    this.name = 'ConditionsSessionError'
  }
}

export class ConditionsConflictError extends Error {
  constructor(message = '다른 곳에서 내 조건이 바뀌었어요.') {
    super(message)
    this.name = 'ConditionsConflictError'
  }
}

export const EMPTY_PROFILE: ConditionProfile = { familyDetailsState: 'NOT_STARTED', facts: {} }
