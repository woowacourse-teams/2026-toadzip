import { describe, expect, it } from 'vitest'
import { formatAge, formatDigitsAsDate, formatDots, formatWon, withJosa } from './conditionFormat'

describe('conditionFormat', () => {
  it('formats won in 억·만 units', () => {
    expect(formatWon(254_000_000)).toBe('2억 5,400만 원')
    expect(formatWon(2_564_238)).toBe('256만 4,238원')
    expect(formatWon(38_030_000)).toBe('3,803만 원')
    expect(formatWon(0)).toBe('0원')
  })

  it('formats dates with dots and digits while typing', () => {
    expect(formatDots('2026-11-06')).toBe('2026.11.06')
    expect(formatDigitsAsDate('1996')).toBe('1996')
    expect(formatDigitsAsDate('199603')).toBe('1996.03')
    expect(formatDigitsAsDate('19960302')).toBe('1996.03.02')
  })

  it('counts the international age at a reference date', () => {
    expect(formatAge('1996-03-02', '2026-11-06')).toBe('만 30세')
    expect(formatAge('1996-11-07', '2026-11-06')).toBe('만 29세')
  })

  it('picks the particle by the final consonant', () => {
    expect(withJosa('본인 자산', '을/를')).toBe('본인 자산을')
    expect(withJosa('자동차', '이/가')).toBe('자동차가')
  })
})
