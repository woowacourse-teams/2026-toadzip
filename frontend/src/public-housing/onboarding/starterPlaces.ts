import type { MapLocation } from '../navigation/mapLocation.ts'

export interface StarterPlace extends MapLocation {
  readonly name: string
  readonly detail: string
}

// Starting points are station surroundings, not administrative boundaries.
export const starterPlaces: readonly StarterPlace[] = [
  { name: '서울 잠실', detail: '잠실역 주변', center: { latitude: 37.51322, longitude: 127.10014 }, zoom: 14 },
  { name: '서울 강남', detail: '강남역 주변', center: { latitude: 37.49782, longitude: 127.02775 }, zoom: 14 },
  { name: '성남 판교', detail: '판교역 주변', center: { latitude: 37.39473, longitude: 127.11119 }, zoom: 14 },
]
