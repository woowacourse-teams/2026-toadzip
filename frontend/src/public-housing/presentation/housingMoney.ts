import { MISSING_DATA_LABEL } from './missingData.ts'

export interface HousingMoneyParts {
  readonly digits: string
  readonly unit: '원' | '만원' | '억'
}

/** 원 단위 금액을 반올림하지 않고 서비스의 만원/억 표기로 변환한다. */
export function housingMoneyParts(value: number | null): HousingMoneyParts | null {
  if (value === null || !Number.isFinite(value) || value < 0) return null

  const divisor = value >= 100_000_000 ? 100_000_000 : value >= 10_000 ? 10_000 : 1
  const unit = divisor === 100_000_000 ? '억' : divisor === 10_000 ? '만원' : '원'
  if (Number.isInteger(value)) {
    const amount = BigInt(value)
    const base = BigInt(divisor)
    const whole = (amount / base).toLocaleString('ko-KR')
    const fraction = (amount % base).toString()
      .padStart(String(divisor).length - 1, '0')
      .replace(/0+$/, '')
    return { digits: fraction ? `${whole}.${fraction}` : whole, unit }
  }
  return {
    digits: (value / divisor).toLocaleString('ko-KR', { maximumFractionDigits: 20 }),
    unit,
  }
}

export function formatHousingMoney(value: number | null): string {
  const amount = housingMoneyParts(value)
  return amount === null ? MISSING_DATA_LABEL : `${amount.digits}${amount.unit}`
}
