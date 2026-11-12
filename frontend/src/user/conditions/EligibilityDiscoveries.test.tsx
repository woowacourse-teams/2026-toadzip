import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ConditionsContext, type ConditionsContextValue } from './ConditionsContext'
import { EligibilityDiscoveries } from './EligibilityDiscoveries'
import { ConditionsConflictError, type ConditionsRepository, type ComparisonView } from './conditionsModel'

vi.mock('../../public-housing/api/publicHousingRepository', () => ({ requestPublicHousingJson: vi.fn(async () => ({ title: 'D05 실제 공고 제목' })) }))
vi.mock('../../public-housing/api/publicHousingContract', () => ({ decodeAnnouncementDetailEnvelope: (value: unknown) => value }))
vi.mock('../../public-housing/api/publicHousingMapper', () => ({ toAnnouncementDetail: (value: unknown) => value }))
const discovery = { eventId: 'event-1', rootAnnouncementId: '1', announcementId: '5', optionIds: ['unit-path'], discoveredAt: '2026-11-09T00:00:00Z' }
const view: ComparisonView = { announcementId: '5', availability: 'AVAILABLE', freshness: 'CURRENT', status: 'ELIGIBLE', profileVersion: 2, evaluatedProfileVersion: 2, eligibleOptionIdsInRegion: ['unit-path'], referenceDate: '', scopeNote: null,
  types: [{ key: 'unit-path', label: '일반입주자 신청자격', units: '사천벌리1 26형', status: 'ELIGIBLE', checks: [], guidances: [] }] }
function fixture() {
  const repository = { loadDiscoveries: vi.fn(async () => ({ items: [discovery], nextCursor: 1, hasMore: false })), loadComparison: vi.fn(async () => view), acknowledgeDiscovery: vi.fn(async () => {}) }
  const value: ConditionsContextValue = { mode: 'member', sessionKey: 'user-a', repository: repository as unknown as ConditionsRepository,
    stored: { version: 2, savedAt: null, profile: { familyDetailsState: 'NOT_STARTED', facts: {} } }, processing: { state: 'DONE', profileVersion: 2, requestedVersion: 2, failedAttempts: 0 },
    hasProfile: true, memberDraft: null, revision: 1, lastSave: null, openInput: vi.fn(), restartInput: vi.fn(), openNudge: vi.fn(), clearProfile: vi.fn(), notify: vi.fn() }
  return { repository, value }
}

describe('새 지원 가능 발견 안내', () => {
  it('shows current eligible options and acknowledges only on explicit action', async () => {
    const { repository, value } = fixture()
    let resolve: () => void = () => {}
    repository.acknowledgeDiscovery.mockImplementation(() => new Promise((done) => { resolve = done }))
    render(<ConditionsContext value={value}><EligibilityDiscoveries /></ConditionsContext>)
    expect(await screen.findByRole('link', { name: 'D05 실제 공고 제목' })).toHaveAttribute('href', '/announcements/5')
    expect(screen.getByText('사천벌리1 26형 · 일반입주자 신청자격')).toBeInTheDocument()
    expect(repository.acknowledgeDiscovery).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '확인했어요' }))
    expect(screen.getByRole('link', { name: 'D05 실제 공고 제목' })).toBeInTheDocument()
    await act(async () => resolve())
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(repository.acknowledgeDiscovery).toHaveBeenCalledWith(discovery)
  })

  it('continues after an empty filtered page and removes stale events after a conflict', async () => {
    const { repository, value } = fixture()
    repository.loadDiscoveries.mockResolvedValueOnce({ items: [], nextCursor: 10, hasMore: true })
    repository.acknowledgeDiscovery.mockRejectedValueOnce(new ConditionsConflictError())
    render(<ConditionsContext value={value}><EligibilityDiscoveries /></ConditionsContext>)
    fireEvent.click(await screen.findByRole('button', { name: '다음 안내 보기' }))
    await screen.findByRole('link', { name: 'D05 실제 공고 제목' })
    expect(repository.loadDiscoveries).toHaveBeenLastCalledWith(10, expect.any(AbortSignal))
    repository.loadDiscoveries.mockResolvedValue({ items: [], nextCursor: 0, hasMore: false })
    fireEvent.click(screen.getByRole('button', { name: '확인했어요' }))
    await screen.findByText('정보나 공고가 바뀌어 목록을 다시 확인해요.')
    await waitFor(() => expect(screen.queryByRole('link')).not.toBeInTheDocument())
  })

  it('does not present an event as eligible while the current result is being recalculated', async () => {
    const { repository, value } = fixture()
    repository.loadComparison.mockResolvedValue({ ...view, freshness: 'RECHECKING', types: [], status: null })
    render(<ConditionsContext value={value}><EligibilityDiscoveries /></ConditionsContext>)
    await screen.findByText('이번 조회에 표시할 새 안내가 없어요.')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
