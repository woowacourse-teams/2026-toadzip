import { describe, expect, it } from 'vitest'
import { formatDepositTick, formatMonthlyRentTick } from './complexFilterPresentation.ts'

describe('가격 필터 눈금', () => {
  it('월 임대료 눈금은 보증금 눈금처럼 단위를 짧게 표시한다', () => {
    expect([0, 100_000, 500_000, 600_000].map(formatMonthlyRentTick))
      .toEqual(['0', '10만', '50만', '60만+'])
    expect([0, 100_000_000, 500_000_000].map(formatDepositTick))
      .toEqual(['0', '1억', '5억+'])
  })
})
