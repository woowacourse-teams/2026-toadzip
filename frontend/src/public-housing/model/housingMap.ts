import type { MapComplex } from './publicHousing.ts'

export type HousingMapStage = 1 | 2 | 3 | 4
export type HousingMapAggregateStage = 1 | 2 | 3
export type HousingMapNextStage = 2 | 3 | 4

interface HousingMapResultBase {
  readonly policyVersion: string
  readonly regionDatasetVersion: string
}

export interface HousingMapAggregateNode {
  readonly type: 'AGGREGATE'
  readonly groupKey: string
  readonly groupLabel: string
  readonly latitude: number
  readonly longitude: number
  readonly uniqueComplexCount: number
  readonly nextStage: HousingMapNextStage
  readonly expansionZoom: number
}

export interface HousingMapIndividualNode extends MapComplex {
  readonly type: 'INDIVIDUAL'
}

export interface HousingMapAggregateResult extends HousingMapResultBase {
  readonly resolvedStage: HousingMapAggregateStage
  readonly representation: 'AGGREGATE'
  readonly nodes: readonly HousingMapAggregateNode[]
}

export interface HousingMapIndividualResult extends HousingMapResultBase {
  readonly resolvedStage: 4
  readonly representation: 'INDIVIDUAL'
  readonly nodes: readonly HousingMapIndividualNode[]
}

export type HousingMapResult =
  | HousingMapAggregateResult
  | HousingMapIndividualResult
