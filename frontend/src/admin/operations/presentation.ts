import type { OperationsQuery } from './useOperationsQuery'

const timestamp = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
export function operationTime(value: string | null | undefined) {
  return value && Number.isFinite(Date.parse(value)) ? timestamp.format(new Date(value)) : '시각 미확인'
}

export function operationCount(value: number | null | undefined, query: OperationsQuery<unknown>) {
  if (query.pending) return '조회 중'
  if (query.error) return '조회 실패'
  return value == null ? '미집계' : value.toLocaleString('ko-KR')
}

export function comparison(value: number | null | undefined, previous: number | null | undefined) {
  if (value == null || previous == null) return '비교 불가'
  if (value === previous) return '변화 없음'
  if (previous === 0) return `이전 기간 ${previous}건`
  return `${value > previous ? '↑' : '↓'} ${Math.abs(Math.round((value - previous) / previous * 100))}%`
}
