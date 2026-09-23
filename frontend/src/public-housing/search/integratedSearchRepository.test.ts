import { describe, expect, it, vi } from 'vitest'
import { createIntegratedSearchRepository } from './integratedSearchRepository.ts'

describe('integratedSearchRepository', () => {
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
