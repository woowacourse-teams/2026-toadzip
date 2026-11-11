/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConditionsContext, type ConditionsContextValue } from '../../user/conditions/ConditionsContext'
import { LATER, emptyDraft, setAnswer, setFamilyDetails, toProfile } from '../../user/conditions/conditionDraft'
import { EMPTY_PROFILE, type ConditionsRepository, type ListConditionResult } from '../../user/conditions/conditionsModel'
import type { AnnouncementListItem } from '../model/publicHousing'
import { ConditionListBanner } from './ConditionListBanner'

afterEach(() => sessionStorage.clear())

const items = [{ announcementId: '1', applicationStatus: 'APPLYING' }, { announcementId: '2', applicationStatus: 'APPLYING' }, { announcementId: '3', applicationStatus: 'CLOSED' }] as AnnouncementListItem[]
const result = (id: string, eligibleInRegion: boolean): [string, ListConditionResult] => [id, { announcementId: id, availability: 'AVAILABLE', freshness: 'CURRENT', missingInputs: [], eligibleInRegion, best: null }]

const fullAnswers = {
  BIRTH_DATE: '1996-03-02', RESIDENCE_REGION: '11140', IS_UNIVERSITY_STUDENT: false, IS_JOB_SEEKER: false, APPLICANT_OWNS_HOUSE: false,
  APPLICANT_OWNS_CAR: false, APPLICANT_ASSETS: 1, APPLICANT_MONTHLY_INCOME: 1, FAMILY_TYPE: 'UNMARRIED', IS_HOUSEHOLD_SEPARATED: true,
  FAMILY_OWNS_HOUSE: false, FAMILY_OWNS_CAR: false, FAMILY_TOTAL_ASSETS: 1, HOUSEHOLD_MONTHLY_INCOME: 1,
} as const
function profile(missingAssets: boolean) {
  let draft = setFamilyDetails(setAnswer(emptyDraft(), 'FAMILY_TYPE', 'UNMARRIED'), 'PROVIDED')
  for (const [field, answer] of Object.entries(fullAnswers)) draft = setAnswer(draft, field as never, answer)
  if (missingAssets) draft = setAnswer(draft, 'APPLICANT_ASSETS', LATER)
  return toProfile(draft)
}

function context(extra: Partial<ConditionsContextValue> = {}): ConditionsContextValue {
  return {
    mode: 'member', hasProfile: true, memberDraft: null, revision: 1, lastSave: null,
    stored: { version: 1, savedAt: null, profile: profile(true) }, repository: {} as ConditionsRepository,
    openInput: vi.fn(), restartInput: vi.fn(), openNudge: vi.fn(), clearProfile: vi.fn(), notify: vi.fn(), ...extra,
  }
}

function renderBanner(value: ConditionsContextValue, results = new Map([result('1', true), result('2', false)]), filtering = false, loading = false) {
  const onToggleFilter = vi.fn()
  render(<ConditionsContext value={value}><ConditionListBanner items={items} results={results} loading={loading} filtering={filtering} onToggleFilter={onToggleFilter} /></ConditionsContext>)
  return onToggleFilter
}

describe('ConditionListBanner', () => {
  it('invites guests without a close button and opens the list nudge', () => {
    const value = context({ mode: 'guest', hasProfile: false, stored: null })
    renderBanner(value, new Map())
    expect(screen.getByText('나에게 맞는 공고 찾기')).toBeInTheDocument()
    expect(screen.getByText('3분 입력하면 조건을 비교해 드려요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '안내 닫기' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '시작' }))
    expect(value.openNudge).toHaveBeenCalledWith('list')
  })

  it('shows the service logo as the guest banner icon', () => {
    const { container } = render(<ConditionsContext value={context({ mode: 'guest', hasProfile: false, stored: null })}>
      <ConditionListBanner items={items} results={new Map()} filtering={false} onToggleFilter={vi.fn()} />
    </ConditionsContext>)
    expect(container.querySelector('img')).toHaveAttribute('src', '/logo-bok-search.svg')
  })

  it('offers to resume a member draft', () => {
    const value = context({ hasProfile: false, stored: { version: 0, savedAt: null, profile: EMPTY_PROFILE }, memberDraft: { step: 2 } })
    renderBanner(value, new Map())
    expect(screen.getByText('작성하던 내 조건이 있어요')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '이어서' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'full', step: 1, origin: 'list' }, { resume: true })
  })

  it('shows the matched count with N건 보기 and toggles the filter', () => {
    const value = context()
    const needed = result('2', false)
    const onToggle = renderBanner(value, new Map([result('1', true), [needed[0], { ...needed[1], missingInputs: ['APPLICANT_ASSETS'] }]]))
    expect(screen.getByText('불러온 공고 중 지원 가능 1건')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '이 공고들에 필요한 미입력 1개 확인' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'partial', fields: ['APPLICANT_ASSETS'], origin: 'list' }, {})
    fireEvent.click(screen.getByRole('button', { name: '1건 보기' }))
    expect(onToggle).toHaveBeenCalled()
  })

  it('switches the action to 전체 보기 while filtering and counts applying announcements', () => {
    renderBanner(context({ stored: { version: 1, savedAt: null, profile: profile(false) } }), undefined, true)
    expect(screen.getByRole('button', { name: '전체 보기' })).toBeInTheDocument()
    expect(screen.getByText('접수 중 공고 1건 · 내 조건 기준')).toBeInTheDocument()
  })

  it('offers to fill missing answers when nothing matches', () => {
    const value = context()
    const needed = result('1', false)
    renderBanner(value, new Map([[needed[0], { ...needed[1], missingInputs: ['APPLICANT_ASSETS'] }]]))
    expect(screen.getByText('불러온 공고 중 지원 가능 0건')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '채우기' }))
    expect(value.openInput).toHaveBeenCalledWith({ mode: 'partial', fields: ['APPLICANT_ASSETS'], origin: 'list' }, {})
  })

  it('does not show a count while comparing the loaded announcements', () => {
    renderBanner(context(), new Map(), false, true)
    expect(screen.getByText('내 조건과 비교하고 있어요')).toBeInTheDocument()
    expect(screen.queryByText(/지원 가능 공고 \d+건/)).not.toBeInTheDocument()
  })

  it('hides the member banner for this visit after closing it', () => {
    const value = context()
    renderBanner(value)
    fireEvent.click(screen.getByRole('button', { name: '안내 닫기' }))
    expect(screen.queryByText('불러온 공고 중 지원 가능 1건')).not.toBeInTheDocument()
    expect(value.notify).toHaveBeenCalledWith('이번 방문 동안 안내를 숨겼어요')
    expect(sessionStorage.getItem('toadzip:condition-banner-hidden')).toBe('true')
  })

  it('keeps a gap below the list header and does not shrink', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/public-housing/announcements/ConditionListBanner.module.css'), 'utf8')
    const banner = css.match(/\.banner\s*\{[^}]*\}/)?.[0] ?? ''
    expect(banner).toMatch(/margin:\s*var\(--ds-space-3\) var\(--ds-space-4\) var\(--ds-space-2\);/)
    expect(banner).toMatch(/flex:\s*0 0 auto;/)
  })
})
