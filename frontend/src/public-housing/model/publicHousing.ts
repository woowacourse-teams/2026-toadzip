export interface MapBounds {
  readonly southWestLat: number
  readonly southWestLng: number
  readonly northEastLat: number
  readonly northEastLng: number
}

export interface HousingAgency {
  readonly code: string | null
  readonly name: string | null
}

export interface RawRepresentativeAnnouncement {
  readonly announcementId: number
  readonly publicationType: string | null
  readonly applicationStatus: string | null
  readonly applicationEndAt: string | null
  readonly dDay: number | null
}

export interface RawComplexListItem {
  readonly complexId: number
  readonly thumbnailImageUrl: string | null
  readonly regionName: string | null
  readonly name: string | null
  readonly rentalType: string | null
  readonly agency: HousingAgency | null
  readonly exclusiveAreaMin: number | null
  readonly exclusiveAreaMax: number | null
  readonly depositMin: number | null
  readonly depositMax: number | null
  readonly monthlyRentMin: number | null
  readonly monthlyRentMax: number | null
  readonly representativeAnnouncement: RawRepresentativeAnnouncement | null
}

export interface RawComplexPage {
  readonly items: readonly RawComplexListItem[]
  readonly nextCursor: string | null
  readonly hasNext: boolean
}

export interface RawMapComplex {
  readonly complexId: number
  readonly name: string | null
  readonly latitude: number
  readonly longitude: number
  readonly rentalType: string | null
  readonly agency: HousingAgency | null
  readonly exclusiveAreaMin: number | null
  readonly exclusiveAreaMax: number | null
  readonly depositMin: number | null
  readonly depositMax: number | null
  readonly monthlyRentMin: number | null
  readonly monthlyRentMax: number | null
}

export interface RawMapComplexResponse {
  readonly items: readonly RawMapComplex[]
}

export interface RepresentativeAnnouncement {
  readonly announcementId: string
  readonly publicationType: string | null
  readonly applicationStatus: string | null
  readonly applicationEndAt: string | null
  readonly dDay: number | null
}

export interface ComplexListItem {
  readonly complexId: string
  readonly thumbnailImageUrl: string | null
  readonly regionName: string | null
  readonly name: string | null
  readonly rentalType: string | null
  readonly agency: HousingAgency | null
  readonly exclusiveAreaMin: number | null
  readonly exclusiveAreaMax: number | null
  readonly depositMin: number | null
  readonly depositMax: number | null
  readonly monthlyRentMin: number | null
  readonly monthlyRentMax: number | null
  readonly representativeAnnouncement: RepresentativeAnnouncement | null
  readonly raw: RawComplexListItem
}

export interface ComplexPage {
  readonly items: readonly ComplexListItem[]
  readonly nextCursor: string | null
  readonly hasNext: boolean
  readonly raw: RawComplexPage
}

export interface MapComplex {
  readonly complexId: string
  readonly name: string | null
  readonly latitude: number
  readonly longitude: number
  readonly rentalType: string | null
  readonly agency: HousingAgency | null
  readonly exclusiveAreaMin: number | null
  readonly exclusiveAreaMax: number | null
  readonly depositMin: number | null
  readonly depositMax: number | null
  readonly monthlyRentMin: number | null
  readonly monthlyRentMax: number | null
  readonly raw: RawMapComplex
}
