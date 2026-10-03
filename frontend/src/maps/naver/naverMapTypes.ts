import type { ViewportSnapshot } from '../../public-housing/map/viewportPolicy.ts'
import type { MapBounds } from '../../public-housing/model/publicHousing.ts'
import type { MapMarkerPresentation } from '../../public-housing/presentation/mapMarkerPresentation.ts'
import type { RegionBoundary } from '../../public-housing/regions/regionBoundary.ts'

export interface NaverMapComplexMarker extends MapMarkerPresentation {
  id: string
  highlighted?: boolean
  latitude: number
  longitude: number
  name: string
  selected?: boolean
}

export type NaverMapMarker = NaverMapComplexMarker

export interface NaverMapAggregateMarker {
  readonly expansionZoom: number
  readonly groupKey: string
  readonly groupLabel: string
  readonly latitude: number
  readonly longitude: number
  readonly nextStage: number
  readonly uniqueComplexCount: number
}

export interface NaverMapViewportChangeMetadata {
  readonly cause: 'initial' | 'user' | 'programmatic' | 'resize'
}

export interface NaverMapScreenPadding {
  readonly top: number
  readonly right: number
  readonly bottom: number
  readonly left: number
}

export interface NaverMapCameraTarget {
  readonly latitude: number
  readonly longitude: number
  /** Fit the complete region instead of applying center, zoom, or screenOffset. */
  readonly bounds?: MapBounds
  readonly boundsPadding?: {
    readonly top: number
    readonly right: number
    readonly bottom: number
    readonly left: number
  }
  /** Limit fitBounds zoom for a small number of results. */
  readonly maxZoom?: number
  /** Reveal the marker only when its card is clipped or covered by these panels. */
  readonly revealPadding?: NaverMapScreenPadding
  /** Target position relative to the map center, in pixels at the destination zoom. */
  readonly screenOffset?: { readonly x: number; readonly y: number }
  readonly zoom?: number
}

interface NaverMapCommonProps {
  cameraRequestId?: number
  cameraTarget?: NaverMapCameraTarget
  dataBusy?: boolean
  onMarkerHighlight?: (complexId: string | null) => void
  onMarkerSelect?: (complexId: string) => void
  onTransitionInterrupt?: () => void
  onViewportChange?: (viewport: ViewportSnapshot, metadata?: NaverMapViewportChangeMetadata) => void
  regionBoundary?: RegionBoundary | null
  visiblePadding?: NaverMapScreenPadding
  transitioning?: boolean
}

interface NaverMapAggregateProps extends NaverMapCommonProps {
  aggregateMarkers: readonly NaverMapAggregateMarker[]
  markers?: never
  onAggregateMarkerSelect: (marker: NaverMapAggregateMarker) => void
  representation: 'AGGREGATE'
}

interface NaverMapIndividualProps extends NaverMapCommonProps {
  aggregateMarkers?: never
  markers: readonly NaverMapMarker[]
  onAggregateMarkerSelect?: never
  representation: 'INDIVIDUAL'
}

export type NaverMapProps =
  | NaverMapAggregateProps
  | NaverMapIndividualProps
