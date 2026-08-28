const MAP_LATITUDE_QUERY_KEY = 'mapLat'
const MAP_LONGITUDE_QUERY_KEY = 'mapLng'
const MAP_ZOOM_QUERY_KEY = 'mapZoom'
const MAP_LOCATION_QUERY_KEYS = [
  MAP_LATITUDE_QUERY_KEY,
  MAP_LONGITUDE_QUERY_KEY,
  MAP_ZOOM_QUERY_KEY,
] as const
const DECIMAL_NUMBER = /^-?(?:\d+(?:\.\d*)?|\.\d+)$/
const MINIMUM_LATITUDE = -90
const MAXIMUM_LATITUDE = 90
const MINIMUM_LONGITUDE = -180
const MAXIMUM_LONGITUDE = 180

export const DEFAULT_MINIMUM_MAP_ZOOM = 6
export const DEFAULT_MAXIMUM_MAP_ZOOM = 21

export interface MapCenter {
  readonly latitude: number
  readonly longitude: number
}

export interface MapLocation {
  readonly center: MapCenter
  readonly zoom: number
}

export interface MapLocationOptions {
  readonly minimumZoom?: number
  readonly maximumZoom?: number
}

export type MapLocationResult =
  | { readonly kind: 'absent' }
  | { readonly kind: 'invalid' }
  | {
      readonly kind: 'valid'
      readonly center: MapCenter
      readonly zoom: number
    }

interface MapZoomRange {
  readonly minimumZoom: number
  readonly maximumZoom: number
}

function resolveZoomRange(options: MapLocationOptions): MapZoomRange {
  const minimumZoom =
    options.minimumZoom ?? DEFAULT_MINIMUM_MAP_ZOOM
  const maximumZoom =
    options.maximumZoom ?? DEFAULT_MAXIMUM_MAP_ZOOM

  if (
    !Number.isFinite(minimumZoom) ||
    !Number.isFinite(maximumZoom) ||
    minimumZoom > maximumZoom
  ) {
    throw new RangeError('유효한 지도 zoom 범위가 아닙니다.')
  }

  return { minimumZoom, maximumZoom }
}

function parseDecimal(value: string | null): number | null {
  if (value === null || !DECIMAL_NUMBER.test(value)) {
    return null
  }

  const number = Number(value)
  if (!Number.isFinite(number)) {
    return null
  }

  return number
}

function isLatitude(value: number): boolean {
  return value >= MINIMUM_LATITUDE && value <= MAXIMUM_LATITUDE
}

function isLongitude(value: number): boolean {
  return value >= MINIMUM_LONGITUDE && value <= MAXIMUM_LONGITUDE
}

function isZoomInRange(zoom: number, range: MapZoomRange): boolean {
  return zoom >= range.minimumZoom && zoom <= range.maximumZoom
}

function isValidLocation(
  location: MapLocation,
  range: MapZoomRange,
): boolean {
  return (
    Number.isFinite(location.center.latitude) &&
    isLatitude(location.center.latitude) &&
    Number.isFinite(location.center.longitude) &&
    isLongitude(location.center.longitude) &&
    Number.isFinite(location.zoom) &&
    isZoomInRange(location.zoom, range)
  )
}

function hasNoMapLocationQuery(searchParams: URLSearchParams): boolean {
  return MAP_LOCATION_QUERY_KEYS.every(
    (key) => searchParams.getAll(key).length === 0,
  )
}

function hasOneValuePerMapLocationKey(
  searchParams: URLSearchParams,
): boolean {
  return MAP_LOCATION_QUERY_KEYS.every(
    (key) => searchParams.getAll(key).length === 1,
  )
}

export function parseMapLocation(
  searchParams: URLSearchParams,
  options: MapLocationOptions = {},
): MapLocationResult {
  const zoomRange = resolveZoomRange(options)
  if (hasNoMapLocationQuery(searchParams)) {
    return { kind: 'absent' }
  }

  if (!hasOneValuePerMapLocationKey(searchParams)) {
    return { kind: 'invalid' }
  }

  const latitude = parseDecimal(searchParams.get(MAP_LATITUDE_QUERY_KEY))
  const longitude = parseDecimal(searchParams.get(MAP_LONGITUDE_QUERY_KEY))
  const zoom = parseDecimal(searchParams.get(MAP_ZOOM_QUERY_KEY))
  if (latitude === null || longitude === null || zoom === null) {
    return { kind: 'invalid' }
  }

  const location = { center: { latitude, longitude }, zoom }
  if (!isValidLocation(location, zoomRange)) {
    return { kind: 'invalid' }
  }

  return { kind: 'valid', ...location }
}

function formatFixed(value: number, fractionDigits: number): string {
  const formatted = value.toFixed(fractionDigits)
  if (Number(formatted) === 0) {
    return (0).toFixed(fractionDigits)
  }

  return formatted
}

export function setMapLocationQuery(
  searchParams: URLSearchParams,
  location: MapLocation,
  options: MapLocationOptions = {},
): URLSearchParams {
  const zoomRange = resolveZoomRange(options)
  if (!isValidLocation(location, zoomRange)) {
    throw new TypeError('유효한 지도 위치가 아닙니다.')
  }

  const nextSearchParams = clearMapLocationQuery(searchParams)
  nextSearchParams.set(
    MAP_LATITUDE_QUERY_KEY,
    formatFixed(location.center.latitude, 5),
  )
  nextSearchParams.set(
    MAP_LONGITUDE_QUERY_KEY,
    formatFixed(location.center.longitude, 5),
  )
  nextSearchParams.set(MAP_ZOOM_QUERY_KEY, formatFixed(location.zoom, 2))
  return nextSearchParams
}

export function clearMapLocationQuery(
  searchParams: URLSearchParams,
): URLSearchParams {
  const nextSearchParams = new URLSearchParams(searchParams)
  MAP_LOCATION_QUERY_KEYS.forEach((key) => nextSearchParams.delete(key))
  return nextSearchParams
}
