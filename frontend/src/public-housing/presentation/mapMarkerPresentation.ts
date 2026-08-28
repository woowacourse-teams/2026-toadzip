import type { HousingAgency, MapComplex } from '../model/publicHousing.ts'

export interface MapMarkerPresentation {
  readonly agencyLabel: string
  readonly areaLabel: string
  readonly monthlyRentLabel: string
  readonly rentalTypeLabel: string
}

export function presentMapComplexMarker(
  complex: MapComplex,
): MapMarkerPresentation {
  return presentMarker({
    agency: complex.agency,
    areas: [complex.exclusiveAreaMin, complex.exclusiveAreaMax],
    monthlyRents: [complex.monthlyRentMin, complex.monthlyRentMax],
    rentalType: complex.rentalType,
  })
}

export function presentComplexDetailMarker(
  detail: DetailMarkerSource,
): MapMarkerPresentation {
  return presentMarker({
    agency: detail.agency,
    areas: detail.housingTypes.map(({ exclusiveArea }) => exclusiveArea),
    monthlyRents: detail.housingTypes.flatMap(({ currentSupplyConditions }) =>
      currentSupplyConditions.map(({ monthlyRent }) => monthlyRent),
    ),
    rentalType: detail.rentalType,
  })
}

interface DetailMarkerSource {
  readonly agency: HousingAgency | null
  readonly housingTypes: readonly {
    readonly currentSupplyConditions: readonly {
      readonly monthlyRent: number | null
    }[]
    readonly exclusiveArea: number | null
  }[]
  readonly rentalType: string | null
}

function presentMarker({
  agency,
  areas,
  monthlyRents,
  rentalType,
}: {
  readonly agency: HousingAgency | null
  readonly areas: readonly (number | null)[]
  readonly monthlyRents: readonly (number | null)[]
  readonly rentalType: string | null
}): MapMarkerPresentation {
  return {
    agencyLabel: firstNonBlank(agency?.code, agency?.name) ?? '기관 확인 중',
    areaLabel: formatRange(
      areas,
      formatArea,
      '면적 확인 중',
      stripAreaUnit,
    ),
    monthlyRentLabel: formatRange(
      monthlyRents,
      formatMoney,
      '정보 확인 중',
      stripWonUnit,
    ),
    rentalTypeLabel: rentalTypeLabel(rentalType),
  }
}

function firstNonBlank(...values: readonly (string | null | undefined)[]) {
  return values.find((value): value is string =>
    typeof value === 'string' && value.trim().length > 0,
  )
}

function formatRange(
  candidates: readonly (number | null)[],
  formatter: (value: number) => string,
  fallback: string,
  rangeStartFormatter: (value: string) => string,
) {
  const values = candidates.filter(isFiniteNumber).sort((left, right) =>
    left - right,
  )
  if (values.length === 0) {
    return fallback
  }
  const minimum = values[0]
  const maximum = values.at(-1) ?? minimum
  const formattedMinimum = formatter(minimum)
  const formattedMaximum = formatter(maximum)
  if (minimum === maximum || formattedMinimum === formattedMaximum) {
    return formattedMinimum
  }
  return `${rangeStartFormatter(formattedMinimum)}~${formattedMaximum}`
}

function isFiniteNumber(value: number | null): value is number {
  return value !== null && Number.isFinite(value)
}

function formatArea(value: number) {
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}㎡`
}

function stripAreaUnit(value: string) {
  return value.endsWith('㎡') ? value.slice(0, -1) : value
}

function formatMoney(value: number) {
  const amountWon = Math.max(0, Math.round(value))
  if (amountWon < 10_000) {
    return `${amountWon.toLocaleString('ko-KR')}원`
  }
  if (amountWon < 100_000_000) {
    return `${formatManWon(amountWon)}만 원`
  }
  return formatEokWon(amountWon)
}

function formatManWon(amountWon: number) {
  return (amountWon / 10_000).toLocaleString('ko-KR', {
    maximumFractionDigits: 1,
  })
}

function formatEokWon(amountWon: number) {
  const amountEokWon = amountWon / 100_000_000
  return `${amountEokWon.toLocaleString('ko-KR', {
    maximumFractionDigits: 1,
  })}억 원`
}

function stripWonUnit(value: string) {
  if (value.endsWith(' 원')) {
    return value.slice(0, -2)
  }
  return value.endsWith('원') ? value.slice(0, -1) : value
}

function rentalTypeLabel(rentalType: string | null) {
  if (rentalType === null) {
    return '임대유형 확인 중'
  }
  const labels: Record<string, string> = {
    ETC: '기타 공공임대',
    HAPPY_HOUSING: '행복주택',
    INTEGRATED_PUBLIC_RENTAL: '통합공공임대',
    NATIONAL_RENTAL: '국민임대',
    PERMANENT_RENTAL: '영구임대',
    PUBLIC_RENTAL_50Y: '50년 공공임대',
    REDEVELOPMENT_RENTAL: '재개발임대',
  }
  return labels[rentalType] ?? '임대유형 확인 중'
}
