import { describe, expect, it } from 'vitest'
import { PublicHousingContractError } from './publicHousingContract.ts'
import { decodeHousingMapEnvelope } from './housingMapContract.ts'

const AGGREGATE_NODE = {
  type: 'AGGREGATE',
  groupKey: 'basic:41130',
  groupLabel: '성남',
  latitude: 37.4201,
  longitude: 127.1262,
  uniqueComplexCount: 0,
  nextStage: 4,
  expansionZoom: 14,
}

const INDIVIDUAL_NODE = {
  type: 'INDIVIDUAL',
  complexId: 17,
  name: '행복 단지',
  latitude: 37.5,
  longitude: 126.9,
  rentalType: 'HAPPY_HOUSING',
  agency: { code: 'LH', name: '한국토지주택공사' },
  exclusiveAreaMin: 0,
  exclusiveAreaMax: 44.87,
  depositMin: 0,
  depositMax: null,
  monthlyRentMin: 200_000,
  monthlyRentMax: null,
}

describe('지도 v2 응답 계약', () => {
  it('0곳을 포함한 지역 노드를 aggregate 결과로 디코딩한다', () => {
    const result = decodeHousingMapEnvelope(envelope({
      resolvedStage: 3,
      representation: 'AGGREGATE',
      nodes: [AGGREGATE_NODE],
    }))

    expect(result).toEqual({
      resolvedStage: 3,
      representation: 'AGGREGATE',
      policyVersion: '2026-09-02-v1',
      regionDatasetVersion: '2026-07-01',
      nodes: [AGGREGATE_NODE],
    })
  })

  it('개별 노드를 기존 지도 단지와 같은 형태로 변환한다', () => {
    const result = decodeHousingMapEnvelope(envelope({
      resolvedStage: 4,
      representation: 'INDIVIDUAL',
      nodes: [INDIVIDUAL_NODE],
    }))

    expect(result).toMatchObject({
      resolvedStage: 4,
      representation: 'INDIVIDUAL',
      nodes: [
        {
          type: 'INDIVIDUAL',
          complexId: '17',
          name: '행복 단지',
          exclusiveAreaMin: 0,
          depositMin: 0,
          depositMax: null,
        },
      ],
    })
    if (result.representation !== 'INDIVIDUAL') {
      throw new Error('개별 지도 결과여야 합니다.')
    }
    expect(result.nodes[0]?.raw).toEqual(withoutType(INDIVIDUAL_NODE))
  })

  it.each([
    [1, 'AGGREGATE'],
    [2, 'AGGREGATE'],
    [3, 'AGGREGATE'],
    [4, 'INDIVIDUAL'],
  ] as const)('빈 %s단계 %s 결과를 허용한다', (resolvedStage, representation) => {
    expect(decodeHousingMapEnvelope(envelope({
      resolvedStage,
      representation,
      nodes: [],
    })).nodes).toEqual([])
  })

  it.each([
    ['$.data.resolvedStage', { resolvedStage: 0 }],
    ['$.data.resolvedStage', { resolvedStage: 5 }],
    ['$.data.representation', { representation: 'UNKNOWN' }],
    ['$.data.representation', {
      resolvedStage: 4,
      representation: 'AGGREGATE',
    }],
    ['$.data.representation', {
      resolvedStage: 3,
      representation: 'INDIVIDUAL',
    }],
    ['$.data.policyVersion', { policyVersion: '' }],
    ['$.data.regionDatasetVersion', { regionDatasetVersion: '   ' }],
  ])('상위 계약이 잘못되면 %s에서 거부한다', (path, override) => {
    expectContractError(envelope({
      resolvedStage: 3,
      representation: 'AGGREGATE',
      nodes: [],
      ...override,
    }), path)
  })

  it.each([
    ['$.data.nodes[0].type', { type: 'INDIVIDUAL' }],
    ['$.data.nodes[0].groupKey', { groupKey: '' }],
    ['$.data.nodes[0].groupLabel', { groupLabel: '   ' }],
    ['$.data.nodes[0].latitude', { latitude: Number.NaN }],
    ['$.data.nodes[0].latitude', { latitude: 91 }],
    ['$.data.nodes[0].longitude', { longitude: -181 }],
    ['$.data.nodes[0].uniqueComplexCount', { uniqueComplexCount: -1 }],
    ['$.data.nodes[0].uniqueComplexCount', { uniqueComplexCount: 0.5 }],
    ['$.data.nodes[0].nextStage', { nextStage: 3 }],
    ['$.data.nodes[0].expansionZoom', { expansionZoom: Number.POSITIVE_INFINITY }],
    ['$.data.nodes[0].expansionZoom', { expansionZoom: -1 }],
  ])('aggregate 노드가 잘못되면 %s에서 거부한다', (path, override) => {
    expectContractError(envelope({
      resolvedStage: 3,
      representation: 'AGGREGATE',
      nodes: [{ ...AGGREGATE_NODE, ...override }],
    }), path)
  })

  it.each([
    ['$.data.nodes[0].type', { type: 'AGGREGATE' }],
    ['$.data.nodes[0].complexId', { complexId: 0 }],
    ['$.data.nodes[0].latitude', { latitude: -91 }],
    ['$.data.nodes[0].longitude', { longitude: 181 }],
    ['$.data.nodes[0].agency.code', { agency: { code: 7, name: 'LH' } }],
  ])('individual 노드가 잘못되면 %s에서 거부한다', (path, override) => {
    expectContractError(envelope({
      resolvedStage: 4,
      representation: 'INDIVIDUAL',
      nodes: [{ ...INDIVIDUAL_NODE, ...override }],
    }), path)
  })

  it('같은 groupKey의 aggregate 노드가 중복되면 거부한다', () => {
    expectContractError(envelope({
      resolvedStage: 3,
      representation: 'AGGREGATE',
      nodes: [AGGREGATE_NODE, AGGREGATE_NODE],
    }), '$.data.nodes[1].groupKey')
  })

  it('같은 complexId의 individual 노드가 중복되면 거부한다', () => {
    expectContractError(envelope({
      resolvedStage: 4,
      representation: 'INDIVIDUAL',
      nodes: [INDIVIDUAL_NODE, INDIVIDUAL_NODE],
    }), '$.data.nodes[1].complexId')
  })
})

function envelope({
  resolvedStage,
  representation,
  nodes,
  policyVersion = '2026-09-02-v1',
  regionDatasetVersion = '2026-07-01',
}: {
  resolvedStage: unknown
  representation: unknown
  nodes: readonly unknown[]
  policyVersion?: unknown
  regionDatasetVersion?: unknown
}) {
  return {
    data: {
      resolvedStage,
      representation,
      policyVersion,
      regionDatasetVersion,
      nodes,
    },
  }
}

function expectContractError(value: unknown, path: string) {
  expect(() => decodeHousingMapEnvelope(value)).toThrowError(
    expect.objectContaining<Partial<PublicHousingContractError>>({ path }),
  )
}

function withoutType(node: typeof INDIVIDUAL_NODE) {
  const { type: _type, ...raw } = node
  return raw
}
