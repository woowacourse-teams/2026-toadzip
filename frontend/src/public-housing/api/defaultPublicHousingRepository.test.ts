import { describe, expect, it, vi } from 'vitest'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from '../testing/minimalPublicHousingSnapshot.ts'
import {
  createLocalPublicHousingMockLoader,
  LOCAL_PUBLIC_HOUSING_SNAPSHOT_ENDPOINT,
  LocalPublicHousingMockLoadError,
  shouldEnableLocalPublicHousingMock,
} from './defaultPublicHousingRepository.ts'

describe('shouldEnableLocalPublicHousingMock', () => {
  it('개발 serve 조건에서만 명시적인 true flag를 허용한다', () => {
    expect(shouldEnableLocalPublicHousingMock({
      development: true,
      flag: 'true',
      mode: 'development',
    })).toBe(true)
    expect(shouldEnableLocalPublicHousingMock({
      development: true,
      flag: 'true',
      mode: 'test',
    })).toBe(false)
    expect(shouldEnableLocalPublicHousingMock({
      development: false,
      flag: 'true',
      mode: 'production',
    })).toBe(false)
    expect(shouldEnableLocalPublicHousingMock({
      development: true,
      flag: undefined,
      mode: 'development',
    })).toBe(false)
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
