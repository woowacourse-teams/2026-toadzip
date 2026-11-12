import { afterEach, describe, expect, it } from 'vitest'
import { emptyDraft, setAnswer } from './conditionDraft'
import {
  bannerHiddenThisVisit,
  clearMemberDraft,
  hideBannerThisVisit,
  loadMemberDraft,
  markNudgeShown,
  nudgeShownThisVisit,
  nudgeSnoozed,
  saveLoginHandoff,
  saveMemberDraft,
  snoozeNudge,
  takeLoginHandoff,
} from './conditionStorage'

const draft = setAnswer(emptyDraft(), 'BIRTH_DATE', '1996-03-02')
const at = (iso: string) => new Date(iso)

afterEach(() => { sessionStorage.clear(); localStorage.clear() })

describe('login handoff', () => {
  it('hands a guest draft over once within 30 minutes', () => {
    saveLoginHandoff({ draft, origin: 'detail', announcementId: '12' }, at('2026-11-06T00:00:00Z'))
    expect(takeLoginHandoff(at('2026-11-06T00:29:00Z'))).toEqual({ draft, origin: 'detail', announcementId: '12' })
    expect(takeLoginHandoff(at('2026-11-06T00:29:00Z'))).toBeNull()
  })

  it('ignores an expired or malformed login handoff', () => {
    saveLoginHandoff({ draft, origin: 'list', announcementId: null }, at('2026-11-06T00:00:00Z'))
    expect(takeLoginHandoff(at('2026-11-06T00:31:00Z'))).toBeNull()
    sessionStorage.setItem('toadzip:condition-handoff', '{"draft":{"answers":3},"origin":"detail","expiresAt":9999999999999}')
    expect(takeLoginHandoff()).toBeNull()
    sessionStorage.setItem('toadzip:condition-handoff', 'not json')
    expect(takeLoginHandoff()).toBeNull()
  })
})

describe('member draft', () => {
  it('keeps a member first draft for 7 days per user', () => {
    saveMemberDraft('7', { draft, step: 2, maxStep: 2 }, at('2026-11-06T00:00:00Z'))
    expect(loadMemberDraft('7', at('2026-11-12T23:00:00Z'))).toEqual({ draft, step: 2, maxStep: 2 })
    expect(loadMemberDraft('8', at('2026-11-07T00:00:00Z'))).toBeNull()
    expect(loadMemberDraft('7', at('2026-11-13T01:00:00Z'))).toBeNull()
  })

  it('clears the member draft', () => {
    saveMemberDraft('7', { draft, step: 1, maxStep: 1 })
    clearMemberDraft('7')
    expect(loadMemberDraft('7')).toBeNull()
  })
})

describe('visit flags', () => {
  it('remembers the nudge for this visit and snoozes it for 7 days', () => {
    expect(nudgeShownThisVisit()).toBe(false)
    markNudgeShown()
    expect(nudgeShownThisVisit()).toBe(true)
    snoozeNudge(at('2026-11-06T00:00:00Z'))
    expect(nudgeSnoozed(at('2026-11-12T00:00:00Z'))).toBe(true)
    expect(nudgeSnoozed(at('2026-11-14T00:00:00Z'))).toBe(false)
  })

  it('hides the list banner for this visit', () => {
    expect(bannerHiddenThisVisit()).toBe(false)
    hideBannerThisVisit()
    expect(bannerHiddenThisVisit()).toBe(true)
  })
})
