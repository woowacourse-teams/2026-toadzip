import { describe, expect, it } from 'vitest'
import { formatAreaTick, formatDepositTick, formatMonthlyRentTick, topicSummary } from './complexFilterPresentation.ts'

describe('가격 필터 눈금', () => {
  it('월 임대료 눈금은 보증금 눈금처럼 단위를 짧게 표시한다', () => {
    expect([0, 100_000, 500_000, 600_000].map(formatMonthlyRentTick))
      .toEqual(['0', '10만', '50만', '60만+'])
    expect([0, 100_000_000, 500_000_000].map(formatDepositTick))
      .toEqual(['0', '1억', '5억+'])
  })
})

describe('전용면적 필터 눈금', () => {
  it('60평까지 표시하고 마지막 눈금은 빠른 선택과 같은 상한 없는 구간이다', () => {
    expect([0, 66, 132, 198].map(formatAreaTick)).toEqual(['0', '20평', '40평', '60평 이상'])
  })
})

describe('지역 필터 요약', () => {
  it.each([
    ['41', '경기'],
    ['41110', '경기 수원시'],
    ['1111010100', '서울 종로구 청운동'],
    ['41102', '지역 선택됨'],
    ['99999', '지역 선택됨'],
  ])('지역명 응답 전에도 %s 코드를 노출하지 않는다', (regionCode, label) => {
    expect(topicSummary({ regionCode }, 'region', null)).toBe(label)
  })

  it('조회한 지역명이 있으면 최신 이름을 사용한다', () => {
    expect(topicSummary({ regionCode: '41110' }, 'region', '경기도 수원시'))
      .toBe('경기 수원시')
  })
})
