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
    expect(visibleItems(3, student)).toEqual(['FAMILY_TYPE', 'IS_HOUSEHOLD_SEPARATED', 'PARENTS_MONTHLY_INCOME', 'REGISTERED_FAMILY_COUNT', 'FAMILY_GATE'])
  })

  it('asks the car value only when the applicant owns a car', () => {
    expect(visibleItems(2, base)).toEqual(['APPLICANT_OWNS_HOUSE', 'APPLICANT_OWNS_CAR', 'APPLICANT_ASSETS', 'APPLICANT_MONTHLY_INCOME'])
    const withCar = setAnswer(base, 'APPLICANT_OWNS_CAR', true)
    expect(visibleItems(2, withCar)).toContain('APPLICANT_CAR_VALUE')
  })

  it('branches the family step by family type and family details', () => {
    const couple = setAnswer(base, 'FAMILY_TYPE', 'COUPLE_OR_ENGAGED')
    expect(visibleItems(3, couple)).toEqual(['FAMILY_TYPE', 'REGISTERED_FAMILY_COUNT', 'MARRIAGE_EVENT', 'HAS_CHILDREN_OR_PREGNANCY', 'IS_DUAL_INCOME', 'FAMILY_GATE'])
    const withChild = setAnswer(couple, 'HAS_CHILDREN_OR_PREGNANCY', true)
    expect(visibleItems(3, withChild)).toContain('YOUNGEST_CHILD')
    const provided = { ...withChild, familyDetails: 'PROVIDED' as const }
    expect(visibleItems(3, provided).slice(-4)).toEqual(['FAMILY_OWNS_HOUSE', 'FAMILY_OWNS_CAR', 'FAMILY_TOTAL_ASSETS', 'HOUSEHOLD_MONTHLY_INCOME'])
    expect(visibleItems(3, setAnswer(base, 'FAMILY_TYPE', 'SINGLE_PARENT'))).toEqual(['FAMILY_TYPE', 'REGISTERED_FAMILY_COUNT', 'CHILDREN_COUNT_INCLUDING_FETUS', 'YOUNGEST_CHILD', 'FAMILY_GATE'])
  })
})

describe('answers', () => {
  it('defaults the family count by family type and the child count to one', () => {
    const single = setAnswer(emptyDraft(), 'FAMILY_TYPE', 'UNMARRIED')
    expect(single.answers.REGISTERED_FAMILY_COUNT).toBe(1)
    const parent = setAnswer(emptyDraft(), 'FAMILY_TYPE', 'SINGLE_PARENT')
    expect(parent.answers.REGISTERED_FAMILY_COUNT).toBe(2)
    expect(parent.answers.CHILDREN_COUNT_INCLUDING_FETUS).toBe(1)
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
    expect(back.answers).toEqual(draft.answers)
    expect(back.familyDetails).toBe('SKIPPED')
    expect(back.provinceCode).toBe('11')
  })
})

describe('missingFields', () => {
  it('counts skipped family fields and later answers as missing', () => {
    const draft = setAnswer(base, 'APPLICANT_ASSETS', LATER)
    expect(missingFields(draft)).toEqual(['APPLICANT_ASSETS', 'FAMILY_OWNS_HOUSE', 'FAMILY_OWNS_CAR', 'FAMILY_TOTAL_ASSETS', 'HOUSEHOLD_MONTHLY_INCOME'])
    expect(applicableFields(draft)).toHaveLength(15)
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
