import type { RegionBoundary } from '../../public-housing/regions/regionBoundary.ts'

const SVG_NAMESPACE = 'http://www.w3.org/2000/svg'

export function createRegionBoundaryOverlay(
  maps: typeof naver.maps,
  map: naver.maps.Map,
  boundary: RegionBoundary,
): naver.maps.OverlayView {
  // One SVG path avoids registering thousands of GL Polygon objects for islands.
  // Even-odd filling preserves holes and islands inside holes regardless of winding.
  const rings = boundary.polygons.flatMap((polygon) => polygon.map((ring) =>
    ring.map(([longitude, latitude]) => new maps.LatLng(latitude, longitude))))
  const svg = document.createElementNS(SVG_NAMESPACE, 'svg')
  const path = document.createElementNS(SVG_NAMESPACE, 'path')
  svg.setAttribute('aria-hidden', 'true')
  svg.style.cssText = 'position:absolute;pointer-events:none;overflow:hidden;'
  path.setAttribute('fill', '#D34F3E')
  path.setAttribute('fill-opacity', '0.08')
  path.setAttribute('fill-rule', 'evenodd')
  path.setAttribute('stroke', '#D34F3E')
  path.setAttribute('stroke-width', '2')
  path.setAttribute('stroke-linejoin', 'round')
  svg.append(path)

  class BoundaryOverlay extends maps.OverlayView {
    onAdd() {
      this.getPanes().overlayLayer.appendChild(svg)
    }

    draw() {
      if (!this.getMap()) return
      const { width, height } = map.getSize()
      if (width <= 0 || height <= 0) return
      const projection = this.getProjection()
      const center = projection.fromCoordToOffset(map.getCenter())
      const left = center.x - width / 2
      const top = center.y - height / 2
      svg.style.left = `${left}px`
      svg.style.top = `${top}px`
      svg.setAttribute('width', String(width))
      svg.setAttribute('height', String(height))
      path.setAttribute('d', rings.map((ring) => ring.map((coordinate, index) => {
        const point = projection.fromCoordToOffset(coordinate)
        // Subpixel output precision does not remove any source vertex or ring.
        const x = Math.round((point.x - left) * 100) / 100
        const y = Math.round((point.y - top) * 100) / 100
        return `${index === 0 ? 'M' : 'L'}${x},${y}`
      }).join('') + 'Z').join(''))
    }

    onRemove() {
      svg.remove()
    }
  }

  const overlay = new BoundaryOverlay()
  overlay.setMap(map)
  return overlay
}
