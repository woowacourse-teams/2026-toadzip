import { complexSections, labels, type Field } from './fields'
import { valueAt } from './fields'
import { type ComparisonSource } from './complexComparisonApi'
import { type JsonValue, type ManagementValues } from './managementContract'

export const comparisonFields = complexSections.flatMap(section => section.fields)
export const categoryLabels = { MYHOME_COMPLEX: '마이홈 단지', LH_LEASE_CATALOG: 'LH 임대 단지' }
const myHomeFields: Record<string, string> = { name: 'hsmp_nm', agencyCode: 'instt_nm', rentalType: 'suply_ty_nm', completionDate: 'compet_de',
  'address.roadAddress': 'rn_adres', 'address.pnu': 'pnu', 'address.provinceCode': 'brtc_code', 'address.cityCountyDistrictCode': 'signgu_code',
  totalHouseholdCount: 'hshld_co', totalParkingCount: 'parkng_co', heatingType: 'heat_mthd_detail_nm', buildingType: 'house_ty_nm', corridorType: 'buld_stle_nm', hasElevator: 'elvtr_instl_at_nm' }
const lhFields: Record<string, string> = { name: 'complex_label', rentalType: 'supply_type_name', totalHouseholdCount: 'complex_total_unit_count' }
const codes: Record<string, string> = { 한국토지주택공사: 'LH', 서울주택도시공사: 'SH', SH공사: 'SH', 경기주택도시공사: 'GH',
  행복주택: 'HAPPY_HOUSING', 국민임대: 'NATIONAL_RENTAL', 영구임대: 'PERMANENT_RENTAL', '5년임대': 'PUBLIC_RENTAL_5Y', '10년임대': 'PUBLIC_RENTAL_10Y',
  '50년임대': 'PUBLIC_RENTAL_50Y', '50년공공임대': 'PUBLIC_RENTAL_50Y', 통합공공임대: 'INTEGRATED_PUBLIC_RENTAL', 재개발임대: 'REDEVELOPMENT_RENTAL',
  장기전세: 'LONG_TERM_JEONSE', 개별난방: 'INDIVIDUAL', 중앙난방: 'CENTRAL', 지역난방: 'DISTRICT', 아파트: 'APARTMENT', 오피스텔: 'OFFICETEL',
  계단식: 'STAIR', 복도식: 'CORRIDOR', 혼합식: 'MIXED', 기타: 'ETC' }
export function comparisonText(value: JsonValue | undefined): string {
  if (value === null || value === undefined || value === '') return '미확인'
  if (typeof value === 'object') return JSON.stringify(value)
  return labels[String(value)] ?? String(value)
}
export function sourceValue(field: Field, source: ComparisonSource): { raw: string; value: JsonValue | undefined } {
  const key = (source.category === 'MYHOME_COMPLEX' ? myHomeFields : lhFields)[field.name]
  let rawValue = key && source.data ? source.data.raw[key] : undefined
  // 마이홈 signgu_code는 시·도 안의 3자리 번호라, 저장값과 같은 5자리 시·군·구 코드로 시·도 코드를 앞에 붙여 비교한다.
  const province = source.data?.raw.brtc_code
  if (field.name === 'address.cityCountyDistrictCode' && source.category === 'MYHOME_COMPLEX' && /^\d{3}$/.test(String(rawValue ?? '')) && /^\d{2}$/.test(String(province ?? ''))) {
    rawValue = `${String(province)}${String(rawValue)}`
  }
  if (rawValue === undefined || rawValue === null || rawValue === '') return { raw: '원천에 없음', value: undefined }
  const raw = comparisonText(rawValue)
  const text = String(rawValue).trim()
  if (field.boolean) {
    const boolean = ({ 설치: true, 미설치: false, 유: true, 무: false, Y: true, N: false, true: true, false: false } as Record<string, boolean>)[text]
    return { raw, value: boolean }
  }
  if (field.type === 'number') {
    const number = typeof rawValue === 'number' ? rawValue : /^\d+(?:\.\d+)?$/.test(text) ? Number(text) : NaN
    return { raw, value: Number.isSafeInteger(number) && number >= 0 && number <= 2147483647 ? number : undefined }
  }
  if (field.type === 'date') {
    const date = /^\d{8}$/.test(text) ? `${text.slice(0,4)}-${text.slice(4,6)}-${text.slice(6)}` : text
    return { raw, value: /^\d{4}-\d{2}-\d{2}$/.test(date) && Number.isFinite(Date.parse(date)) && new Date(date).toISOString().startsWith(date) ? date : undefined }
  }
  if (field.options) {
    const code = codes[text] ?? Object.keys(labels).find(code => labels[code] === text && field.options?.includes(code)) ?? text
    return { raw, value: field.options.includes(code) ? code : undefined }
  }
  return { raw, value: typeof rawValue === 'string' && rawValue.length <= 255 ? rawValue : undefined }
}
export function writeComparisonValue(current: ManagementValues, field: Field, text: string): ManagementValues {
  const next = structuredClone(current)
  const value = text === '' ? null : field.boolean ? text === 'true' : field.type === 'number' ? Number(text) : text
  const [parent, child] = field.name.split('.')
  if (child) {
    const original = next[parent]
    next[parent] = { ...(original && typeof original === 'object' && !Array.isArray(original) ? original : {}), [child]: value }
  } else next[parent] = value
  return next
}
export function changedComparisonFields(current: ManagementValues, draft: ManagementValues) {
  return comparisonFields.filter(field => JSON.stringify(valueAt(current, field.name)) !== JSON.stringify(valueAt(draft, field.name)))
}
