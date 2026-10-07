import { describe, expect, it, vi } from 'vitest'
import { createIntegratedSearchRepository } from './integratedSearchRepository.ts'

describe('integratedSearchRepository', () => {
  it('기존 지역 목록에 없는 읍면동은 위치 검색으로 보완한다', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: {
        announcements: [], complexes: [], failures: [], hasNext: false,
        page: 0, query: '역삼동', regions: [], size: 5, totalCount: 0,
      } })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: {
        items: [{ ...item('REGION', 'local-region:1168010100'),
          title: '서울 강남구 역삼동', subtitle: '행정구역', latitude: 37.5, longitude: 127.03, regionCode: null }],
        page: 0, size: 5, hasNext: false, totalCount: 1,
      } })))

    const result = await createIntegratedSearchRepository(fetcher).search(
      '역삼동', false, 0, new AbortController().signal, 'REGION',
    )

    expect(result.regions[0]?.title).toBe('서울 강남구 역삼동')
    expect(new URL(fetcher.mock.calls[1][0]).pathname).toBe('/api/v1/locations/search')
  })
  it('장소는 네이버 위치 검색 첫 페이지를 조회하고 더보기를 종료한다', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: {
      items: [{ type: 'PLACE', id: '123', title: '서울역', subtitle: '지하철역 · 서울 중구', latitude: 37.5, longitude: 127 }],
      page: 0, size: 5, hasNext: false, totalCount: 1,
    } })))
    const signal = new AbortController().signal

    const result = await createIntegratedSearchRepository(fetcher).search('서울역', false, 0, signal, 'PLACE')

    expect(result.places?.[0].title).toBe('서울역')
    expect(result.places?.[0].regionCode).toBeNull()
    expect(result.regions).toEqual([])
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(new URL(fetcher.mock.calls[0][0]).searchParams.get('page')).toBe('0')
    expect(result.hasNext).toBe(false)
    expect(result.totalCount).toBe(1)
    expect(fetcher.mock.calls[0][1].signal).toBe(signal)
  })

  it('지역의 마지막 페이지가 비었더라도 내부 지역 결과가 있으면 외부 검색으로 바꾸지 않는다', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: {
      announcements: [], complexes: [], failures: [], hasNext: false,
      page: 2, query: '서울', regions: [], size: 5, totalCount: 10,
    } })))

    await createIntegratedSearchRepository(fetcher).search('서울', false, 2, new AbortController().signal, 'REGION')

    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('외부 검색 실패는 빈 검색 결과로 처리하지 않는다', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('{}', { status: 503 }))

    await expect(createIntegratedSearchRepository(fetcher).search(
      '서울역', false, 0, new AbortController().signal, 'PLACE',
    )).rejects.toThrow('위치 검색을 사용할 수 없습니다.')
  })

  it('유형이 섞이거나 좌표가 손상된 외부 결과는 거부한다', async () => {
    for (const invalid of [{ type: 'REGION', latitude: 37.5 }, { type: 'PLACE', latitude: 91 }]) {
      const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: {
        items: [{ ...invalid, id: '1', title: '장소', subtitle: '주소', longitude: 127 }],
        page: 0, size: 5, hasNext: false, totalCount: 1,
      } })))
      await expect(createIntegratedSearchRepository(fetcher).search(
        '서울역', false, 0, new AbortController().signal, 'PLACE',
      )).rejects.toThrow()
    }
  })
  it('공고 단지 지역으로 분리된 최소 응답을 해석한다', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      data: {
        announcements: [item('ANNOUNCEMENT', '1')],
        complexes: [item('COMPLEX', '2')],
        failures: [],
        hasNext: false,
        page: 0,
        query: '서울',
        regions: [item('REGION', '11')],
        size: 8,
      },
    }), { status: 200 }))

    const result = await createIntegratedSearchRepository(fetcher).search(
      '서울',
      true,
      0,
      new AbortController().signal,
    )

    expect(result.totalCount).toBeNull()
    expect(result.announcements).toHaveLength(1)
    expect(result.complexes).toHaveLength(1)
    expect(result.regions).toHaveLength(1)
    expect(new URL(fetcher.mock.calls[0][0]).searchParams.get('type')).toBeNull()
    expect(new URL(fetcher.mock.calls[0][0]).searchParams.get('size')).toBe('20')
  })

  it('유형별 검색은 5개씩 독립적인 페이지와 취소 신호를 전달한다', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      data: {
        announcements: [], complexes: [], failures: [], hasNext: true,
        page: 1, query: '수원', regions: [item('REGION', '41110')], size: 5, totalCount: 23,
      },
    }), { status: 200 }))
    const controller = new AbortController()

    const result = await createIntegratedSearchRepository(fetcher).search(
      '수원', false, 1, controller.signal, 'REGION',
    )

    expect(Object.fromEntries(new URL(fetcher.mock.calls[0][0]).searchParams)).toEqual({
      query: '수원', preview: 'false', page: '1', size: '5', type: 'REGION',
    })
    expect(fetcher.mock.calls[0][1].signal).toBe(controller.signal)
    expect(result.hasNext).toBe(true)
    expect(result.totalCount).toBe(23)
    expect(result.regions[0].id).toBe('41110')
  })
})

function item(type: 'ANNOUNCEMENT' | 'COMPLEX' | 'REGION', id: string) {
  return {
    applicationStatus: null,
    id,
    latitude: null,
    longitude: null,
    publishedAt: null,
    regionCode: type === 'REGION' ? id : null,
    subtitle: null,
    title: '서울',
    type,
  }
}
