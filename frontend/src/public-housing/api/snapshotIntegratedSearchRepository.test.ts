import { afterEach, describe, expect, it, vi } from 'vitest'
import { MINIMAL_PUBLIC_HOUSING_SNAPSHOT } from '../testing/minimalPublicHousingSnapshot.ts'
import { createSnapshotIntegratedSearchRepository } from './snapshotIntegratedSearchRepository.ts'

const SNAPSHOT = MINIMAL_PUBLIC_HOUSING_SNAPSHOT

afterEach(() => {
  vi.restoreAllMocks()
})

describe('snapshot integrated search repository', () => {
  it('snapshot의 지역·단지·공고를 외부 요청 없이 검색한다', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const repository = createSnapshotIntegratedSearchRepository(SNAPSHOT)

    const result = await repository.search('서울', false, 0, new AbortController().signal)

    expect(result).toMatchObject({
      hasNext: true,
      complexes: [{ id: '17', title: '서울가람 행복주택', latitude: 37.5666, longitude: 126.9784 }],
      announcements: [{ id: '201', title: '서울 청년 행복주택 입주자 모집', applicationStatus: 'APPLYING' }],
      failures: [],
    })
    expect(result.regions).toEqual(expect.arrayContaining([expect.objectContaining({
      id: '11140', regionCode: '11140', title: '서울특별시 중구',
    })]))
    expect(result.regions.some((region) => region.regionCode?.length === 10)).toBe(true)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('제목이 없는 데이터는 기존 누락 안내로 표시한다', async () => {
    const repository = createSnapshotIntegratedSearchRepository({
      ...SNAPSHOT,
      complexListItems: [{ ...SNAPSHOT.complexListItems[0], name: null }],
      announcementListItems: [{ ...SNAPSHOT.announcementListItems[0], title: null }],
    })

    const result = await repository.search('서울', false, 0, new AbortController().signal)

    expect(result).toMatchObject({
      complexes: [{ id: '17', title: '공고문 확인' }],
      announcements: [{ id: '201', title: '공고문 확인' }],
    })
  })

  it('유형별 결과를 다섯 개씩 페이지로 나눈다', async () => {
    const item = SNAPSHOT.complexListItems[0]
    const repository = createSnapshotIntegratedSearchRepository({
      ...SNAPSHOT,
      complexListItems: Array.from({ length: 6 }, (_, index) => ({
        ...item, complexId: index + 1,
      })),
    })
    const signal = new AbortController().signal

    const first = await repository.search('행복', false, 0, signal, 'COMPLEX')
    const next = await repository.search('행복', false, 1, signal, 'COMPLEX')

    expect(first.complexes.map((complex) => complex.id)).toEqual(['1', '2', '3', '4', '5'])
    expect(first).toMatchObject({ size: 5, totalCount: 6, hasNext: true, announcements: [], regions: [] })
    expect(next).toMatchObject({ complexes: [{ id: '6' }], page: 1, hasNext: false })
  })

  it('검색 조건이 맞지 않으면 빈 결과를 반환하고 취소를 존중한다', async () => {
    const repository = createSnapshotIntegratedSearchRepository(SNAPSHOT)
    const controller = new AbortController()
    await expect(repository.search('없는 단지', false, 0, controller.signal))
      .resolves.toMatchObject({ totalCount: 0, complexes: [], announcements: [], regions: [] })
    controller.abort()

    await expect(repository.search('서울', false, 0, controller.signal))
      .rejects.toMatchObject({ name: 'AbortError' })
  })
})
