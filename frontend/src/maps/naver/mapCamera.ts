import type { ViewportSnapshot } from '../../public-housing/map/viewportPolicy.ts'
import type { NaverMapCameraTarget } from './naverMapTypes.ts'

const INITIAL_CENTER = {
  latitude: 37.5666103,
  longitude: 126.9783882,
}
const CAMERA_COORDINATE_PRECISION = 5
const CAMERA_ZOOM_PRECISION = 2

export function initialMapCamera(
  cameraTarget: NaverMapCameraTarget | undefined,
): Required<Pick<NaverMapCameraTarget, 'latitude' | 'longitude' | 'zoom'>> {
  if (cameraTarget && isValidCameraTarget(
    cameraTarget.latitude,
    cameraTarget.longitude,
    cameraTarget.zoom,
  )) {
    return {
      latitude: cameraTarget.latitude,
      longitude: cameraTarget.longitude,
      zoom: cameraTarget.zoom ?? 14,
    }
  }
  return { ...INITIAL_CENTER, zoom: 14 }
}

export function offsetCameraTarget(
  maps: typeof naver.maps,
  mapInstance: naver.maps.Map,
  target: NaverMapCameraTarget,
): NaverMapCameraTarget {
  const offset = target.screenOffset
  if (!offset || !Number.isFinite(offset.x) || !Number.isFinite(offset.y)) {
    return target
  }
  const projection = mapInstance.getProjection()
  const currentZoom = mapInstance.getZoom()
  const scaleRatio = projection.factor(currentZoom)
    / projection.factor(target.zoom ?? currentZoom)
  if (!Number.isFinite(scaleRatio) || scaleRatio <= 0) {
    return target
  }
  const targetPoint = projection.fromCoordToOffset(
    new maps.LatLng(target.latitude, target.longitude),
  )
  const center = readCoordinateValue(projection.fromOffsetToCoord(new maps.Point(
    targetPoint.x - offset.x * scaleRatio,
    targetPoint.y - offset.y * scaleRatio,
  )))
  if (!center || !isValidCameraTarget(center.latitude, center.longitude, target.zoom)) {
    return target
  }
  return { ...center, zoom: target.zoom }
}

export function isValidCameraTarget(
  latitude: number,
  longitude: number,
  zoom: number | undefined,
): boolean {
  return (
    Number.isFinite(latitude) &&
    latitude >= -90 &&
    latitude <= 90 &&
    Number.isFinite(longitude) &&
    longitude >= -180 &&
    longitude <= 180 &&
    (zoom === undefined || Number.isFinite(zoom))
  )
}

export function cameraCoordinatesChanged(
  previousTarget: NaverMapCameraTarget | null,
  nextTarget: NaverMapCameraTarget,
): boolean {
  return (
    previousTarget === null ||
    fixedValueChanged(
      previousTarget.latitude,
      nextTarget.latitude,
      CAMERA_COORDINATE_PRECISION,
    ) ||
    fixedValueChanged(
      previousTarget.longitude,
      nextTarget.longitude,
      CAMERA_COORDINATE_PRECISION,
    )
  )
}

export function cameraZoomChanged(
  previousZoom: number | undefined,
  nextZoom: number,
) {
  return previousZoom === undefined || fixedValueChanged(
    previousZoom,
    nextZoom,
    CAMERA_ZOOM_PRECISION,
  )
}

function fixedValueChanged(
  previousValue: number,
  nextValue: number,
  fractionDigits: number,
) {
  return previousValue.toFixed(fractionDigits)
    !== nextValue.toFixed(fractionDigits)
}

export function readViewport(mapInstance: naver.maps.Map): ViewportSnapshot | null {
  const bounds = mapInstance.getBounds()
  const southWest = readCoordinate(bounds, 'getSW')
  const northEast = readCoordinate(bounds, 'getNE')
  const center = readCoordinateValue(mapInstance.getCenter())
  const zoom = mapInstance.getZoom()

  if (!southWest || !northEast || !center || !Number.isFinite(zoom)) {
    return null
  }

  return {
    bounds: {
      southWestLat: southWest.latitude,
      southWestLng: southWest.longitude,
      northEastLat: northEast.latitude,
      northEastLng: northEast.longitude,
    },
    center,
    zoom,
  }
}

function readCoordinate(
  bounds: naver.maps.Bounds,
  methodName: 'getNE' | 'getSW',
): { latitude: number; longitude: number } | null {
  const method: unknown = Reflect.get(bounds, methodName)
  if (typeof method !== 'function') {
    return null
  }

  const coordinate: unknown = Reflect.apply(method, bounds, [])
  return readCoordinateValue(coordinate)
}

function readCoordinateValue(
  coordinate: unknown,
): { latitude: number; longitude: number } | null {
  if (typeof coordinate !== 'object' || coordinate === null) {
    return null
  }

  const latitudeMethod: unknown = Reflect.get(coordinate, 'lat')
  const longitudeMethod: unknown = Reflect.get(coordinate, 'lng')
  if (
    typeof latitudeMethod !== 'function' ||
    typeof longitudeMethod !== 'function'
  ) {
    return null
  }

  const latitude: unknown = Reflect.apply(latitudeMethod, coordinate, [])
  const longitude: unknown = Reflect.apply(longitudeMethod, coordinate, [])
  if (typeof latitude !== 'number' || typeof longitude !== 'number') {
    return null
  }

  return { latitude, longitude }
}
