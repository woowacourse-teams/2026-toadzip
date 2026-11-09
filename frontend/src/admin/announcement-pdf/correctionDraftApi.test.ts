import { beforeEach, expect, it, vi } from 'vitest'
import { requestManagementApi } from '../management/api'
import { draftVersion, loadCorrectionDraft } from './correctionDraftApi'
vi.mock('../management/api', () => ({ requestManagementApi: vi.fn() }))

const stored = (payload: Record<string, unknown>) => ({ revision: 4, baseSnapshotToken: 'current', pdfSha256: null, extractionId: null, updatedAt: '2026-11-03T03:00:00Z', stale: false, payload })
const payload = (version: number) => ({ version, text: '{}', review: { rows: [], schedules: [] }, baseRows: [], reason: '이유', sourceUrl: 'https://example.com', sourcePage: '', section: 'supply', pdfName: '' })
beforeEach(() => vi.mocked(requestManagementApi).mockReset())

it('공급행을 LH 행 기준으로 나누던 이전 방식(version 1) 초안은 복원하지 않고 덮어쓸 revision만 알린다', async () => {
  vi.mocked(requestManagementApi).mockResolvedValue(stored(payload(1)))
  await expect(loadCorrectionDraft('29')).resolves.toEqual({ outdated: true, revision: 4 })
})

it('지금 방식의 초안은 그대로 읽고, 형식이 틀리거나 더 새 방식이면 읽지 않는다', async () => {
  vi.mocked(requestManagementApi).mockResolvedValue(stored(payload(draftVersion)))
  await expect(loadCorrectionDraft('29')).resolves.toMatchObject({ revision: 4, payload: { version: 2, reason: '이유' } })
  vi.mocked(requestManagementApi).mockResolvedValue(stored(payload(draftVersion + 1)))
  await expect(loadCorrectionDraft('29')).rejects.toThrow('임시저장 응답이 올바르지 않습니다.')
  vi.mocked(requestManagementApi).mockResolvedValue(stored({ ...payload(draftVersion), review: null }))
  await expect(loadCorrectionDraft('29')).rejects.toThrow('임시저장 응답이 올바르지 않습니다.')
  vi.mocked(requestManagementApi).mockResolvedValue(null)
  await expect(loadCorrectionDraft('29')).resolves.toBeNull()
})
