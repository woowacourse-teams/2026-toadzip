import type { HousingAgency, MapComplex } from '../model/publicHousing.ts'

export interface MapMarkerAmount {
  readonly digits: string
  readonly unit: '원' | '만' | '억' | '조'
  readonly exactLabel: string
}

export interface MapMarkerPresentation {
  readonly agencyLabel: string
  readonly agencyName: string
  readonly rentalTypeLabel: string
  readonly rentalTypeName: string
  readonly deposit: MapMarkerAmount | null
  readonly monthlyRent: MapMarkerAmount | null
}

interface MarkerName {
  readonly label: string
  readonly name: string
}

const AGENCIES: readonly MarkerName[] = [
  { label: 'LH', name: '한국토지주택공사' },
  { label: 'SH', name: '서울주택도시공사' },
  { label: 'GH', name: '경기주택도시공사' },
  { label: '기타', name: '기타' },
]
const RENTAL_TYPES = new Map<string, MarkerName>([
  ['HAPPY_HOUSING', { label: '행복', name: '행복주택' }],
  ['NATIONAL_RENTAL', { label: '국민', name: '국민임대' }],
  ['PERMANENT_RENTAL', { label: '영구', name: '영구임대' }],
  ['PUBLIC_RENTAL_5Y', { label: '5년', name: '5년 공공임대' }],
  ['PUBLIC_RENTAL_10Y', { label: '10년', name: '10년 공공임대' }],
  ['PUBLIC_RENTAL_50Y', { label: '50년', name: '50년 공공임대' }],
  ['INTEGRATED_PUBLIC_RENTAL', { label: '통합', name: '통합공공임대' }],
  ['REDEVELOPMENT_RENTAL', { label: '재개발', name: '재개발임대' }],
  ['LONG_TERM_JEONSE', { label: '전세', name: '장기전세' }],
  ['ETC', { label: '기타', name: '기타 공공임대' }],
])

export function presentMapComplexMarker(
  complex: MapComplex,
): MapMarkerPresentation {
  return presentMarker(
    complex.agency,
    complex.rentalType,
    complex.depositMin,
    complex.monthlyRentMin,
  )
}

export function presentComplexDetailMarker(
  detail: DetailMarkerSource,
): MapMarkerPresentation {
  const conditions = detail.housingTypes.flatMap(
    ({ currentSupplyConditions }) => currentSupplyConditions,
  )
  return presentMarker(
    detail.agency,
    detail.rentalType,
    minimumAmount(conditions.map(({ deposit }) => deposit)),
    minimumAmount(conditions.map(({ monthlyRent }) => monthlyRent)),
  )
}

interface DetailMarkerSource {
  readonly agency: HousingAgency | null
  readonly housingTypes: readonly {
    readonly currentSupplyConditions: readonly {
      readonly deposit: number | null
      readonly monthlyRent: number | null
    }[]
  }[]
  readonly rentalType: string | null
}

function presentMarker(
  agency: HousingAgency | null,
  rentalType: string | null,
  deposit: number | null,
  monthlyRent: number | null,
): MapMarkerPresentation {
  const agencyPresentation = presentAgency(agency)
  const rentalTypePresentation = presentRentalType(rentalType)
  return {
    agencyLabel: agencyPresentation.label,
    agencyName: agencyPresentation.name,
    rentalTypeLabel: rentalTypePresentation.label,
    rentalTypeName: rentalTypePresentation.name,
    deposit: presentAmount(deposit),
    monthlyRent: presentAmount(monthlyRent),
  }
}

function presentAgency(agency: HousingAgency | null): MarkerName {
  const code = nonBlank(agency?.code)
  const name = nonBlank(agency?.name)
  const known = AGENCIES.find((candidate) => candidate.label === code
    || candidate.name === code)
    ?? AGENCIES.find((candidate) => candidate.name === name)
  const label = code === 'ETC' ? '기타' : known?.label ?? code ?? name ?? '미상'
  return {
    label,
    name: name ?? (code === 'ETC' ? '기타' : known?.name) ?? code ?? '기관 정보 없음',
  }
}

function presentRentalType(rentalType: string | null): MarkerName {
  const code = nonBlank(rentalType)
  if (code === null) {
    return { label: '미상', name: '임대유형 정보 없음' }
  }
  return RENTAL_TYPES.get(code) ?? { label: '미상', name: code }
}

function nonBlank(value: string | null | undefined): string | null {
  const trimmed = value?.trim()
  return trimmed ? trimmed : null
}

function isValidAmount(value: number | null): value is number {
  return value !== null && Number.isFinite(value) && value >= 0
}

function minimumAmount(values: readonly (number | null)[]): number | null {
  return values.reduce<number | null>((minimum, value) => {
    if (!isValidAmount(value)) return minimum
    return minimum === null || value < minimum ? value : minimum
  }, null)
}

function presentAmount(value: number | null): MapMarkerAmount | null {
  if (!isValidAmount(value)) return null

  const won = BigInt(Math.floor(value))
  const exactLabel = `${value.toLocaleString('ko-KR', { maximumFractionDigits: 20 })}원`
  if (won < 10_000n) {
    return { digits: String(won), unit: '원', exactLabel }
  }
  const unit = won < 100_000_000n ? '만' : won < 1_000_000_000_000n ? '억' : '조'
  const divisor = unit === '만' ? 10_000n : unit === '억' ? 100_000_000n : 1_000_000_000_000n
  const decimalPlaces = won >= divisor * 1000n ? 0
    : unit === '만' || won >= divisor * 100n ? 1 : 2
  const scale = 10n ** BigInt(decimalPlaces)
  // Integer division keeps a minimum from rounding up, including unit boundaries.
  const scaled = won / (divisor / scale)
  const whole = scaled / scale
  const fraction = String(scaled % scale).padStart(decimalPlaces, '0').replace(/0+$/, '')
  const digits = fraction ? `${whole}.${fraction}` : String(whole)
  return { digits, unit, exactLabel }
}
