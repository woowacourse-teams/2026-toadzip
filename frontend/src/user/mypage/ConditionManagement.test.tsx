import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ConditionsContext, type ConditionsContextValue } from '../conditions/ConditionsContext'
import { LATER, emptyDraft, setAnswer, setFamilyDetails, toProfile } from '../conditions/conditionDraft'
import type { ConditionsRepository } from '../conditions/conditionsModel'
import { ConditionManagement } from './ConditionManagement'

function partialProfile() {
  let draft = setFamilyDetails(setAnswer(emptyDraft(), 'FAMILY_TYPE', 'UNMARRIED'), 'SKIPPED')
  for (const [field, value] of Object.entries({
    BIRTH_DATE: '1996-03-02', RESIDENCE_REGION: '11140', CURRENT_REGION_RESIDENCE_MONTHS: 24, CURRENT_LEASE_END_DATE: '2027-05-01', IS_UNIVERSITY_STUDENT: false, IS_JOB_SEEKER: false, APPLICANT_OWNS_HOUSE: false,
    APPLICANT_OWNS_CAR: false, APPLICANT_ASSETS: LATER, APPLICANT_MONTHLY_INCOME: 2_300_000, IS_HOUSEHOLD_SEPARATED: true, REGISTERED_FAMILY_COUNT: 1,
  })) draft = setAnswer(draft, field as never, value)
  return toProfile(draft)
}

function context(extra: Partial<ConditionsContextValue> = {}): ConditionsContextValue {
  return {
    mode: 'member', hasProfile: true, memberDraft: null, revision: 1, lastSave: null,
    stored: { version: 2, savedAt: '2026-11-05T03:00:00.000Z', profile: partialProfile() }, repository: {} as ConditionsRepository,
    openInput: vi.fn(), restartInput: vi.fn(), openNudge: vi.fn(), clearProfile: vi.fn(), notify: vi.fn(), ...extra,
  }
}

const renderManagement = (value: ConditionsContextValue) => render(<ConditionsContext value={value}><ConditionManagement /></ConditionsContext>)

describe('ConditionManagement', () => {
  it('lists every applicable item in order and marks missing ones in red with an input button', () => {
    const value = context()
    renderManagement(value)
    expect(screen.getByText('공고 상세의 자격 비교에 쓰는 정보예요. 내 계정에 저장돼요. 11월 5일 저장')).toBeInTheDocument()
    expect(screen.getByText('입력한 항목 12 / 17')).toBeInTheDocument()
    const money = screen.getByRole('region', { name: '소득·자산' })
    expect(within(money).getAllByRole('term').map((term) => term.textContent)).toEqual(['본인 주택 소유', '본인 자동차 소유', '본인 자산', '본인 월소득'])
    expect(within(money).getByText('미입력')).toBeInTheDocument()
    fireEvent.click(within(money).getByRole('button', { name: '본인 자산 입력하기' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'partial', fields: ['APPLICANT_ASSETS'], origin: 'mypage' }, {})
    const family = screen.getByRole('region', { name: '가족·세대' })
    expect(within(family).getAllByText('미입력')).toHaveLength(4)
    fireEvent.click(screen.getByRole('button', { name: '미입력 5개 한 번에 입력하기' }))
    expect(value.openInput).toHaveBeenLastCalledWith({ mode: 'partial', fields: ['APPLICANT_ASSETS', 'HOUSEHOLD_MONTHLY_INCOME', 'FAMILY_OWNS_HOUSE', 'FAMILY_OWNS_CAR', 'FAMILY_TOTAL_ASSETS'], origin: 'mypage' }, {})
  })

  it('opens the full input at the step of the edit button', () => {
    const value = context()
    renderManagement(value)
    fireEvent.click(within(screen.getByRole('region', { name: '가족·세대' })).getByRole('button', { name: '가족·세대 수정' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'full', step: 3, origin: 'mypage' }, {})
    fireEvent.click(screen.getByRole('button', { name: '처음부터 다시 입력' }))
    expect(value.openInput).toHaveBeenLastCalledWith({ mode: 'full', step: 1, origin: 'mypage' }, {})
  })

  it('clears all conditions after confirmation', () => {
    const value = context()
    renderManagement(value)
    fireEvent.click(screen.getByRole('button', { name: '입력 정보 모두 지우기' }))
    const confirm = screen.getByRole('alertdialog', { name: '입력 정보를 모두 지울까요?' })
    expect(confirm).toHaveTextContent('공고 상세의 자격 비교도 함께 사라져요. 다시 비교하려면 처음부터 입력해야 해요.')
    fireEvent.click(within(confirm).getByRole('button', { name: '모두 지우기' }))
    expect(value.clearProfile).toHaveBeenCalled()
  })

  it('invites to enter conditions when nothing is saved', () => {
    const value = context({ hasProfile: false, stored: null })
    renderManagement(value)
    expect(screen.getByText('아직 입력한 내 조건이 없어요')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '내 조건 입력하기' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'full', step: 1, origin: 'mypage' }, { resume: true })
  })
})
