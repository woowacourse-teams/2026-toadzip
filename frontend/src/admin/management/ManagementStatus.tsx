import type { ManagementSummary } from './managementContract'

const publication: Record<string, string> = { ORIGINAL: '원공고', CORRECTION: '정정공고', CANCELLATION: '취소공고' }

/** 공고 구분. 다른 칸처럼 글자로만 적고, 값이 없는 이전 응답은 아무것도 표시하지 않는다. */
export function PublicationType({ summary }: { summary: ManagementSummary }) {
  const type = summary.announcement?.publicationType
  if (!type || !publication[type]) return null
  return <span>{publication[type]}</span>
}

/** 관리자가 확인해야 할 상태만 보여 준다. 평소 상태(등록됨)는 표시하지 않는다. */
export function ManagementStatus({ summary, placeholder }: { summary: ManagementSummary; placeholder?: string }) {
  const badges: { label: string; tone: string; title: string }[] = []
  if (summary.deleted) badges.push({ label: '휴지통', tone: 'deleted', title: '휴지통에 있는 데이터입니다. 서비스에 보이지 않습니다.' })
  else if (summary.reviewRequired) badges.push({ label: '원천 변경 확인', tone: 'review', title: '수집 원천이 바뀌어 등록값과 다시 대조해야 합니다.' })
  else if (summary.modified) badges.push({ label: '관리자 수정', tone: 'modified', title: '관리자가 고친 값이 있어 다음 수집이 덮어쓰지 않습니다.' })
  if (!badges.length) return placeholder ? <span className="admin-status-empty" aria-label="특이 상태 없음">{placeholder}</span> : null
  return <span className="admin-status-list">{badges.map(badge => <span key={badge.label} className="admin-status" data-tone={badge.tone} title={badge.title}>{badge.label}</span>)}</span>
}
