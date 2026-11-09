import type { InitialRegistrationStatus } from './api'

const labels: Record<InitialRegistrationStatus, string> = {
  COMPLETED: '등록 완료', FAILED: '등록 실패', EXISTING: '기존 단지 건너뜀',
  INFORMATION_REQUIRED: '정보 보완 필요', LOCATION_REQUIRED: '좌표 준비 필요', READY: '등록 가능',
}

export function InitialRegistrationReport({ report }: { report: unknown }) {
  if (typeof report !== 'object' || report === null) return null
  const value = report as Record<string, unknown>
  if (typeof value.counts !== 'object' || value.counts === null) return null
  const counts = value.counts as Record<string, unknown>
  const entries: [string, unknown][] = [
    ['등록 대상 단지', value.targetCount], ['고유 원천 주소', value.uniqueAddressCount],
    ...Object.entries(labels).map(([status, label]): [string, unknown] => [label, counts[status]]),
  ]
  if (!entries.every(([, count]) => typeof count === 'number' && Number.isSafeInteger(count) && count >= 0)) {
    return <p role="alert">등록 결과 보고서의 건수를 확인하지 못했습니다.</p>
  }
  return <dl className="pipeline-report">{entries.map(([label, count]) => (
    <div key={label}><dt>{label}</dt><dd>{String(count)}</dd></div>
  ))}</dl>
}

export function InitialRegistrationResultNotice({ onShowRegistration }: { onShowRegistration?: () => void }) {
  return <p>
    실패·보완 대상은 신규 단지 등록의 상태 필터에서 확인하세요. 목록은 전체 대상의 현재 상태입니다.{' '}
    <a href="/admin/ingest/complexes#complex-registration" onClick={onShowRegistration}>현재 등록 현황 보기</a>
  </p>
}
