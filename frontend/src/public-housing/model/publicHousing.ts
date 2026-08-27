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

export interface RawComplexAddress {
  readonly regionName: string | null
  readonly roadAddress: string | null
  readonly latitude: number | null
  readonly longitude: number | null
}

export interface RawComplexSupplyCondition {
  readonly target: string | null
  readonly deposit: number | null
  readonly monthlyRent: number | null
  readonly convertibleDeposit: number | null
}

export interface RawComplexHousingType {
  readonly housingTypeId: number
  readonly name: string | null
  readonly exclusiveArea: number | null
  readonly supplyArea: number | null
  readonly floorPlanImageUrl: string | null
  readonly floorPlan3dImageUrl: string | null
  readonly isDuplex: boolean | null
  readonly maintenanceFee: number | null
  readonly currentSupplyConditions: readonly RawComplexSupplyCondition[]
}

export interface RawComplexCurrentAnnouncement {
  readonly announcementId: number
  readonly title: string | null
  readonly publicationType: string | null
  readonly applicationStatus: string | null
  readonly targets: readonly string[]
  readonly applicationStartAt: string | null
  readonly applicationEndAt: string | null
  readonly dDay: number | null
  readonly actualCompetitionRate: number | null
}

export interface RawComplexDetail {
  readonly complexId: number
  readonly name: string | null
  readonly rentalType: string | null
  readonly agency: HousingAgency | null
  readonly address: RawComplexAddress | null
  readonly completionDate: string | null
  readonly buildingType: string | null
  readonly hasElevator: boolean | null
  readonly heatingType: string | null
  readonly corridorType: string | null
  readonly moveOutCountLastYear: number | null
  readonly totalHouseholdCount: number | null
  readonly totalParkingCount: number | null
  readonly images: readonly string[]
  readonly overviewImageUrl: string | null
  readonly housingTypes: readonly RawComplexHousingType[]
  readonly currentAnnouncements: readonly RawComplexCurrentAnnouncement[]
}

export type ComplexAddress = RawComplexAddress

export interface ComplexSupplyCondition {
  readonly target: string | null
  readonly deposit: number | null
  readonly monthlyRent: number | null
  readonly convertibleDeposit: number | null
}

export interface ComplexHousingType {
  readonly housingTypeId: string
  readonly name: string | null
  readonly exclusiveArea: number | null
  readonly supplyArea: number | null
  readonly floorPlanImageUrl: string | null
  readonly floorPlan3dImageUrl: string | null
  readonly isDuplex: boolean | null
  readonly maintenanceFee: number | null
  readonly currentSupplyConditions: readonly ComplexSupplyCondition[]
}

export interface ComplexCurrentAnnouncement {
  readonly announcementId: string
  readonly title: string | null
  readonly publicationType: string | null
  readonly applicationStatus: string | null
  readonly targets: readonly string[]
  readonly applicationStartAt: string | null
  readonly applicationEndAt: string | null
  readonly dDay: number | null
  readonly actualCompetitionRate: number | null
}

export interface ComplexDetail {
  readonly complexId: string
  readonly name: string | null
  readonly rentalType: string | null
  readonly agency: HousingAgency | null
  readonly address: ComplexAddress | null
  readonly completionDate: string | null
  readonly buildingType: string | null
  readonly hasElevator: boolean | null
  readonly heatingType: string | null
  readonly corridorType: string | null
  readonly moveOutCountLastYear: number | null
  readonly totalHouseholdCount: number | null
  readonly totalParkingCount: number | null
  readonly images: readonly string[]
  readonly overviewImageUrl: string | null
  readonly housingTypes: readonly ComplexHousingType[]
  readonly currentAnnouncements: readonly ComplexCurrentAnnouncement[]
  readonly raw: RawComplexDetail
}
