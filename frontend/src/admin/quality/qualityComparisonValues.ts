import type { ReviewField } from './reviewTargets'
import type { JsonValue } from '../management/managementContract'
import type { QualitySource } from './qualityComparisonApi'

const fields: Record<string, Record<string, string>> = {
  MYHOME_COMPLEX: { name: 'style_nm', exclusiveArea: 'suply_prvuse_ar' },
  LH_LEASE_CATALOG: { exclusiveArea: 'exclusive_area', householdCount: 'total_unit_count' },
  MYHOME_ANNOUNCEMENT: { name: 'pblanc_nm', postedDate: 'rcrit_pblanc_de', applicationStartDate: 'begin_de',
    applicationEndDate: 'end_de', winnerAnnouncementDate: 'przwner_presnatn_de',
    supplyPnu: 'pnu', totalSupplyHouseholdCount: 'sum_suply_co',
    rentalDeposit: 'rent_gtn', monthlyRent: 'mt_rntchrg' },
  LH_ANNOUNCEMENT_CATALOG: { name: 'PAN_NM' },
  LH_ANNOUNCEMENT_SUPPLY: {
    exclusiveArea: 'exclusive_area', supplyHouseholdCount: 'supplied_unit_count', rentalDeposit: 'deposit_text', monthlyRent: 'monthly_rent_text' },
  SH_ANNOUNCEMENT: { name: 'title' },
}
export function qualitySourceValue(field: ReviewField, source: QualitySource, raw: Record<string, JsonValue>) {
  const key = fields[source.category]?.[field.name]
  if (!key) return { raw: '자동 비교 미지원', value: undefined, availability: 'unsupported' as const }
  const original = raw[key]
  if (original === undefined || original === null || String(original).trim() === '') return { raw: '원천에 값 없음', value: undefined, availability: 'missing' as const }
  const text = String(original).trim()
  // 금액 원천은 최소 금액·문자열 단위와 공급 범위가 다르므로 직접 확인해 원 단위로 입력한다.
  if (field.money) return { raw: `${text} · 원천 단위·적용 범위 확인 필요`, value: undefined, availability: 'present' as const }
  if (field.options || field.boolean || field.name.includes('.')) return { raw: text, value: undefined, availability: 'present' as const }
  if (field.type === 'date') {
    const date = /^\d{8}$/.test(text) ? `${text.slice(0, 4)}-${text.slice(4, 6)}-${text.slice(6)}` : text
    const valid = /^\d{4}-\d{2}-\d{2}$/.test(date) && Number.isFinite(Date.parse(date)) && new Date(date).toISOString().slice(0, 10) === date
    return { raw: text, value: valid ? date : undefined, availability: 'present' as const }
  }
  if (field.type === 'number') {
    const number = /^(0|[1-9]\d*)(?:\.\d{1,4})?$/.test(text) ? Number(text) : NaN
    const valid = Number.isFinite(number) && number >= 0 && (field.name === 'exclusiveArea' ? number <= 999999.9999 : Number.isSafeInteger(number) && number <= 2147483647)
    return { raw: text, value: valid ? number : undefined, availability: 'present' as const }
  }
  return { raw: text, value: text.length <= 255 ? text : undefined, availability: 'present' as const }
}

export function qualityComparisonStatus(field: ReviewField, sources: QualitySource[], current: JsonValue | undefined): 'different' | 'manual' | 'same' | 'unavailable' {
  const values = sources.flatMap(source => source.rows.map(row => qualitySourceValue(field, source, row.raw)))
  if (values.some(item => item.value !== undefined && String(item.value) !== String(current))) return 'different'
  if (values.some(item => item.availability === 'present' && item.value === undefined)) return 'manual'
  if (values.some(item => item.value !== undefined)) return 'same'
  return 'unavailable'
}
