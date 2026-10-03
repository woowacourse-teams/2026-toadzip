import { afterEach, describe, expect, it, vi } from 'vitest'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from '../testing/minimalPublicHousingSnapshot.ts'
import {
  createLocalPublicHousingMockLoader,
  LOCAL_PUBLIC_HOUSING_SNAPSHOT_ENDPOINT,
  LocalPublicHousingMockLoadError,
} from './defaultPublicHousingRepository.ts'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.resetModules()
})

describe('실제 local mock repository 선택', () => {
  it.each([
    { development: true, flag: 'true', mode: 'development', enabled: true },
    { development: true, flag: 'true', mode: 'test', enabled: false },
    { development: false, flag: 'true', mode: 'production', enabled: false },
    { development: true, flag: undefined, mode: 'development', enabled: false },
    { development: true, flag: 'false', mode: 'development', enabled: false },
    { development: true, flag: 'TRUE', mode: 'development', enabled: false },
    { development: true, flag: ' true ', mode: 'development', enabled: false },
  ])('$development/$mode/$flag 설정으로 제품 repository를 선택한다', async ({ development, flag, mode, enabled }) => {
    vi.stubEnv('DEV', development)
    vi.stubEnv('MODE', mode)
    vi.stubEnv('VITE_PUBLIC_HOUSING_LOCAL_MOCK', flag)
    vi.resetModules()
    const { localPublicHousingMockEnabled, defaultPublicHousingRepository } =
      await import('./defaultPublicHousingRepository.ts')
    const { publicHousingRepository } = await import('./publicHousingRepository.ts')

    expect(localPublicHousingMockEnabled).toBe(enabled)
    if (enabled) {
      expect(defaultPublicHousingRepository).not.toBe(publicHousingRepository)
    } else {
      expect(defaultPublicHousingRepository).toBe(publicHousingRepository)
    }
  })
})

describe('createLocalPublicHousingMockLoader', () => {
  it('성공한 로컬 snapshot을 no-store로 한 번만 불러온다', async () => {
    const snapshot = localSnapshot()
    const fetcher = vi.fn().mockResolvedValue(new Response(
      JSON.stringify(snapshot),
      { status: 200 },
    ))
    const load = createLocalPublicHousingMockLoader(fetcher)

    await expect(load()).resolves.toEqual(snapshot)
    await expect(load()).resolves.toEqual(snapshot)

    expect(fetcher).toHaveBeenCalledOnce()
    expect(fetcher).toHaveBeenCalledWith(
      LOCAL_PUBLIC_HOUSING_SNAPSHOT_ENDPOINT,
      {
        cache: 'no-store',
        headers: { Accept: 'application/json' },
      },
    )
  })

  it('실패는 캐시하지 않아 다음 호출에서 로컬 파일을 다시 읽는다', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 404 }))
      .mockResolvedValueOnce(new Response(
        JSON.stringify(localSnapshot()),
        { status: 200 },
      ))
    const load = createLocalPublicHousingMockLoader(fetcher)

    await expect(load()).rejects.toEqual(
      new LocalPublicHousingMockLoadError(404),
    )
    await expect(load()).resolves.toEqual(localSnapshot())
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('DTO 의미 검증 실패도 캐시하지 않아 파일 수정 뒤 다시 읽는다', async () => {
    const invalidSnapshot = {
      ...localSnapshot(),
      announcementDetails: [{
        ...MINIMAL_PUBLIC_HOUSING_SNAPSHOT.announcementDetails[0],
        announcementId: null,
      }],
    }
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(
        JSON.stringify(invalidSnapshot),
        { status: 200 },
      ))
      .mockResolvedValueOnce(new Response(
        JSON.stringify(localSnapshot()),
        { status: 200 },
      ))
    const load = createLocalPublicHousingMockLoader(fetcher)

    await expect(load()).rejects.toThrow(
      '$.data.announcementId',
    )
    await expect(load()).resolves.toEqual(localSnapshot())
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
})

function localSnapshot() {
  return MINIMAL_PUBLIC_HOUSING_SNAPSHOT
}
