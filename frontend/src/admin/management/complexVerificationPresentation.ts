import { labels } from './fields'
import type { VerificationField, VerificationSource, VerificationValues } from './complexVerificationContract'

export const verificationFieldLabels: Record<VerificationField, string> = {
  NAME: '단지명', ADDRESS: '주소', LOCATION: '지도 위치', AGENCY: '공급기관', RENTAL_TYPE: '공급유형', HOUSEHOLD_COUNT: '세대수',
}
export const verificationStatusLabels = {
  UNREVIEWED: '미검토', VERIFIED: '확인 완료', ON_HOLD: '확인 보류', STALE: '재검토 필요',
}
export function currentText(field: VerificationField, values: Partial<VerificationValues>): string {
  if (field === 'ADDRESS') return values.ADDRESS?.roadAddress ?? '미확인'
  if (field === 'LOCATION') return values.LOCATION?.map(value => value.toFixed(6)).join(', ') ?? '미확인'
  if (field === 'HOUSEHOLD_COUNT') return values.HOUSEHOLD_COUNT === undefined ? '미확인' : `${values.HOUSEHOLD_COUNT.toLocaleString('ko-KR')}세대`
  const value = field === 'RENTAL_TYPE' ? rentalLabel(values.RENTAL_TYPE ?? '') : values[field]
  return value ? labels[value] ?? value : '미확인'
}
export function sourceText(field: VerificationField, source: VerificationSource): string | null {
  if (field === 'NAME') return source.name
  if (field === 'ADDRESS') return source.roadAddress
  if (field === 'AGENCY') return source.provider
  if (field === 'RENTAL_TYPE') return source.rentalType
  if (field === 'HOUSEHOLD_COUNT') return source.householdCount === null ? null : `${source.householdCount.toLocaleString('ko-KR')}세대`
  return null
}
export function sourceMatches(field: VerificationField, values: VerificationValues, source: VerificationSource): boolean {
  if (field === 'ADDRESS') return source.roadAddress === values.ADDRESS.roadAddress && source.pnu === values.ADDRESS.pnu
  if (field === 'HOUSEHOLD_COUNT') return source.householdCount === values.HOUSEHOLD_COUNT
  if (field === 'AGENCY') {
    const code = ({ 한국토지주택공사: 'LH', 서울주택도시공사: 'SH', SH공사: 'SH', 경기주택도시공사: 'GH', 기타: 'ETC' } as Record<string, string>)[source.provider ?? '']
    if (source.provider?.trim().toUpperCase().startsWith('LH')) return agencyCode(values.AGENCY) === 'LH'
    return (code ?? source.provider) === agencyCode(values.AGENCY)
  }
  if (field === 'RENTAL_TYPE') {
    return source.rentalType !== null && rentalLabel(values.RENTAL_TYPE) === rentalLabel(source.rentalType)
  }
  return field === 'NAME' && source.name === values.NAME
}
function rentalLabel(value: string): string {
  if (value === '50년임대' || value === '50년공공임대') return '50년 공공임대'
  if (value === '5년임대') return '5년 공공임대'
  if (value === '10년임대') return '10년 공공임대'
  return labels[value] ?? value
}
function agencyCode(value: string) {
  return ({ 한국토지주택공사: 'LH', 서울주택도시공사: 'SH', 경기주택도시공사: 'GH', 기타: 'ETC' } as Record<string, string>)[value] ?? value
}
export function sameValue(left: unknown, right: unknown): boolean {
  if (Array.isArray(left) && Array.isArray(right)) return left.length === right.length && left.every((item, index) => sameValue(item, right[index]))
  if (left && right && typeof left === 'object' && typeof right === 'object') {
    const a = Object.entries(left)
    const b = Object.entries(right)
    return a.length === b.length && a.every(([key, value]) => sameValue(value, b.find(([name]) => name === key)?.[1]))
  }
  return left === right
}
export function safeEvidenceUrl(raw: string | null | undefined): string | null {
  if (!raw) return null
  try {
    const url = new URL(raw)
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) return null
    for (const key of [...url.searchParams.keys()]) if (key.toLowerCase() === 'servicekey') url.searchParams.delete(key)
    return url.href
  } catch { return null }
}
export function mapUrl(query: string): string {
  return `https://www.google.com/maps/search/?${new URLSearchParams({ api: '1', query })}`
}
export function reviewTime(value: string): string {
  return new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' })
}
