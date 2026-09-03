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

    expect(result.announcements).toHaveLength(1)
    expect(result.complexes).toHaveLength(1)
    expect(result.regions).toHaveLength(1)
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
