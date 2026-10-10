import { afterEach, expect, it, vi } from 'vitest'
import { consentStore } from '../../privacy/consentStore'
import { logoutUser, socialLoginUrl } from './api'

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs() })

it.each(['kakao', 'google'] as const)('%s 로그인 URL은 버전 없이도 만들고 전달받은 구버전을 바꾸지 않는다', provider => {
  vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com')
  const base = `https://api.example.com/api/auth/oauth2/authorization/${provider}`
  expect(socialLoginUrl(provider)).toBe(base)
  expect(socialLoginUrl(provider, 'privacy-2026-10-09-v1')).toBe(`${base}?policyVersion=privacy-2026-10-09-v1`)
})

it.each(['success', 'csrf-http', 'csrf-body', 'logout-http', 'network'] as const)('로그아웃 %s 경로도 인증 전환 완료를 전파하고 다시 확인한다', async outcome => {
  const begin = vi.spyOn(consentStore, 'beginAuthTransition').mockReturnValue('00000000-0000-4000-8000-000000000001')
  const finish = vi.spyOn(consentStore, 'finishAuthTransition').mockResolvedValue()
  const fetcher = vi.fn<typeof fetch>()
  if (outcome === 'network') fetcher.mockRejectedValueOnce(new Error('offline'))
  else if (outcome === 'csrf-http') fetcher.mockResolvedValueOnce(new Response(null, { status: 500 }))
  else if (outcome === 'csrf-body') fetcher.mockResolvedValueOnce(Response.json({}))
  else fetcher.mockResolvedValueOnce(Response.json({ token: 'csrf', headerName: 'X-XSRF-TOKEN' }))
    .mockResolvedValueOnce(new Response(null, { status: outcome === 'logout-http' ? 500 : 204 }))
  vi.stubGlobal('fetch', fetcher)

  if (outcome === 'success') await expect(logoutUser()).resolves.toBeUndefined()
  else await expect(logoutUser()).rejects.toThrow()

  expect(begin).toHaveBeenCalledWith('logout')
  expect(begin.mock.invocationCallOrder[0]).toBeLessThan(fetcher.mock.invocationCallOrder[0]!)
  expect(finish).toHaveBeenCalledExactlyOnceWith('00000000-0000-4000-8000-000000000001')
  expect(fetcher.mock.calls[0]?.[1]?.signal).toBeInstanceOf(AbortSignal)
})
