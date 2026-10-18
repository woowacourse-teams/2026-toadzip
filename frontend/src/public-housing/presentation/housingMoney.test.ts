import { describe, expect, it } from 'vitest'
import { formatHousingMoney, formatHousingMoneyReadable, housingMoneyParts } from './housingMoney.ts'

describe('공통 주택 금액 표시', () => {
  it.each([
    [0, '0원'],
    [9999, '9,999원'],
    [10000, '1만원'],
    [18000000, '1,800만원'],
    [28800000, '2,880만원'],
    [144000, '14.4만원'],
    [99999999, '9,999.9999만원'],
    [100000000, '1억'],
    [180000000, '1.8억'],
    [180000001, '1.80000001억'],
    [Number.MAX_SAFE_INTEGER, '90,071,992.54740991억'],
  ])('%s원을 %s으로 표시한다', (value, expected) => {
    expect(formatHousingMoney(value)).toBe(expected)
  })

  it.each([null, NaN, Infinity, -1])('미제공 또는 유효하지 않은 %s은 공고문 확인으로 표시한다', (value) => {
    expect(formatHousingMoney(value)).toBe('공고문 확인')
    expect(housingMoneyParts(value)).toBeNull()
  })
})

describe('읽는 단위 금액 표시', () => {
  it.each([
    [0, '0원'],
    [9_999, '9,999원'],
    [10_000, '1만원'],
    [185_500, '18만 5,500원'],
    [425_000, '42만 5,000원'],
    [50_000_000, '5,000만원'],
    [100_000_000, '1억원'],
    [120_000_000, '1억 2,000만원'],
    [192_000_000, '1억 9,200만원'],
    [180_000_001, '1억 8,000만 1원'],
    [1_234_567_890_123, '12,345억 6,789만 123원'],
  ])('%s원을 버림 없이 %s으로 표시한다', (value, expected) => {
    expect(formatHousingMoneyReadable(value)).toBe(expected)
  })

  it.each([null, undefined, NaN, Infinity, -1])('미제공 또는 유효하지 않은 %s은 공고문 확인으로 표시한다', (value) => {
    expect(formatHousingMoneyReadable(value)).toBe('공고문 확인')
  })
})
