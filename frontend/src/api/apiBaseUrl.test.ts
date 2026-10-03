import { afterEach, describe, expect, it, vi } from 'vitest'
import { getApiBaseUrl } from './apiBaseUrl'

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
    vi.stubEnv('VITE_API_BASE_URL', configured)
    vi.stubEnv('DEV', development)
    expect(getApiBaseUrl()).toBe(expected)
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

  it.each(['admin-auth', 'registration', 'ingest', 'guest-cancellation'] as const)(
    '%s API는 모듈 초기화 후 환경 주소가 바뀌어도 기존 주소를 사용한다', async (api) => {
      vi.stubEnv('VITE_API_BASE_URL', 'https://initial.example.test')
      let request: () => Promise<unknown>
      if (api === 'admin-auth') {
        const { getCurrentAdmin } = await import('../admin/auth/api')
        request = getCurrentAdmin
      } else if (api === 'registration') {
        const { validateAnnouncementImport } = await import('../admin/registration/api')
        request = () => validateAnnouncementImport('{}')
      } else if (api === 'ingest') {
        const { getLhAnnouncementQuality } = await import('../admin/ingest/api')
        request = getLhAnnouncementQuality
      } else {
        const { requestGuestCancellation } = await import('../public-housing/interest/guestCancellationApi')
        request = () => requestGuestCancellation('guest@example.test')
      }
      vi.stubEnv('VITE_API_BASE_URL', 'https://changed.example.test')
      const fetcher = vi.fn().mockRejectedValue(new Error('요청 경로 확인'))
      vi.stubGlobal('fetch', fetcher)

      await expect(request()).rejects.toThrow('요청 경로 확인')
      expect(fetcher.mock.calls[0]?.[0]).toMatch(/^https:\/\/initial\.example\.test\/api\//)
    },
  )

  it.each(['housing', 'map', 'region'] as const)(
    '%s 저장소는 생성 이후 환경 주소가 바뀌어도 생성 시점의 주소를 사용한다', async (repositoryType) => {
      vi.stubEnv('VITE_API_BASE_URL', 'https://initial.example.test')
      const fetcher = vi.fn().mockRejectedValue(new Error('요청 경로 확인'))
      const signal = new AbortController().signal
      let request: () => Promise<unknown>
      if (repositoryType === 'housing') {
        const { createHttpPublicHousingRepository } = await import('../public-housing/api/publicHousingRepository')
        const repository = createHttpPublicHousingRepository({ fetcher })
        request = () => repository.findAnnouncementPage(null, 20, signal)
      } else if (repositoryType === 'map') {
        const { createHttpHousingMapRepository } = await import('../public-housing/api/housingMapRepository')
        const repository = createHttpHousingMapRepository({ fetcher })
        request = () => repository.findMap({
          bounds: { southWestLat: 37, southWestLng: 126, northEastLat: 38, northEastLng: 127 }, zoom: 12,
        }, signal)
      } else {
        const { createHttpPublicHousingRegionRepository } = await import('../public-housing/api/publicHousingRegionRepository')
        const repository = createHttpPublicHousingRegionRepository({ fetcher })
        request = () => repository.search('서울', signal)
      }
      vi.stubEnv('VITE_API_BASE_URL', 'https://changed.example.test')

      await expect(request()).rejects.toThrow()
      expect(fetcher.mock.calls[0]?.[0]).toMatch(/^https:\/\/initial\.example\.test\/api\//)
    },
  )

  it('첨부파일은 요청 시점의 기본 주소를 사용한다', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://initial.example.test')
    const { loadAnnouncementAttachment } = await import('../public-housing/api/announcementAttachments')
    const fetcher = vi.fn().mockRejectedValue(new Error('요청 경로 확인'))
    vi.stubGlobal('fetch', fetcher)
    vi.stubEnv('VITE_API_BASE_URL', 'https://changed.example.test')

    await expect(loadAnnouncementAttachment('1', '2', false, new AbortController().signal)).rejects.toThrow()
    expect(fetcher.mock.calls[0]?.[0]).toBe('https://changed.example.test/api/v1/announcements/1/attachments/2/content?download=false')
  })

  it('회원 조회는 호출 시점의 빈 환경값을 개발 기본값으로 해석한다', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://initial.example.test')
    const { getCurrentUser } = await import('../user/auth/api')
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
    vi.stubGlobal('fetch', fetcher)
    vi.stubEnv('VITE_API_BASE_URL', '')
    vi.stubEnv('DEV', true)

    await expect(getCurrentUser()).resolves.toBeNull()
    expect(fetcher.mock.calls[0]?.[0]).toBe('http://localhost:8080/api/auth/me')
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
