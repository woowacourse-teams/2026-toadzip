import type {
  HousingMapAggregateNode,
  HousingMapAggregateResult,
  HousingMapAggregateStage,
  HousingMapIndividualNode,
  HousingMapIndividualResult,
  HousingMapNextStage,
  HousingMapResult,
  HousingMapStage,
} from '../model/housingMap.ts'
import {
  decodeMapComplex,
  PublicHousingContractError,
} from './publicHousingContract.ts'
import { toMapComplex } from './publicHousingMapper.ts'

export function decodeHousingMapEnvelope(value: unknown): HousingMapResult {
  const envelope = recordAt(value, '$')
  const data = recordAt(recordField(envelope, 'data', '$'), '$.data')
  const stage = stageAt(
    recordField(data, 'resolvedStage', '$.data'),
    '$.data.resolvedStage',
  )
  const representation = representationAt(
    recordField(data, 'representation', '$.data'),
    '$.data.representation',
  )
  const policyVersion = nonEmptyStringAt(
    recordField(data, 'policyVersion', '$.data'),
    '$.data.policyVersion',
  )
  const regionDatasetVersion = nonEmptyStringAt(
    recordField(data, 'regionDatasetVersion', '$.data'),
    '$.data.regionDatasetVersion',
  )
  const nodes = recordField(data, 'nodes', '$.data')

  if (representation === 'AGGREGATE') {
    return decodeAggregateResult(
      stage,
      policyVersion,
      regionDatasetVersion,
      nodes,
    )
  }
  return decodeIndividualResult(
    stage,
    policyVersion,
    regionDatasetVersion,
    nodes,
  )
}

function decodeAggregateResult(
  stage: HousingMapStage,
  policyVersion: string,
  regionDatasetVersion: string,
  nodes: unknown,
): HousingMapAggregateResult {
  if (stage === 4) {
    throw new PublicHousingContractError('$.data.representation')
  }

  const decodedNodes = arrayAt(nodes, '$.data.nodes').map((node, index) =>
    decodeAggregateNode(node, `$.data.nodes[${index}]`, stage),
  )
  return {
    resolvedStage: stage,
    representation: 'AGGREGATE',
    policyVersion,
    regionDatasetVersion,
    nodes: requireUniqueNodes(decodedNodes, 'groupKey'),
  }
}

function decodeIndividualResult(
  stage: HousingMapStage,
  policyVersion: string,
  regionDatasetVersion: string,
  nodes: unknown,
): HousingMapIndividualResult {
  if (stage !== 4) {
    throw new PublicHousingContractError('$.data.representation')
  }

  const decodedNodes = arrayAt(nodes, '$.data.nodes').map((node, index) =>
    decodeIndividualNode(node, `$.data.nodes[${index}]`),
  )
  return {
    resolvedStage: stage,
    representation: 'INDIVIDUAL',
    policyVersion,
    regionDatasetVersion,
    nodes: requireUniqueNodes(decodedNodes, 'complexId'),
  }
}

function decodeAggregateNode(
  value: unknown,
  path: string,
  stage: HousingMapAggregateStage,
): HousingMapAggregateNode {
  const node = recordAt(value, path)
  literalAt(recordField(node, 'type', path), `${path}.type`, 'AGGREGATE')
  const nextStage = nextStageAt(
    recordField(node, 'nextStage', path),
    `${path}.nextStage`,
    stage,
  )

  return {
    type: 'AGGREGATE',
    groupKey: nonEmptyStringAt(
      recordField(node, 'groupKey', path),
      `${path}.groupKey`,
    ),
    groupLabel: nonEmptyStringAt(
      recordField(node, 'groupLabel', path),
      `${path}.groupLabel`,
    ),
    latitude: latitudeAt(
      recordField(node, 'latitude', path),
      `${path}.latitude`,
    ),
    longitude: longitudeAt(
      recordField(node, 'longitude', path),
      `${path}.longitude`,
    ),
    uniqueComplexCount: nonNegativeSafeIntegerAt(
      recordField(node, 'uniqueComplexCount', path),
      `${path}.uniqueComplexCount`,
    ),
    nextStage,
    expansionZoom: nonNegativeFiniteNumberAt(
      recordField(node, 'expansionZoom', path),
      `${path}.expansionZoom`,
    ),
  }
}

function decodeIndividualNode(
  value: unknown,
  path: string,
): HousingMapIndividualNode {
  const node = recordAt(value, path)
  literalAt(recordField(node, 'type', path), `${path}.type`, 'INDIVIDUAL')
  const raw = decodeMapComplex(node, path)
  latitudeAt(raw.latitude, `${path}.latitude`)
  longitudeAt(raw.longitude, `${path}.longitude`)

  return { ...toMapComplex(raw), type: 'INDIVIDUAL' }
}

function nextStageAt(
  value: unknown,
  path: string,
  stage: HousingMapAggregateStage,
): HousingMapNextStage {
  const expected = nextStage(stage)
  if (value !== expected) {
    throw new PublicHousingContractError(path)
  }
  return expected
}

function nextStage(stage: HousingMapAggregateStage): HousingMapNextStage {
  if (stage === 1) {
    return 2
  }
  if (stage === 2) {
    return 3
  }
  return 4
}

function stageAt(value: unknown, path: string): HousingMapStage {
  if (value === 1 || value === 2 || value === 3 || value === 4) {
    return value
  }
  throw new PublicHousingContractError(path)
}

function representationAt(
  value: unknown,
  path: string,
): 'AGGREGATE' | 'INDIVIDUAL' {
  if (value === 'AGGREGATE' || value === 'INDIVIDUAL') {
    return value
  }
  throw new PublicHousingContractError(path)
}

function nonEmptyStringAt(value: unknown, path: string): string {
  if (typeof value === 'string' && value.trim().length > 0) {
    return value
  }
  throw new PublicHousingContractError(path)
}

function literalAt(value: unknown, path: string, literal: string) {
  if (value !== literal) {
    throw new PublicHousingContractError(path)
  }
}

function latitudeAt(value: unknown, path: string): number {
  const latitude = finiteNumberAt(value, path)
  if (latitude >= -90 && latitude <= 90) {
    return latitude
  }
  throw new PublicHousingContractError(path)
}

function longitudeAt(value: unknown, path: string): number {
  const longitude = finiteNumberAt(value, path)
  if (longitude >= -180 && longitude <= 180) {
    return longitude
  }
  throw new PublicHousingContractError(path)
}

function finiteNumberAt(value: unknown, path: string): number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  throw new PublicHousingContractError(path)
}

function nonNegativeFiniteNumberAt(value: unknown, path: string): number {
  const number = finiteNumberAt(value, path)
  if (number >= 0) {
    return number
  }
  throw new PublicHousingContractError(path)
}

function nonNegativeSafeIntegerAt(value: unknown, path: string): number {
  if (Number.isSafeInteger(value) && Number(value) >= 0) {
    return Number(value)
  }
  throw new PublicHousingContractError(path)
}

function recordField(
  record: Record<string, unknown>,
  field: string,
  path: string,
): unknown {
  if (Object.hasOwn(record, field)) {
    return record[field]
  }
  throw new PublicHousingContractError(`${path}.${field}`)
}

function recordAt(value: unknown, path: string): Record<string, unknown> {
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    return value as Record<string, unknown>
  }
  throw new PublicHousingContractError(path)
}

function arrayAt(value: unknown, path: string): readonly unknown[] {
  if (Array.isArray(value)) {
    return value
  }
  throw new PublicHousingContractError(path)
}

function requireUniqueNodes<T extends object>(
  nodes: readonly T[],
  identityField: keyof T,
) {
  const identities = new Set<unknown>()
  nodes.forEach((node, index) => {
    const identity = node[identityField]
    if (identities.has(identity)) {
      throw new PublicHousingContractError(
        `$.data.nodes[${index}].${String(identityField)}`,
      )
    }
    identities.add(identity)
  })
  return nodes
}
