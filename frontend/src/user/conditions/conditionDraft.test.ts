import { describe, expect, it } from 'vitest'
import {
  LATER,
  applicableFields,
  emptyDraft,
  fromProfile,
  isAnswered,
  missingFields,
  setAnswer,
  stepComplete,
  toProfile,
  todayInSeoul,
  validateDate,
  visibleItems,
  type ConditionDraft,
} from './conditionDraft'

const base: ConditionDraft = {
  ...emptyDraft(),
  answers: {
    BIRTH_DATE: '1996-03-02',
    RESIDENCE_REGION: '11140',
    CURRENT_REGION_RESIDENCE_MONTHS: 24,
    CURRENT_LEASE_END_DATE: '2027-05-01',
    IS_UNIVERSITY_STUDENT: false,
    IS_JOB_SEEKER: false,
    APPLICANT_OWNS_HOUSE: false,
    APPLICANT_OWNS_CAR: false,
    APPLICANT_ASSETS: 30_000_000,
    APPLICANT_MONTHLY_INCOME: 2_300_000,
    FAMILY_TYPE: 'UNMARRIED',
    IS_HOUSEHOLD_SEPARATED: true,
    REGISTERED_FAMILY_COUNT: 1,
  },
  familyDetails: 'SKIPPED',
}

describe('visibleItems', () => {
  it('shows the parents income only for a separated student with no income', () => {
    expect(visibleItems(3, base)).not.toContain('PARENTS_MONTHLY_INCOME')
    const student = { ...base, answers: { ...base.answers, IS_UNIVERSITY_STUDENT: true, APPLICANT_MONTHLY_INCOME: 0 } }
    expect(visibleItems(3, student)).toEqual(['FAMILY_TYPE', 'IS_HOUSEHOLD_SEPARATED', 'PARENTS_MONTHLY_INCOME', 'REGISTERED_FAMILY_COUNT', 'HOUSEHOLD_MONTHLY_INCOME', 'FAMILY_GATE'])
  })

  it('asks the car value only when the applicant owns a car', () => {
    expect(visibleItems(2, base)).toEqual(['APPLICANT_OWNS_HOUSE', 'APPLICANT_OWNS_CAR', 'APPLICANT_ASSETS', 'APPLICANT_MONTHLY_INCOME'])
    const withCar = setAnswer(base, 'APPLICANT_OWNS_CAR', true)
    expect(visibleItems(2, withCar)).toContain('APPLICANT_CAR_VALUE')
  })

  it('branches the family step by family type and family details', () => {
    const couple = setAnswer(base, 'FAMILY_TYPE', 'COUPLE_OR_ENGAGED')
    expect(visibleItems(3, couple)).toEqual(['FAMILY_TYPE', 'REGISTERED_FAMILY_COUNT', 'MARRIAGE_EVENT', 'HAS_CHILDREN_OR_PREGNANCY', 'IS_DUAL_INCOME', 'HOUSEHOLD_MONTHLY_INCOME', 'FAMILY_GATE'])
    const withChild = setAnswer(couple, 'HAS_CHILDREN_OR_PREGNANCY', true)
    expect(visibleItems(3, withChild)).toContain('YOUNGEST_CHILD')
    const provided = { ...withChild, familyDetails: 'PROVIDED' as const }
    expect(visibleItems(3, provided).slice(-3)).toEqual(['FAMILY_OWNS_HOUSE', 'FAMILY_OWNS_CAR', 'FAMILY_TOTAL_ASSETS'])
    expect(visibleItems(3, setAnswer(base, 'FAMILY_TYPE', 'SINGLE_PARENT'))).toEqual(['FAMILY_TYPE', 'REGISTERED_FAMILY_COUNT', 'CHILDREN_COUNT_INCLUDING_FETUS', 'YOUNGEST_CHILD', 'HOUSEHOLD_MONTHLY_INCOME', 'FAMILY_GATE'])
  })
})

describe('answers', () => {
  it('does not assume family or child counts when only family type is selected', () => {
    const single = setAnswer(emptyDraft(), 'FAMILY_TYPE', 'UNMARRIED')
    expect(single.answers.REGISTERED_FAMILY_COUNT).toBeUndefined()
    const parent = setAnswer(emptyDraft(), 'FAMILY_TYPE', 'SINGLE_PARENT')
    expect(parent.answers.REGISTERED_FAMILY_COUNT).toBeUndefined()
    expect(parent.answers.CHILDREN_COUNT_INCLUDING_FETUS).toBeUndefined()
  })

  it('locks family ownership to true when the applicant owns a house or a car', () => {
    const owner = { ...setAnswer(base, 'APPLICANT_OWNS_HOUSE', true), familyDetails: 'PROVIDED' as const }
    const tidy = setAnswer(owner, 'APPLICANT_OWNS_CAR', true)
    expect(tidy.answers.FAMILY_OWNS_HOUSE).toBe(true)
    expect(tidy.answers.FAMILY_OWNS_CAR).toBe(true)
  })

  it('counts later answers as answered and a date event without a date as unanswered', () => {
    expect(isAnswered('APPLICANT_ASSETS', setAnswer(base, 'APPLICANT_ASSETS', LATER))).toBe(true)
    const couple = setAnswer(base, 'FAMILY_TYPE', 'COUPLE_OR_ENGAGED')
    expect(isAnswered('MARRIAGE_EVENT', setAnswer(couple, 'MARRIAGE_EVENT', { kind: 'PLANNED', date: '' }))).toBe(false)
    expect(isAnswered('FAMILY_GATE', base)).toBe(true)
    expect(stepComplete(1, base)).toBe(true)
    expect(stepComplete(3, { ...base, familyDetails: null })).toBe(false)
  })
})

describe('toProfile', () => {
  it('preserves household income, family count and optional family values without a family-type answer', () => {
    const draft: ConditionDraft = { ...emptyDraft(), familyDetails: 'PROVIDED', answers: {
      HOUSEHOLD_MONTHLY_INCOME: 3000000, REGISTERED_FAMILY_COUNT: 2, FAMILY_TOTAL_ASSETS: 100000000,
    } }
    const result = toProfile(draft)
    expect(result.familyDetailsState).toBe('PROVIDED')
    expect(result.facts.HOUSEHOLD_MONTHLY_INCOME).toEqual({ state: 'PROVIDED', value: 3000000 })
    expect(result.facts.REGISTERED_FAMILY_COUNT).toEqual({ state: 'PROVIDED', value: 2 })
    expect(result.facts.FAMILY_TOTAL_ASSETS).toEqual({ state: 'PROVIDED', value: 100000000 })
  })

  it('does not turn UNKNOWN family type into a couple flow', () => {
    const draft = fromProfile({ familyDetailsState: 'NOT_STARTED', facts: { FAMILY_TYPE: { state: 'UNKNOWN' } } })
    expect(visibleItems(3, draft)).not.toContain('MARRIAGE_EVENT')
    expect(isAnswered('FAMILY_TYPE', draft)).toBe(false)
  })

  it('clears the previous region duration when the residence region changes', () => {
    const updated = setAnswer(base, 'RESIDENCE_REGION', '48240')
    expect(toProfile(updated).facts.CURRENT_REGION_RESIDENCE_MONTHS?.state).not.toBe('PROVIDED')
    expect(setAnswer(base, 'RESIDENCE_REGION', '11140').answers.CURRENT_REGION_RESIDENCE_MONTHS).toBe(24)
  })
  it('keeps unconfirmed counts missing when an unrelated amount changes', () => {
    const loaded = fromProfile({ familyDetailsState: 'NOT_STARTED', facts: {
      FAMILY_TYPE: { state: 'PROVIDED', value: 'COUPLE_OR_ENGAGED' },
      REGISTERED_FAMILY_COUNT: { state: 'UNKNOWN' }, CHILDREN_COUNT_INCLUDING_FETUS: { state: 'UNKNOWN' },
      HAS_CHILDREN_OR_PREGNANCY: { state: 'PROVIDED', value: true },
    } })
    const saved = toProfile(setAnswer(loaded, 'HOUSEHOLD_MONTHLY_INCOME', 3000000))
    expect(saved.facts.REGISTERED_FAMILY_COUNT?.state).not.toBe('PROVIDED')
    expect(saved.facts.CHILDREN_COUNT_INCLUDING_FETUS?.state).not.toBe('PROVIDED')
  })

  it('preserves supplied dependent facts while their branch answers are unknown', () => {
    const loaded = fromProfile({ familyDetailsState: 'PROVIDED', facts: {
      FAMILY_TYPE: { state: 'PROVIDED', value: 'COUPLE_OR_ENGAGED' },
      APPLICANT_OWNS_CAR: { state: 'UNKNOWN' }, FAMILY_OWNS_CAR: { state: 'UNKNOWN' },
      APPLICANT_CAR_VALUE: { state: 'PROVIDED', value: 20000000 }, FAMILY_CAR_VALUE: { state: 'PROVIDED', value: 20000000 },
      HAS_CHILDREN_OR_PREGNANCY: { state: 'UNKNOWN' }, CHILDREN_COUNT_INCLUDING_FETUS: { state: 'PROVIDED', value: 2 },
      YOUNGEST_CHILD: { state: 'PROVIDED', value: { kind: 'BORN', date: '2020-01-01' } },
    } })
    const saved = toProfile(setAnswer(loaded, 'HOUSEHOLD_MONTHLY_INCOME', 3000000))
    for (const field of ['APPLICANT_CAR_VALUE', 'FAMILY_CAR_VALUE', 'CHILDREN_COUNT_INCLUDING_FETUS', 'YOUNGEST_CHILD'] as const) {
      expect(saved.facts[field]?.state).toBe('PROVIDED')
    }
  })

  it('drops answers of inactive branches from the saved profile', () => {
    const couple = setAnswer(setAnswer(base, 'FAMILY_TYPE', 'COUPLE_OR_ENGAGED'), 'MARRIAGE_EVENT', { kind: 'REGISTERED', date: '2025-04-01' })
    const switchedToUnmarried = setAnswer(couple, 'FAMILY_TYPE', 'UNMARRIED')
    expect(switchedToUnmarried.answers.MARRIAGE_EVENT).toBeDefined()
    expect(toProfile(switchedToUnmarried).facts.MARRIAGE_EVENT).toBeUndefined()
  })

  it('stores NOT_APPLICABLE for car value without a car and for children fields without children', () => {
    const couple = setAnswer(setAnswer(base, 'FAMILY_TYPE', 'COUPLE_OR_ENGAGED'), 'HAS_CHILDREN_OR_PREGNANCY', false)
    const facts = toProfile({ ...couple, familyDetails: 'PROVIDED', answers: { ...couple.answers, FAMILY_OWNS_CAR: false } }).facts
    expect(facts.APPLICANT_CAR_VALUE).toEqual({ state: 'NOT_APPLICABLE' })
    expect(facts.FAMILY_CAR_VALUE).toEqual({ state: 'NOT_APPLICABLE' })
    expect(facts.CHILDREN_COUNT_INCLUDING_FETUS).toEqual({ state: 'NOT_APPLICABLE' })
    expect(facts.YOUNGEST_CHILD).toEqual({ state: 'NOT_APPLICABLE' })
  })

  it('stores HAS_CHILDREN_OR_PREGNANCY true for a single parent', () => {
    expect(toProfile(setAnswer(base, 'FAMILY_TYPE', 'SINGLE_PARENT')).facts.HAS_CHILDREN_OR_PREGNANCY).toEqual({ state: 'PROVIDED', value: true })
  })

  it('stores later answers as UNKNOWN and omits family facts when family details are skipped', () => {
    const profile = toProfile({ ...setAnswer(base, 'APPLICANT_ASSETS', LATER), answers: { ...setAnswer(base, 'APPLICANT_ASSETS', LATER).answers, FAMILY_OWNS_HOUSE: false } })
    expect(profile.facts.APPLICANT_ASSETS).toEqual({ state: 'UNKNOWN' })
    expect(profile.facts.FAMILY_OWNS_HOUSE).toBeUndefined()
    expect(profile.familyDetailsState).toBe('SKIPPED')
  })

  it('round-trips a stored profile', () => {
    const draft = setAnswer(base, 'APPLICANT_ASSETS', LATER)
    const back = fromProfile(toProfile(draft))
    expect(back.answers).toEqual({ ...draft.answers, APPLICANT_CAR_VALUE: 'NOT_APPLICABLE' })
    expect(back.familyDetails).toBe('SKIPPED')
    expect(back.provinceCode).toBe('11')
  })
})

describe('missingFields', () => {
  it('counts skipped family fields and later answers as missing', () => {
    const draft = setAnswer(base, 'APPLICANT_ASSETS', LATER)
    expect(missingFields(draft)).toEqual(['APPLICANT_ASSETS', 'HOUSEHOLD_MONTHLY_INCOME', 'FAMILY_OWNS_HOUSE', 'FAMILY_OWNS_CAR', 'FAMILY_TOTAL_ASSETS'])
    expect(applicableFields(draft)).toHaveLength(17)
  })
})

describe('validateDate', () => {
  it('rejects a future actual date and an applicant younger than 19', () => {
    expect(validateDate('BIRTH_DATE', '2010-01-01', null, '2026-11-06')).toBe('만 19세 이상부터 이용할 수 있어요.')
    expect(validateDate('BIRTH_DATE', '2026-02-30', null, '2026-11-06')).toBe('올바른 날짜를 입력해 주세요.')
    expect(validateDate('MARRIAGE_EVENT', '2027-03-01', 'REGISTERED', '2026-11-06')).toBe('미래 날짜는 입력할 수 없어요.')
    expect(validateDate('MARRIAGE_EVENT', '2027-03-01', 'PLANNED', '2026-11-06')).toBeNull()
  })

  it('reads today in Seoul time', () => {
    expect(todayInSeoul(new Date('2026-11-05T16:00:00Z'))).toBe('2026-11-06')
  })
})
