export const verificationFields = ['NAME', 'ADDRESS', 'LOCATION', 'AGENCY', 'RENTAL_TYPE', 'HOUSEHOLD_COUNT'] as const
export type VerificationField = typeof verificationFields[number]
export const verificationStatuses = ['UNREVIEWED', 'VERIFIED', 'ON_HOLD', 'STALE'] as const
export type VerificationStatus = typeof verificationStatuses[number]
export type ReviewOutcome = 'VERIFIED' | 'ON_HOLD'
export type VerificationValues = {
  NAME: string
  ADDRESS: { roadAddress: string; pnu: string; legalDongCode: string; provinceCode: string; cityCountyDistrictCode: string }
  LOCATION: [number, number]
  AGENCY: string
  RENTAL_TYPE: string
  HOUSEHOLD_COUNT: number
}
export type VerificationSource = {
  sourceIdentifier: string
  name: string | null
  roadAddress: string | null
  pnu: string | null
  provider: string | null
  rentalType: string | null
  householdCount: number | null
  housingType: string | null
  exclusiveArea: number | null
  collectedAt: string | null
}
export type ComplexReview = {
  id: number
  outcome: ReviewOutcome
  fields: VerificationField[]
  checkedValues: Partial<VerificationValues>
  evidenceUrl: string | null
  evidenceNote: string
  actor: string
  reviewedAt: string
}
export type ComplexVerification = {
  version: number
  snapshotToken: string
  status: VerificationStatus
  currentValues: VerificationValues
  sources: VerificationSource[]
  latestReview: ComplexReview | null
  history: ComplexReview[]
}
export type ComplexReviewInput = {
  version: number
  reviewId: number
  snapshotToken: string
  fields: VerificationField[]
  outcome: ReviewOutcome
  evidenceUrl: string
  evidenceNote: string
}

export function isVerificationStatus(value: unknown): value is VerificationStatus {
  return verificationStatuses.some(status => status === value)
}
export function parseComplexVerification(value: unknown): ComplexVerification {
  if (!isRecord(value) || !count(value.version) || typeof value.snapshotToken !== 'string'
    || !/^[a-f0-9]{64}$/.test(value.snapshotToken) || !isVerificationStatus(value.status)
    || !isValues(value.currentValues) || !Array.isArray(value.sources) || !value.sources.every(isSource)
    || !(value.latestReview === null || isReview(value.latestReview))
    || !Array.isArray(value.history) || !value.history.every(isReview)) {
    throw new Error('단지 검증 응답이 올바르지 않습니다.')
  }
  return {
    version: value.version, snapshotToken: value.snapshotToken, status: value.status,
    currentValues: value.currentValues, sources: value.sources, latestReview: value.latestReview, history: value.history,
  }
}
function count(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
function nullableText(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}
function date(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value))
}
function field(value: unknown): value is VerificationField {
  return verificationFields.some(field => field === value)
}
function isValues(value: unknown): value is VerificationValues {
  return isRecord(value) && verificationFields.every(key => isFieldValue(key, value[key]))
}
function isFieldValue(key: VerificationField, value: unknown): boolean {
  if (key === 'ADDRESS') {
    return isRecord(value) && ['roadAddress', 'pnu', 'legalDongCode', 'provinceCode', 'cityCountyDistrictCode']
      .every(name => typeof value[name] === 'string')
  }
  if (key === 'LOCATION') {
    return Array.isArray(value) && value.length === 2 && value.every(item => typeof item === 'number' && Number.isFinite(item))
      && Math.abs(value[0]) <= 90 && Math.abs(value[1]) <= 180
  }
  if (key === 'HOUSEHOLD_COUNT') return count(value)
  return typeof value === 'string'
}
function isSource(value: unknown): value is VerificationSource {
  return isRecord(value) && typeof value.sourceIdentifier === 'string'
    && ['name', 'roadAddress', 'pnu', 'provider', 'rentalType', 'housingType'].every(key => nullableText(value[key]))
    && (value.householdCount === null || count(value.householdCount))
    && (value.exclusiveArea === null || typeof value.exclusiveArea === 'number' && Number.isFinite(value.exclusiveArea))
    && (value.collectedAt === null || date(value.collectedAt))
}
function isReview(value: unknown): value is ComplexReview {
  if (!isRecord(value) || !count(value.id) || value.id === 0
    || (value.outcome !== 'VERIFIED' && value.outcome !== 'ON_HOLD')
    || !Array.isArray(value.fields) || !value.fields.length || !value.fields.every(field)
    || new Set(value.fields).size !== value.fields.length || !isRecord(value.checkedValues)
    || !nullableText(value.evidenceUrl) || typeof value.evidenceNote !== 'string'
    || typeof value.actor !== 'string' || !date(value.reviewedAt)) return false
  const checked = value.checkedValues
  return Object.keys(checked).length === value.fields.length
    && value.fields.every(key => isFieldValue(key, checked[key]))
}
