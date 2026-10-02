const RENTAL_TYPE_LABELS: Readonly<Record<string, string>> = {
  ETC: '기타 공공임대',
  HAPPY_HOUSING: '행복주택',
  INTEGRATED_PUBLIC_RENTAL: '통합공공임대',
  NATIONAL_RENTAL: '국민임대',
  PERMANENT_RENTAL: '영구임대',
  PUBLIC_RENTAL_50Y: '50년 공공임대',
  REDEVELOPMENT_RENTAL: '재개발임대',
}

export function rentalTypeLabel(value: string | null): string | null {
  return value === null ? null : RENTAL_TYPE_LABELS[value] ?? null
}
