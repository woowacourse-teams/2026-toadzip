import { describe, expect, it } from 'vitest'
import { compactMarkerAmountParts, formatAmount } from './mapMarkerAmount.ts'

describe('COMPACT marker amount', () => {
  it.each([
    [null, '-'],
    [undefined, '-'],
    [0, '0원'],
    [999, '0만'],
    [3_000, '0.3만'],
    [9_999, '0.9만'],
    [10_000, '1만'],
    [350_000, '35만'],
    [1_000_000, '100만'],
    [3_500_000, '350만'],
    [3_567_890, '356만'],
    [9_990_000, '999만'],
    [10_000_000, '1천'],
    [13_000_000, '1.3천'],
    [35_000_000, '3.5천'],
    [35_500_000, '3.5천'],
    [35_550_000, '3.5천'],
    [99_990_000, '9.9천'],
    [100_000_000, '1억'],
    [103_000_000, '1억'],
    [130_000_000, '1.3억'],
    [135_000_000, '1.3억'],
    [135_500_000, '1.3억'],
    [350_000_000, '3.5억'],
    [1_000_000_000, '10억'],
  ])('정책 예시 %s원을 %s로 표시한다', (amountWon, expected) => {
    expect(formatAmount(amountWon, 'COMPACT')).toBe(expected)
  })

  it.each([
    [1, '0만'],
    [1_000, '0.1만'],
    [10_001, '1만'],
    [19_999, '1만'],
    [9_999_999, '999만'],
    [10_000_001, '1천'],
    [10_999_999, '1천'],
    [11_000_000, '1.1천'],
    [99_999_999, '9.9천'],
    [100_000_001, '1억'],
    [109_999_999, '1억'],
    [110_000_000, '1.1억'],
    [999_999_999, '9.9억'],
    [100_000_000_000, '1,000억'],
    [123_456_789_012, '1,234.5억'],
    [Number.MAX_SAFE_INTEGER, '90,071,992.5억'],
  ])('경계와 큰 금액 %s원을 반올림하지 않고 %s로 표시한다', (amountWon, expected) => {
    expect(formatAmount(amountWon, 'COMPACT')).toBe(expected)
  })

  it.each([
    null, undefined, -1, 0.1, 10_000.5, Number.NaN,
    Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1,
  ])('결측 또는 잘못된 입력 %s을 결측으로 표시한다', amountWon => {
    expect(formatAmount(amountWon, 'COMPACT')).toBe('-')
    expect(compactMarkerAmountParts(amountWon)).toBeNull()
  })

  it.each([
    [0, { digits: '0', unit: '원' }],
    [3_000, { digits: '0.3', unit: '만' }],
    [3_567_890, { digits: '356', unit: '만' }],
    [35_550_000, { digits: '3.5', unit: '천' }],
    [135_500_000, { digits: '1.3', unit: '억' }],
    [Number.MAX_SAFE_INTEGER, { digits: '90,071,992.5', unit: '억' }],
  ])('마커의 숫자와 단위를 분리해도 같은 표시를 제공한다: %s원', (amountWon, expected) => {
    expect(compactMarkerAmountParts(amountWon)).toEqual(expected)
    expect(formatAmount(amountWon, 'COMPACT')).toBe(`${expected.digits}${expected.unit}`)
  })
})
