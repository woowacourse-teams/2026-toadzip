import { afterEach, describe, expect, it, vi } from 'vitest'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('관리자 API 주소', () => {
  it('로컬에서 API 주소가 없으면 로컬 백엔드로 요청한다', async () => {
    const fetchMock = prepareFetch({ development: true })
    const { getCurrentAdmin } = await import('./api.ts')

    await getCurrentAdmin()

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/admin/auth/me',
      expect.objectContaining({ credentials: 'include' }),
    )
  })

  it('프로덕션에서 API 주소가 없으면 같은 주소로 요청한다', async () => {
    const fetchMock = prepareFetch({ development: false })
    const { getCurrentAdmin } = await import('./api.ts')

    await getCurrentAdmin()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/auth/me',
      expect.objectContaining({ credentials: 'include' }),
    )
  })

  it('API 주소를 설정하면 해당 주소로 요청한다', async () => {
    const fetchMock = prepareFetch({
      apiBaseUrl: 'https://api.example.test',
      development: false,
    })
    const { getCurrentAdmin } = await import('./api.ts')

    await getCurrentAdmin()

    expect(fetchMock).toHaveBeenCalledWith(
      'https://api.example.test/api/admin/auth/me',
      expect.objectContaining({ credentials: 'include' }),
    )
  })
})

function prepareFetch({
  apiBaseUrl,
  development,
}: {
  apiBaseUrl?: string
  development: boolean
}) {
  vi.stubEnv('DEV', development)
  vi.stubEnv('VITE_API_BASE_URL', apiBaseUrl)
  vi.resetModules()

  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: vi.fn().mockResolvedValue({
      loginIdentifier: 'admin',
      role: 'ADMIN',
    }),
  } as unknown as Response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
