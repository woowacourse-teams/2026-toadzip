import { expect, it, vi } from 'vitest'
import { createPrivacyApi, parseConsent, PrivacyError } from './api'
const context = { subject: { kind: 'MEMBER', userId: '7', contextId: null }, consent: { decision: 'UNSET', effectiveStatus: 'UNSET', revision: 0, noticeVersion: null, scopeVersion: null, decidedAt: null, expiresAt: null }, requiredNoticeVersion: 'analytics-v1', requiredScopeVersion: 'scope-1', collectionAllowed: false, checkedAt: '2026-10-09T00:00:00Z', maxAgeSeconds: 60 }
const command = { commandId: crypto.randomUUID(), expectedUserId: '7', expectedRevision: 0, action: 'GRANT' as const, noticeVersion: 'analytics-v1', scopeVersion: 'scope-1', source: 'FIRST_VISIT' as const }
it.each([{ subject: { kind: 'MEMBER', contextId: null } }, { consent: { ...context.consent, revision: -1 } }, { checkedAt: 'invalid' }, { maxAgeSeconds: 120 }, { collectionAllowed: 'true' }])('잘못된 외부 동의 상태를 허용으로 해석하지 않는다: %j', invalid => {
  expect(() => parseConsent({ ...context, ...invalid })).toThrow()
})
it('CSRF 403은 토큰을 갱신하고 같은 명령 본문으로 한 번만 재시도한다', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ token: 'old', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(Response.json({ code: 'FORBIDDEN' }, { status: 403 }))
    .mockResolvedValueOnce(Response.json({ token: 'new', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(Response.json({ receipt: { commandId: command.commandId }, current: context }))
  expect(await createPrivacyApi(fetcher).choose('MEMBER', command)).toEqual(context)
  expect(fetcher.mock.calls[1]?.[1].body).toBe(fetcher.mock.calls[3]?.[1].body)
  expect(fetcher.mock.calls[3]?.[1].headers['X-XSRF-TOKEN']).toBe('new')
  expect(fetcher.mock.calls[3]?.[0]).toMatch(/analytics\/me$/)
})
it('상태·주체 충돌은 코드와 상태를 보존하고 자동 변경하지 않는다', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(Response.json({ code: 'PRIVACY_SUBJECT_CHANGED' }, { status: 409 }))
  await expect(createPrivacyApi(fetcher).choose('MEMBER', command)).rejects.toEqual(expect.objectContaining({ status: 409, code: 'PRIVACY_SUBJECT_CHANGED' }))
  expect(fetcher).toHaveBeenCalledTimes(2)
})
it('임의 CSRF 헤더와 다른 명령의 영수증을 거부한다', async () => {
  const badCsrf = vi.fn().mockResolvedValue(Response.json({ token: 'secret', headerName: 'Authorization' }))
  await expect(createPrivacyApi(badCsrf).choose('MEMBER', command)).rejects.toThrow(); expect(badCsrf).toHaveBeenCalledOnce()
  const wrongReceipt = vi.fn().mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' })).mockResolvedValueOnce(Response.json({ receipt: { commandId: 'other' }, current: context }))
  await expect(createPrivacyApi(wrongReceipt).choose('MEMBER', command)).rejects.toThrow('저장 결과')
})
it('공개 원문은 요청한 버전과 일치해야 하며 미존재 원문은 404다', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ code: 'NOT_FOUND' }, { status: 404 }))
  await expect(createPrivacyApi(fetcher).document('PRIVACY_POLICY', 'missing')).rejects.toBeInstanceOf(PrivacyError)
  expect(fetcher.mock.calls[0]?.[1].signal).toBeInstanceOf(AbortSignal)
})
