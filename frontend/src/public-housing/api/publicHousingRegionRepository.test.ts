import { describe, expect, it, vi } from 'vitest'
import {
  districtRegionOptionsForProvince,
  type PublicHousingRegion,
} from '../model/publicHousingRegion.ts'
import { createHttpPublicHousingRegionRepository } from './publicHousingRegionRepository.ts'

describe('public housing region HTTP repository', () => {
  it('encodes the keyword, forwards the signal, and decodes region items', async () => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse({
      data: {
        items: [
          {
            regionCode: '41',
            provinceName: '경기도',
            districtName: null,
            displayName: '경기도 전체',
          },
          {
            regionCode: '41110',
            provinceName: '경기도',
            districtName: '수원시',
            displayName: '경기도 수원시',
          },
        ],
      },
    }))
    const repository = createHttpPublicHousingRegionRepository({
      apiBaseUrl: 'https://api.example.test',
      fetcher,
    })
    const controller = new AbortController()

    await expect(repository.search('수원 +/구', controller.signal)).resolves.toEqual([
      {
        regionCode: '41',
        provinceName: '경기도',
        districtName: null,
        displayName: '경기도 전체',
      },
      {
        regionCode: '41110',
        provinceName: '경기도',
        districtName: '수원시',
        displayName: '경기도 수원시',
      },
    ])
    expect(fetcher).toHaveBeenCalledWith(
      'https://api.example.test/api/v1/regions?keyword=%EC%88%98%EC%9B%90+%2B%2F%EA%B5%AC',
      {
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      },
    )
  })

  it('reports an HTTP failure separately from a response contract failure', async () => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse({
      code: 'INVALID_REQUEST',
      message: '검색어를 확인해 주세요.',
      traceId: 'region-trace-id',
    }, 400))
    const repository = createHttpPublicHousingRegionRepository({ fetcher })

    await expect(repository.search(
      ' ',
      new AbortController().signal,
    )).rejects.toMatchObject({
      name: 'PublicHousingRegionHttpError',
      message: '검색어를 확인해 주세요.',
      status: 400,
      code: 'INVALID_REQUEST',
      traceId: 'region-trace-id',
    })
  })

  it('keeps the HTTP status when the failure body is not JSON', async () => {
    const repository = createHttpPublicHousingRegionRepository({
      fetcher: vi.fn().mockResolvedValue(new Response('unavailable', {
        status: 503,
      })),
    })

    await expect(repository.search(
      '수원',
      new AbortController().signal,
    )).rejects.toMatchObject({
      name: 'PublicHousingRegionHttpError',
      message: '지역 정보를 불러오지 못했습니다.',
      status: 503,
      code: null,
      traceId: null,
    })
  })

  it('does not trust fields in an HTTP failure body', async () => {
    const repository = createHttpPublicHousingRegionRepository({
      fetcher: vi.fn().mockResolvedValue(jsonResponse({
        code: 7,
        message: { text: 'wrong shape' },
        traceId: false,
      }, 422)),
    })

    await expect(repository.search(
      '수원',
      new AbortController().signal,
    )).rejects.toMatchObject({
      name: 'PublicHousingRegionHttpError',
      message: '지역 정보를 불러오지 못했습니다.',
      status: 422,
      code: null,
      traceId: null,
    })
  })

  it.each([
    ['missing data', {}, '$.data'],
    ['non-object data', { data: null }, '$.data'],
    ['missing items', { data: {} }, '$.data.items'],
    ['non-array items', { data: { items: null } }, '$.data.items'],
    [
      'malformed region code',
      { data: { items: [regionItem({ regionCode: '4111' })] } },
      '$.data.items[0].regionCode',
    ],
    [
      'non-string province name',
      { data: { items: [regionItem({ provinceName: null })] } },
      '$.data.items[0].provinceName',
    ],
    [
      'non-nullable district name',
      { data: { items: [regionItem({ districtName: 7 })] } },
      '$.data.items[0].districtName',
    ],
    [
      'empty display name',
      { data: { items: [regionItem({ displayName: '' })] } },
      '$.data.items[0].displayName',
    ],
  ])('rejects %s at its response path', async (_caseName, body, path) => {
    const repository = createHttpPublicHousingRegionRepository({
      fetcher: vi.fn().mockResolvedValue(jsonResponse(body)),
    })

    await expect(repository.search(
      '수원',
      new AbortController().signal,
    )).rejects.toMatchObject({
      name: 'PublicHousingRegionContractError',
      path,
    })
  })

  it('turns invalid success JSON into a response contract error', async () => {
    const repository = createHttpPublicHousingRegionRepository({
      fetcher: vi.fn().mockResolvedValue(new Response('not-json')),
    })

    await expect(repository.search(
      '수원',
      new AbortController().signal,
    )).rejects.toMatchObject({
      name: 'PublicHousingRegionContractError',
      path: '$ (invalid JSON)',
    })
  })
})

describe('district region options for a province', () => {
  it('removes aggregate and other-province rows without changing response order', () => {
    const regions = [
      region('41', '경기도', null),
      region('41130', '경기도', '성남시'),
      region('11140', '서울특별시', '중구'),
      region('41110', '경기도', '수원시'),
    ]

    expect(districtRegionOptionsForProvince(regions, '41').map(
      ({ regionCode }) => regionCode,
    )).toEqual(['41130', '41110'])
  })

  it('keeps all 25 Seoul autonomous districts in response order', () => {
    const seoulDistrictCodes = [
      '11110', '11140', '11170', '11200', '11215',
      '11230', '11260', '11290', '11305', '11320',
      '11350', '11380', '11410', '11440', '11470',
      '11500', '11530', '11545', '11560', '11590',
      '11620', '11650', '11680', '11710', '11740',
    ]
    const regions = [
      region('11', '서울특별시', null),
      ...seoulDistrictCodes.map((code) =>
        region(code, '서울특별시', `자치구-${code}`),
      ),
    ]

    const options = districtRegionOptionsForProvince(regions, '11')

    expect(options).toHaveLength(25)
    expect(options.map(({ regionCode }) => regionCode)).toEqual([
      '11110', '11140', '11170', '11200', '11215',
      '11230', '11260', '11290', '11305', '11320',
      '11350', '11380', '11410', '11440', '11470',
      '11500', '11530', '11545', '11560', '11590',
      '11620', '11650', '11680', '11710', '11740',
    ])
  })

  it('keeps a parent city and hides only its matching child districts', () => {
    const regions = [
      region('41111', '경기도', '수원시 장안구'),
      region('41110', '경기도', '수원시'),
      region('41112', '경기도', '이름이 다른 구'),
      region('41281', '경기도', '수원시 코드가 다른 구'),
      region('41371', '경기도', '화성시'),
      region('41372', '경기도', '화성시 동부구'),
      region('41461', '경기도', '용인시 처인구'),
    ]

    expect(districtRegionOptionsForProvince(regions, '41').map(
      ({ regionCode }) => regionCode,
    )).toEqual(['41110', '41112', '41281', '41371', '41372', '41461'])
  })
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    status,
  })
}

function regionItem(overrides: Record<string, unknown>) {
  return {
    regionCode: '41110',
    provinceName: '경기도',
    districtName: '수원시',
    displayName: '경기도 수원시',
    ...overrides,
  }
}

function region(
  regionCode: string,
  provinceName: string,
  districtName: string | null,
): PublicHousingRegion {
  return {
    regionCode,
    provinceName,
    districtName,
    displayName: districtName === null
      ? `${provinceName} 전체`
      : `${provinceName} ${districtName}`,
  }
}
