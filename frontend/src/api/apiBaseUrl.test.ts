import { afterEach, describe, expect, it, vi } from 'vitest'
import { resolveApiBaseUrl } from './apiBaseUrl'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('API 기본 주소 정책', () => {
  it.each([
    [undefined, true, 'http://localhost:8080'],
    ['', true, 'http://localhost:8080'],
    [undefined, false, ''],
    ['', false, ''],
    ['https://api.example.test', true, 'https://api.example.test'],
    ['https://api.example.test', false, 'https://api.example.test'],
  ] as const)('설정 %s, 개발 모드 %s에서 %s를 사용한다', (configured, development, expected) => {
    expect(resolveApiBaseUrl(configured, development)).toBe(expected)
  })

  it.each(['housing', 'map', 'region'] as const)(
    '%s 저장소의 명시적인 빈 주소는 환경 설정보다 우선한다', async (repositoryType) => {
      vi.stubEnv('DEV', true)
      vi.stubEnv('VITE_API_BASE_URL', 'https://configured.example.test')
      const fetcher = vi.fn().mockRejectedValue(new Error('요청 경로 확인'))
      const signal = new AbortController().signal

      if (repositoryType === 'housing') {
        const { createHttpPublicHousingRepository } = await import('../public-housing/api/publicHousingRepository')
        const repository = createHttpPublicHousingRepository({ apiBaseUrl: '', fetcher })
        await expect(repository.findAnnouncementPage(null, 20, signal)).rejects.toThrow()
      } else if (repositoryType === 'map') {
        const { createHttpHousingMapRepository } = await import('../public-housing/api/housingMapRepository')
        const repository = createHttpHousingMapRepository({ apiBaseUrl: '', fetcher })
        await expect(repository.findMap({
          bounds: { southWestLat: 37, southWestLng: 126, northEastLat: 38, northEastLng: 127 },
          zoom: 12,
        }, signal)).rejects.toThrow()
      } else {
        const { createHttpPublicHousingRegionRepository } = await import('../public-housing/api/publicHousingRegionRepository')
        const repository = createHttpPublicHousingRegionRepository({ apiBaseUrl: '', fetcher })
        await expect(repository.search('서울', signal)).rejects.toThrow()
      }

      expect(fetcher).toHaveBeenCalledOnce()
      expect(fetcher.mock.calls[0]?.[0]).toMatch(/^\/api\//)
    },
  )

  it('관리 API는 모듈을 초기화할 때의 기본 주소를 유지한다', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://initial.example.test')
    const { requestManagementApi } = await import('../admin/management/api')
    vi.stubEnv('VITE_API_BASE_URL', 'https://changed.example.test')
    const fetcher = vi.fn().mockResolvedValue(Response.json({ data: [] }))
    vi.stubGlobal('fetch', fetcher)

    await requestManagementApi('/api/admin/announcements')
    expect(fetcher.mock.calls[0]?.[0]).toBe('https://initial.example.test/api/admin/announcements')
  })

  it('관심 저장소는 생성할 때의 기본 주소를 유지한다', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://initial.example.test')
    const fetcher = vi.fn().mockRejectedValue(new Error('요청 경로 확인'))
    const { createNotificationInterestRepository } = await import('../public-housing/interest/notificationInterestRepository')
    const repository = createNotificationInterestRepository(fetcher)
    vi.stubEnv('VITE_API_BASE_URL', 'https://changed.example.test')

    await expect(repository.record({
      eventId: 'event-1', sessionId: 'session-1', eventType: 'CLICKED', source: 'COMPLEX_DETAIL',
      targetType: 'COMPLEX', targetId: '7',
    })).rejects.toThrow()
    expect(fetcher.mock.calls[0]?.[0]).toBe('https://initial.example.test/api/auth/csrf')
  })

  it('통합 검색은 요청할 때 기본 주소를 결정한다', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://initial.example.test')
    const fetcher = vi.fn().mockRejectedValue(new Error('요청 경로 확인'))
    const { createIntegratedSearchRepository } = await import('../public-housing/search/integratedSearchRepository')
    const repository = createIntegratedSearchRepository(fetcher)
    vi.stubEnv('VITE_API_BASE_URL', 'https://changed.example.test')

    await expect(repository.search('서울', true, 0, new AbortController().signal)).rejects.toThrow()
    expect(fetcher.mock.calls[0]?.[0]).toMatch(/^https:\/\/changed\.example\.test\/api\/v1\/search\?/)
  })

  it('소셜 로그인 URL은 호출할 때 기본 주소를 결정한다', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://initial.example.test')
    const { socialLoginUrl } = await import('../user/auth/api')
    vi.stubEnv('VITE_API_BASE_URL', 'https://changed.example.test')

    expect(socialLoginUrl('google')).toBe('https://changed.example.test/api/auth/oauth2/authorization/google')
  })
})
