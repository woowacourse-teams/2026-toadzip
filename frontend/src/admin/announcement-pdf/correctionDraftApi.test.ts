import { beforeEach, expect, it, vi } from 'vitest'
import { requestManagementApi } from '../management/api'
import { baseSheetChanges, draftBaseSheet, draftVersion, loadCorrectionDraft } from './correctionDraftApi'
import { daegu } from './fixtures/workspace'
vi.mock('../management/api', () => ({ requestManagementApi: vi.fn() }))

const stored = (payload: Record<string, unknown>) => ({ revision: 4, baseSnapshotToken: 'current', pdfSha256: null, extractionId: null, updatedAt: '2026-11-03T03:00:00Z', stale: false, payload })
const payload = (version: number) => ({ version, text: '{}', review: { rows: [], schedules: [] }, baseSheet: [], reason: '이유', sourceUrl: 'https://example.com', sourcePage: '', section: 'supply', pdfName: '' })
beforeEach(() => vi.mocked(requestManagementApi).mockReset())

// version 1·2는 공급행으로 검토하던 초안, 3은 공급대상 분류가 없던 초안이다.
it.each([1, 2, 3])('이전 방식(version %i) 초안은 복원하지 않고 덮어쓸 revision만 알린다', async version => {
  vi.mocked(requestManagementApi).mockResolvedValue(stored(payload(version)))
  await expect(loadCorrectionDraft('29')).resolves.toEqual({ outdated: true, revision: 4 })
})

it('지금 방식의 초안은 그대로 읽고, 형식이 틀리거나 더 새 방식이면 읽지 않는다', async () => {
  vi.mocked(requestManagementApi).mockResolvedValue(stored(payload(draftVersion)))
  await expect(loadCorrectionDraft('29')).resolves.toMatchObject({ revision: 4, payload: { version: draftVersion, reason: '이유' } })
  vi.mocked(requestManagementApi).mockResolvedValue(stored(payload(draftVersion + 1)))
  await expect(loadCorrectionDraft('29')).rejects.toThrow('임시저장 응답이 올바르지 않습니다.')
  vi.mocked(requestManagementApi).mockResolvedValue(stored({ ...payload(draftVersion), review: null }))
  await expect(loadCorrectionDraft('29')).rejects.toThrow('임시저장 응답이 올바르지 않습니다.')
  vi.mocked(requestManagementApi).mockResolvedValue(null)
  await expect(loadCorrectionDraft('29')).resolves.toBeNull()
})

it('초안을 만든 뒤 지금 공급표에서 사라지거나 생기거나 모집수가 바뀐 주택형을 알린다', () => {
  const { workspace } = daegu()
  const saved = draftBaseSheet(workspace.supply.sheet), sheet = structuredClone(workspace.supply.sheet)
  sheet.quotas[0] = { ...sheet.quotas[0], count: 250 }
  sheet.housingTypes = [sheet.housingTypes[0], { ...sheet.housingTypes[1], key: 'row:900', id: 900, name: '59' }]
  sheet.cells = sheet.cells.map(cell => cell.housingType === 'row:748' ? { ...cell, housingType: 'row:900' } : cell)
  expect(baseSheetChanges(saved, workspace.supply.sheet)).toEqual([])
  expect(baseSheetChanges(saved, sheet)).toEqual(['대구테크노폴리스4 · 공공임대: 모집수 240 → 250', '다른 단지 · 39 주택형이 없어졌습니다.', '다른 단지 · 59 주택형이 새로 생겼습니다.'])
})
