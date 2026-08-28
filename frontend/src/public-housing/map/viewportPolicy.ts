import type { MapBounds } from '../model/publicHousing.ts'

export const MINIMUM_REQUEST_ZOOM = 13
export const MAXIMUM_LATITUDE_SPAN = 0.25
export const MAXIMUM_LONGITUDE_SPAN = 0.35

const MINIMUM_LATITUDE = -90
const MAXIMUM_LATITUDE = 90
const MINIMUM_LONGITUDE = -180
const MAXIMUM_LONGITUDE = 180
const BOUNDS_SIGNATURE_PRECISION = 5

export interface ViewportSnapshot {
  readonly bounds: MapBounds
  readonly center: {
    readonly latitude: number
    readonly longitude: number
  }
  readonly zoom: number
}

export type ViewportBlockReason =
  | 'invalid-bounds'
  | 'invalid-zoom'
  | 'zoom-too-low'
  | 'latitude-span-too-large'
  | 'longitude-span-too-large'

export type ViewportRequestDecision =
  | {
      readonly allowed: true
      readonly boundsSignature: string
    }
  | {
      readonly allowed: false
      readonly reason: ViewportBlockReason
      readonly boundsSignature: string | null
    }

function isLatitude(value: number): boolean {
  return value >= MINIMUM_LATITUDE && value <= MAXIMUM_LATITUDE
}

function isLongitude(value: number): boolean {
  return value >= MINIMUM_LONGITUDE && value <= MAXIMUM_LONGITUDE
}

function isValidBounds(bounds: MapBounds): boolean {
  const coordinates = [
    bounds.southWestLat,
    bounds.southWestLng,
    bounds.northEastLat,
    bounds.northEastLng,
  ]

  return (
    coordinates.every(Number.isFinite) &&
    isLatitude(bounds.southWestLat) &&
    isLatitude(bounds.northEastLat) &&
    isLongitude(bounds.southWestLng) &&
    isLongitude(bounds.northEastLng) &&
    bounds.southWestLat < bounds.northEastLat &&
    bounds.southWestLng < bounds.northEastLng
  )
}

function normalizeSignatureCoordinate(coordinate: number): string {
  return Number(coordinate.toFixed(BOUNDS_SIGNATURE_PRECISION)).toFixed(
    BOUNDS_SIGNATURE_PRECISION,
  )
}

export function createBoundsSignature(bounds: MapBounds): string | null {
  if (!isValidBounds(bounds)) {
    return null
  }

  return [
    bounds.southWestLat,
    bounds.southWestLng,
    bounds.northEastLat,
    bounds.northEastLng,
  ]
    .map((coordinate) => normalizeSignatureCoordinate(coordinate))
    .join(':')
}

export function evaluateViewportRequest(
  snapshot: ViewportSnapshot,
): ViewportRequestDecision {
  const boundsSignature = createBoundsSignature(snapshot.bounds)

  if (boundsSignature === null) {
    return { allowed: false, reason: 'invalid-bounds', boundsSignature: null }
  }

  if (!Number.isFinite(snapshot.zoom)) {
    return { allowed: false, reason: 'invalid-zoom', boundsSignature }
  }

  if (snapshot.zoom < MINIMUM_REQUEST_ZOOM) {
    return { allowed: false, reason: 'zoom-too-low', boundsSignature }
  }

  const latitudeSpan =
    snapshot.bounds.northEastLat - snapshot.bounds.southWestLat
  if (latitudeSpan > MAXIMUM_LATITUDE_SPAN) {
    return {
      allowed: false,
      reason: 'latitude-span-too-large',
      boundsSignature,
    }
  }

  const longitudeSpan =
    snapshot.bounds.northEastLng - snapshot.bounds.southWestLng
  if (longitudeSpan > MAXIMUM_LONGITUDE_SPAN) {
    return {
      allowed: false,
      reason: 'longitude-span-too-large',
      boundsSignature,
    }
  }

  return { allowed: true, boundsSignature }
}
