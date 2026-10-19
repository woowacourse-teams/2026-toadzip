import styles from './NaverMap.module.css'

export function createCurrentLocationMarker(
  maps: typeof naver.maps,
  map: naver.maps.Map,
  position: naver.maps.LatLng,
) {
  const content = document.createElement('div')
  content.className = styles.currentLocation
  content.setAttribute('aria-hidden', 'true')
  const pulse = document.createElement('span')
  pulse.className = styles.currentLocationPulse
  const dot = document.createElement('span')
  dot.className = styles.currentLocationDot
  content.append(pulse, dot)

  return new maps.Marker({
    clickable: false,
    draggable: false,
    icon: {
      anchor: new maps.Point(24, 24),
      content,
      size: new maps.Size(48, 48),
    },
    map,
    position,
    zIndex: 50,
  })
}
