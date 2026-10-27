import { expect, it } from 'vitest'
import type { ReviewField } from './reviewTargets'
import type { QualitySource } from './qualityComparisonApi'
import { qualitySourceValue, qualityComparisonStatus } from './qualityComparisonValues'

const source: QualitySource = { category: 'MYHOME_COMPLEX', sourceKey: 'key', automatic: false, evidenceNote: '범위 확인', rows: [] }
const field: ReviewField = { name: 'householdCount', label: '주택형 세대수', type: 'number', unit: '세대', scope: '해당 주택형' }
it('단지 전체 세대수를 주택형 세대수로 옮기지 않고 0인 주택형 세대수는 보존한다', () => {
  expect(qualitySourceValue(field, source, { hshld_co: 120 }).value).toBeUndefined()
  expect(qualitySourceValue(field, { ...source, category: 'LH_LEASE_CATALOG' }, { total_unit_count: '0' }).value).toBe(0)
})
it('금액의 단위와 최소 금액 범위를 추정해 원 단위로 자동 변환하지 않는다', () => {
  const amount = qualitySourceValue({ ...field, name: 'rentalDeposit', type: undefined, money: true },
    { ...source, category: 'LH_ANNOUNCEMENT_SUPPLY' }, { deposit_text: '999999999999999999' })
  expect(amount.raw).toContain('999999999999999999')
  expect(amount.value).toBeUndefined()
})
it('실제 날짜와 소수 네 자리 면적만 변환하고 잘못된 값은 근거로만 표시한다', () => {
  const date = { ...field, name: 'applicationStartDate', type: 'date' as const }
  const announcement = { ...source, category: 'MYHOME_ANNOUNCEMENT' as const }
  expect(qualitySourceValue(date, announcement, { begin_de: '20260230' }).value).toBeUndefined()
  expect(qualitySourceValue(date, announcement, { begin_de: '20261016' }).value).toBe('2026-10-16')
  expect(qualitySourceValue({ ...field, name: 'exclusiveArea' }, source, { suply_prvuse_ar: '26.1234' }).value).toBe(26.1234)
})

it('미지원·값 없음·직접 대조를 구분하고 금액을 같은 값으로 단정하지 않는다', () => {
  expect(qualitySourceValue(field, source, { hshld_co: 120 }).availability).toBe('unsupported')
  const lh = { ...source, category: 'LH_LEASE_CATALOG' as const }
  expect(qualitySourceValue(field, lh, {}).availability).toBe('missing')
  expect(qualityComparisonStatus(field, [lh], 10)).toBe('unavailable')
  const money = { ...field, name: 'rentalDeposit', type: undefined, money: true }
  const amount = { ...source, category: 'LH_ANNOUNCEMENT_SUPPLY' as const, rows: [{ id: 1, name: '행', collectedAt: null, raw: { deposit_text: '1000000' } }] }
  expect(qualityComparisonStatus(money, [amount], '1000000')).toBe('manual')
})
