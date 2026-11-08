import { FAMILY_DETAIL_FIELDS, FIELD_COPY, type StepNumber } from './conditionFieldCopy'
import { ageAt } from './conditionFormat'
import {
  PROFILE_FIELDS,
  type ConditionProfile,
  type DateEvent,
  type DateEventKind,
  type Fact,
  type FactValue,
  type ProfileField,
} from './conditionsModel'

export const LATER = 'LATER' as const
export const NOT_APPLICABLE = 'NOT_APPLICABLE' as const
export type DraftAnswer = FactValue | typeof LATER | typeof NOT_APPLICABLE
export type DraftItem = ProfileField | 'FAMILY_GATE'
export type { StepNumber }

export interface ConditionDraft {
  /** 입력 중에는 숨겨진 분기의 답도 기억한다(잘못 누른 뒤 되돌려도 유지). 저장할 때만 뺀다. */
  readonly answers: Partial<Record<ProfileField, DraftAnswer>>
  readonly familyDetails: 'PROVIDED' | 'SKIPPED' | null
  /** 시·군·구를 고르기 전 시·도 */
  readonly provinceCode: string | null
}

export function emptyDraft(): ConditionDraft {
  return { answers: {}, familyDetails: null, provinceCode: null }
}

export function isDateEvent(value: unknown): value is DateEvent {
  return typeof value === 'object' && value !== null && 'kind' in value && 'date' in value
}

/** 비교에 쓸 수 있는 실제 값인지 */
export function hasValue(value: DraftAnswer | undefined): value is FactValue {
  if (value === undefined || value === LATER || value === NOT_APPLICABLE || value === '') return false
  if (isDateEvent(value)) return Boolean(value.kind && value.date)
  return true
}

export function visibleItems(step: StepNumber, draft: ConditionDraft): DraftItem[] {
  const a = draft.answers
  if (step === 1) return ['BIRTH_DATE', 'RESIDENCE_REGION', 'CURRENT_REGION_RESIDENCE_MONTHS', 'CURRENT_LEASE_END_DATE', 'IS_UNIVERSITY_STUDENT', 'IS_JOB_SEEKER']
  if (step === 2) {
    const items: DraftItem[] = ['APPLICANT_OWNS_HOUSE', 'APPLICANT_OWNS_CAR']
    if (a.APPLICANT_OWNS_CAR === true) items.push('APPLICANT_CAR_VALUE')
    return [...items, 'APPLICANT_ASSETS', 'APPLICANT_MONTHLY_INCOME']
  }
  const items: DraftItem[] = ['FAMILY_TYPE']
  const type = a.FAMILY_TYPE
  if (!type) return items
  if (type === 'UNMARRIED') {
    items.push('IS_HOUSEHOLD_SEPARATED')
    if (a.IS_UNIVERSITY_STUDENT === true && a.IS_HOUSEHOLD_SEPARATED === true && a.APPLICANT_MONTHLY_INCOME === 0) items.push('PARENTS_MONTHLY_INCOME')
    items.push('REGISTERED_FAMILY_COUNT')
  } else if (type === 'SINGLE_PARENT') {
    items.push('REGISTERED_FAMILY_COUNT', 'CHILDREN_COUNT_INCLUDING_FETUS', 'YOUNGEST_CHILD')
  } else {
    items.push('REGISTERED_FAMILY_COUNT', 'MARRIAGE_EVENT', 'HAS_CHILDREN_OR_PREGNANCY')
    if (a.HAS_CHILDREN_OR_PREGNANCY === true) items.push('CHILDREN_COUNT_INCLUDING_FETUS', 'YOUNGEST_CHILD')
    items.push('IS_DUAL_INCOME')
  }
  items.push('FAMILY_GATE')
  if (draft.familyDetails === 'PROVIDED') {
    items.push('FAMILY_OWNS_HOUSE', 'FAMILY_OWNS_CAR')
    if (a.FAMILY_OWNS_CAR === true) items.push('FAMILY_CAR_VALUE')
    items.push('FAMILY_TOTAL_ASSETS', 'HOUSEHOLD_MONTHLY_INCOME')
  }
  return items
}

function visibleFields(draft: ConditionDraft): ProfileField[] {
  return ([1, 2, 3] as const).flatMap((step) => visibleItems(step, draft)).filter((item): item is ProfileField => item !== 'FAMILY_GATE')
}

export function isAnswered(item: DraftItem, draft: ConditionDraft): boolean {
  if (item === 'FAMILY_GATE') return draft.familyDetails !== null
  const value = draft.answers[item]
  if (value === LATER || value === NOT_APPLICABLE) return true
  return hasValue(value)
}

export function stepComplete(step: StepNumber, draft: ConditionDraft): boolean {
  return visibleItems(step, draft).every((item) => isAnswered(item, draft))
}

/** 값이 바뀐 뒤 따라오는 기본값과 잠금을 맞춘다. */
export function withDefaults(draft: ConditionDraft): ConditionDraft {
  const answers = { ...draft.answers }
  if (answers.FAMILY_TYPE && answers.REGISTERED_FAMILY_COUNT === undefined) answers.REGISTERED_FAMILY_COUNT = answers.FAMILY_TYPE === 'UNMARRIED' ? 1 : 2
  const next = { ...draft, answers, familyDetails: answers.FAMILY_TYPE ? draft.familyDetails : null }
  if (visibleItems(3, next).includes('CHILDREN_COUNT_INCLUDING_FETUS') && answers.CHILDREN_COUNT_INCLUDING_FETUS === undefined) answers.CHILDREN_COUNT_INCLUDING_FETUS = 1
  if (next.familyDetails === 'PROVIDED') {
    if (answers.APPLICANT_OWNS_HOUSE === true) answers.FAMILY_OWNS_HOUSE = true
    if (answers.APPLICANT_OWNS_CAR === true) answers.FAMILY_OWNS_CAR = true
  }
  return next
}

export function setAnswer(draft: ConditionDraft, field: ProfileField, value: DraftAnswer | undefined): ConditionDraft {
  const answers = { ...draft.answers }
  if (value === undefined) delete answers[field]
  else answers[field] = value
  return withDefaults({ ...draft, answers })
}

export function setFamilyDetails(draft: ConditionDraft, familyDetails: 'PROVIDED' | 'SKIPPED'): ConditionDraft {
  return withDefaults({ ...draft, familyDetails })
}

export function draftHasAnswers(draft: ConditionDraft): boolean {
  return Object.keys(draft.answers).length > 0 || draft.provinceCode !== null
}

/** 비교에 쓰는 항목 전체(가족 정보를 입력한다고 가정). 마이페이지와 완료 요약의 분모. */
export function applicableFields(draft: ConditionDraft): ProfileField[] {
  return visibleFields({ ...draft, familyDetails: draft.answers.FAMILY_TYPE ? 'PROVIDED' : draft.familyDetails })
}

/** 아직 값이 없는 항목. 건너뛴 가족 정보와 나중에 입력하기로 한 항목을 포함한다. */
export function missingFields(draft: ConditionDraft): ProfileField[] {
  const familySkipped = draft.familyDetails !== 'PROVIDED'
  return applicableFields(draft).filter((field) =>
    (draft.answers[field] !== NOT_APPLICABLE && !hasValue(draft.answers[field])) || (familySkipped && FAMILY_DETAIL_FIELDS.includes(field)))
}

/** 한 단계에서 적용되는 항목 전체(가족 정보를 입력한다고 가정). 완료 요약과 마이페이지의 표 행. */
export function stepFields(step: StepNumber, draft: ConditionDraft): ProfileField[] {
  const asFull = { ...draft, familyDetails: draft.answers.FAMILY_TYPE ? 'PROVIDED' as const : draft.familyDetails }
  return visibleItems(step, asFull).filter((item): item is ProfileField => item !== 'FAMILY_GATE')
}

export function isFamilyDetailField(field: ProfileField): boolean {
  return FAMILY_DETAIL_FIELDS.includes(field)
}

export function toProfile(draft: ConditionDraft): ConditionProfile {
  const visible = new Set(visibleFields(draft))
  const facts: Partial<Record<ProfileField, Fact>> = {}
  for (const field of PROFILE_FIELDS) {
    if (!visible.has(field)) continue
    const value = draft.answers[field]
    if (value === LATER) facts[field] = { state: 'UNKNOWN' }
    else if (value === NOT_APPLICABLE) facts[field] = { state: 'NOT_APPLICABLE' }
    else if (hasValue(value)) facts[field] = { state: 'PROVIDED', value }
  }
  const a = draft.answers
  if (a.APPLICANT_OWNS_CAR === false) facts.APPLICANT_CAR_VALUE = { state: 'NOT_APPLICABLE' }
  if (draft.familyDetails === 'PROVIDED' && a.FAMILY_OWNS_CAR === false) facts.FAMILY_CAR_VALUE = { state: 'NOT_APPLICABLE' }
  if (a.FAMILY_TYPE === 'SINGLE_PARENT') facts.HAS_CHILDREN_OR_PREGNANCY = { state: 'PROVIDED', value: true }
  if (a.FAMILY_TYPE === 'COUPLE_OR_ENGAGED' && a.HAS_CHILDREN_OR_PREGNANCY === false) {
    facts.CHILDREN_COUNT_INCLUDING_FETUS = { state: 'NOT_APPLICABLE' }
    facts.YOUNGEST_CHILD = { state: 'NOT_APPLICABLE' }
  }
  return { familyDetailsState: a.FAMILY_TYPE ? draft.familyDetails ?? 'NOT_STARTED' : 'NOT_STARTED', facts }
}

export function fromProfile(profile: ConditionProfile): ConditionDraft {
  const answers: Partial<Record<ProfileField, DraftAnswer>> = {}
  for (const field of PROFILE_FIELDS) {
    const fact = profile.facts[field]
    if (!fact) continue
    if (fact.state === 'UNKNOWN') answers[field] = LATER
    if (fact.state === 'NOT_APPLICABLE') answers[field] = NOT_APPLICABLE
    if (fact.state === 'PROVIDED' && fact.value !== undefined) answers[field] = fact.value
  }
  // 한부모는 자녀 있음이 고정이라 입력 화면에는 따로 두지 않는다.
  if (answers.FAMILY_TYPE === 'SINGLE_PARENT') delete answers.HAS_CHILDREN_OR_PREGNANCY
  const region = answers.RESIDENCE_REGION
  return {
    answers,
    familyDetails: profile.familyDetailsState === 'NOT_STARTED' ? null : profile.familyDetailsState,
    provinceCode: typeof region === 'string' ? region.slice(0, 2) : null,
  }
}

function validIso(iso: string) {
  const date = new Date(`${iso}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === iso
}

export function validateDate(field: ProfileField, iso: string, kind: DateEventKind | null, today: string): string | null {
  if (!validIso(iso)) return '올바른 날짜를 입력해 주세요.'
  const actual = field === 'BIRTH_DATE' || kind === 'REGISTERED' || kind === 'BORN'
  if (actual && iso > today) return '미래 날짜는 입력할 수 없어요.'
  if (field === 'BIRTH_DATE' && iso < '1900-01-01') return '생년월일을 다시 확인해 주세요.'
  if (field === 'BIRTH_DATE' && ageAt(iso, today) < 19) return '만 19세 이상부터 이용할 수 있어요.'
  return null
}

export function todayInSeoul(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}

export function fieldLabel(field: ProfileField): string {
  return FIELD_COPY[field].label
}
