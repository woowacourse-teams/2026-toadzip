import type { MapBounds } from '../model/publicHousing.ts'

export type Position = readonly [longitude: number, latitude: number]

export interface RegionBoundary {
  readonly regionCode: string
  readonly version: string
  readonly polygons: readonly (readonly (readonly Position[])[])[]
}

export interface RegionBoundaryMetadata {
  readonly regionCode: string
  readonly name: string
  readonly bounds: MapBounds
  readonly path: string
}
