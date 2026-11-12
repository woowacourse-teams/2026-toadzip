import { fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { ConditionField, FamilyGate } from './ConditionFields'
import { LATER, emptyDraft, setAnswer, setFamilyDetails, type ConditionDraft } from './conditionDraft'
import type { ProfileField } from './conditionsModel'

let latest: ConditionDraft = emptyDraft()

function Harness({ field, initial = emptyDraft(), partial = false }: { field: ProfileField | 'GATE'; initial?: ConditionDraft; partial?: boolean }) {
  const [draft, setDraft] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  latest = draft
  const change = (next: ConditionDraft) => { setDraft(next); latest = next }
  if (field === 'GATE') return <FamilyGate draft={draft} error={error} onChange={change} />
  return <><ConditionField field={field} draft={draft} error={error} partial={partial} today="2026-11-06" onChange={change} onFieldError={(_, message) => setError(message)} />
    <button type="button">바깥</button></>
}

describe('ConditionField', () => {
  it('converts 만원 input to won and shows the reading inside the field', () => {
    render(<Harness field="APPLICANT_ASSETS" />)
    const input = screen.getByRole('textbox', { name: '본인 자산은 모두 얼마인가요?' })
    fireEvent.change(input, { target: { value: '25400' } })
    expect(latest.answers.APPLICANT_ASSETS).toBe(254_000_000)
    expect(screen.getByText('2억 5,400만 원')).toBeInTheDocument()
    expect(screen.getByText('만원')).toBeInTheDocument()
  })

  it('locks the field when "나중에 입력할게요" is checked and unlocks it again', () => {
    render(<Harness field="APPLICANT_ASSETS" />)
    const later = screen.getByRole('checkbox', { name: '나중에 입력할게요' })
    fireEvent.click(later)
    expect(latest.answers.APPLICANT_ASSETS).toBe(LATER)
    expect(screen.getByRole('textbox', { name: '본인 자산은 모두 얼마인가요?' })).toBeDisabled()
    expect(later).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(later)
    expect(latest.answers.APPLICANT_ASSETS).toBeUndefined()
    expect(screen.getByRole('textbox', { name: '본인 자산은 모두 얼마인가요?' })).toBeEnabled()
  })

  it('sets zero with "소득이 없어요" and clears it when unchecked', () => {
    render(<Harness field="APPLICANT_MONTHLY_INCOME" />)
    const none = screen.getByRole('checkbox', { name: '소득이 없어요' })
    fireEvent.click(none)
    expect(latest.answers.APPLICANT_MONTHLY_INCOME).toBe(0)
    fireEvent.click(none)
    expect(latest.answers.APPLICANT_MONTHLY_INCOME).toBeUndefined()
  })

  it('offers "본인 소득과 같아요" for a one-person household', () => {
    const initial = setAnswer(setAnswer(setAnswer(emptyDraft(), 'FAMILY_TYPE', 'UNMARRIED'), 'APPLICANT_MONTHLY_INCOME', 2_300_000), 'REGISTERED_FAMILY_COUNT', 1)
    render(<Harness field="HOUSEHOLD_MONTHLY_INCOME" initial={initial} />)
    fireEvent.click(screen.getByRole('checkbox', { name: '본인 소득과 같아요' }))
    expect(latest.answers.HOUSEHOLD_MONTHLY_INCOME).toBe(2_300_000)
  })

  it('hides the later option in partial mode', () => {
    render(<Harness field="APPLICANT_ASSETS" partial />)
    expect(screen.queryByRole('checkbox', { name: '나중에 입력할게요' })).not.toBeInTheDocument()
  })

  it('formats digits as a date and shows the age', () => {
    render(<Harness field="BIRTH_DATE" />)
    const input = screen.getByRole('textbox', { name: '생년월일을 알려주세요' })
    fireEvent.change(input, { target: { value: '19960302' } })
    expect(input).toHaveValue('1996.03.02')
    expect(latest.answers.BIRTH_DATE).toBe('1996-03-02')
    expect(screen.getByText('만 30세')).toBeInTheDocument()
  })

  it('reports an invalid date and keeps no value', () => {
    render(<Harness field="BIRTH_DATE" />)
    fireEvent.change(screen.getByRole('textbox', { name: '생년월일을 알려주세요' }), { target: { value: '20100101' } })
    expect(latest.answers.BIRTH_DATE).toBeUndefined()
    expect(screen.getByRole('alert')).toHaveTextContent('만 19세 이상부터 이용할 수 있어요.')
  })

  it('opens the district list after picking a province and supports arrow keys', () => {
    render(<Harness field="RESIDENCE_REGION" />)
    fireEvent.click(screen.getByRole('button', { name: '시·도' }))
    fireEvent.click(within(screen.getByRole('listbox', { name: '시·도' })).getByRole('option', { name: '서울특별시' }))
    expect(latest.provinceCode).toBe('11')
    const districts = screen.getByRole('listbox', { name: '시·군·구' })
    const first = within(districts).getAllByRole('option')[0]
    expect(first).toHaveFocus()
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    const second = within(districts).getAllByRole('option')[1]
    expect(second).toHaveFocus()
    fireEvent.click(second)
    expect(latest.answers.RESIDENCE_REGION).toBe('11140')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '시·군·구' })).toHaveTextContent('중구')
  })

  it('closes the list on Escape and on an outside click', () => {
    render(<Harness field="RESIDENCE_REGION" />)
    fireEvent.click(screen.getByRole('button', { name: '시·도' }))
    fireEvent.keyDown(screen.getByRole('listbox'), { key: 'Escape' })
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '시·도' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: '시·도' }))
    fireEvent.pointerDown(screen.getByRole('button', { name: '바깥' }))
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('locks family house ownership when the applicant owns a house', () => {
    const initial = setFamilyDetails(setAnswer(setAnswer(emptyDraft(), 'FAMILY_TYPE', 'UNMARRIED'), 'APPLICANT_OWNS_HOUSE', true), 'PROVIDED')
    render(<Harness field="FAMILY_OWNS_HOUSE" initial={initial} />)
    expect(screen.getByRole('radio', { name: '예' })).toBeDisabled()
    expect(screen.getByRole('radio', { name: '예' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText("본인 명의 주택이 있어서 '예'로 정했어요.")).toBeInTheDocument()
  })

  it('counts people with a stepper that stops at the minimum', () => {
    render(<Harness field="REGISTERED_FAMILY_COUNT" initial={setAnswer(emptyDraft(), 'FAMILY_TYPE', 'UNMARRIED')} />)
    expect(screen.getByRole('button', { name: '한 명 줄이기' })).toBeDisabled()
    expect(screen.getByRole('spinbutton')).toHaveValue(null)
    expect(screen.getByPlaceholderText('인원')).toBeInTheDocument()
    expect(screen.queryByText('명')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '한 명 늘리기' }))
    expect(latest.answers.REGISTERED_FAMILY_COUNT).toBe(1)
    expect(screen.getByRole('spinbutton')).toHaveValue(1)
    expect(screen.getByText('명')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '한 명 늘리기' }))
    expect(screen.getByRole('spinbutton')).toHaveValue(2)
  })

  it('asks the kind before the date of an event and lets the date wait', () => {
    render(<Harness field="MARRIAGE_EVENT" initial={setAnswer(emptyDraft(), 'FAMILY_TYPE', 'COUPLE_OR_ENGAGED')} />)
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('radio', { name: '신고 예정이에요' }))
    fireEvent.change(screen.getByRole('textbox', { name: '신고 예정일' }), { target: { value: '20270301' } })
    expect(latest.answers.MARRIAGE_EVENT).toEqual({ kind: 'PLANNED', date: '2027-03-01' })
    fireEvent.click(screen.getByRole('checkbox', { name: '날짜는 나중에 입력할게요' }))
    expect(latest.answers.MARRIAGE_EVENT).toBe(LATER)
  })

  it('picks a family type from cards', () => {
    render(<Harness field="FAMILY_TYPE" />)
    fireEvent.click(screen.getByRole('radio', { name: /부부·예비부부/ }))
    expect(latest.answers.FAMILY_TYPE).toBe('COUPLE_OR_ENGAGED')
    expect(latest.answers.REGISTERED_FAMILY_COUNT).toBeUndefined()
  })
})

describe('FamilyGate', () => {
  it('chooses whether to enter family details', () => {
    render(<Harness field="GATE" initial={setAnswer(emptyDraft(), 'FAMILY_TYPE', 'UNMARRIED')} />)
    fireEvent.click(screen.getByRole('radio', { name: '다음에 할게요' }))
    expect(latest.familyDetails).toBe('SKIPPED')
    expect(screen.getByText('마이페이지의 내 조건 관리나 공고의 △ 항목에서 언제든 추가할 수 있어요.')).toBeInTheDocument()
  })
})
