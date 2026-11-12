import { beforeEach, expect, it, vi } from 'vitest'
import { requestManagementApi } from '../management/api'
import { applyEligibility, createEligibilityDraft, loadEligibility, type Content, type RuleSet } from './eligibilityDraftApi'
vi.mock('../management/api', () => ({ requestManagementApi: vi.fn() }))

const request = vi.mocked(requestManagementApi)
const content = { metadata: { referenceDate: '2026-09-30', reviewStatus: 'REVIEWED', scopeNote: null } } as unknown as Content
const set = (id: number, revision: number, status: string): RuleSet => ({ id, revision, status, content })
const base = '/api/admin/announcements/12/basic-comparison-rule-set'
beforeEach(() => request.mockReset())

it('초안이 없으면 사용자에게 보이는 공개본을 읽는다', async () => {
  request.mockResolvedValueOnce(null).mockResolvedValueOnce(set(3, 1, 'PUBLISHED'))
  await expect(loadEligibility('12')).resolves.toMatchObject({ id: 3, status: 'PUBLISHED' })
  expect(request.mock.calls.map(call => call[0])).toEqual([`${base}/draft`, `${base}/published`])
})

it('공개본을 고치면 초안으로 복사해 검토 완료로 저장한 뒤 공개한다', async () => {
  request.mockResolvedValueOnce(set(4, 0, 'DRAFT')).mockResolvedValueOnce(set(4, 1, 'DRAFT'))
    .mockResolvedValueOnce(undefined).mockResolvedValueOnce(set(4, 1, 'PUBLISHED'))

  await expect(applyEligibility('12', set(3, 1, 'PUBLISHED'), { ...content, metadata: { ...content.metadata, reviewStatus: 'REVIEWING' } }))
    .resolves.toMatchObject({ published: true, ruleSet: { status: 'PUBLISHED' } })
  expect(request.mock.calls.map(call => [call[0], call[1]])).toEqual([[`${base}/draft/copy`, 'POST'], [`${base}/draft`, 'PUT'],
    [`${base}/draft/validation`, 'POST'], [`${base}/draft/publish`, 'POST']])
  expect(request.mock.calls[1][2]).toMatchObject({ expectedDraftId: 4, expectedRevision: 0, content: { metadata: { reviewStatus: 'REVIEWED' } } })
  expect(request.mock.calls[3][2]).toEqual({ draftId: 4, expectedRevision: 1 })
})

it('초안을 고쳤는데 공개가 막히면(공고가 공개 전 등) 저장한 초안을 이유와 함께 돌려준다', async () => {
  request.mockResolvedValueOnce(set(4, 2, 'DRAFT')).mockRejectedValueOnce(new Error('요청이 충돌했습니다.'))

  await expect(applyEligibility('12', set(4, 1, 'DRAFT'), content))
    .resolves.toEqual({ published: false, saved: true, reason: '요청이 충돌했습니다.', ruleSet: set(4, 2, 'DRAFT') })
  expect(request.mock.calls[0][0]).toBe(`${base}/draft`)
})

it('공개본을 복사했더니 화면이 읽은 뒤 다른 곳에서 바뀌어 있으면 덮어쓰지 않고 복사한 초안을 돌려준다', async () => {
  const changed = { ...content, metadata: { ...content.metadata, scopeNote: '다른 관리자가 고침' } } as Content
  request.mockResolvedValueOnce({ ...set(4, 0, 'DRAFT'), content: changed })

  await expect(applyEligibility('12', set(3, 1, 'PUBLISHED'), content)).resolves
    .toMatchObject({ published: false, saved: false, ruleSet: { id: 4, revision: 0, content: changed } })
  expect(request.mock.calls.map(call => call[0])).toEqual([`${base}/draft/copy`])
})

it('공개본을 복사한 뒤 저장이 실패하면 복사한 초안을 돌려줘 화면이 그 초안으로 이어가게 한다', async () => {
  request.mockResolvedValueOnce(set(4, 0, 'DRAFT')).mockRejectedValueOnce(new Error('검증 실패'))

  await expect(applyEligibility('12', set(3, 1, 'PUBLISHED'), content)).resolves
    .toEqual({ published: false, saved: false, reason: '검증 실패', ruleSet: set(4, 0, 'DRAFT') })
})

it('PDF 추출로 다시 만들 때 공개본은 버전 확인에 넘기지 않고, 초안만 그 버전으로 덮어쓴다', async () => {
  request.mockResolvedValue({ ruleSet: set(5, 1, 'PUBLISHED'), published: true, notices: [] })
  await createEligibilityDraft('12', 'a', set(3, 1, 'PUBLISHED'))
  await createEligibilityDraft('12', 'a', set(4, 2, 'DRAFT'))
  expect(request.mock.calls[0][2]).toEqual({ pdfSha256: 'a', expectedRevision: 0 })
  expect(request.mock.calls[1][2]).toEqual({ pdfSha256: 'a', expectedRevision: 2, expectedDraftId: 4 })
  await expect(createEligibilityDraft('12', 'a', null)).resolves.toMatchObject({ published: true })
})
