import type { ManagementSummary } from './managementContract'

export function ManagementStatus({ summary }: { summary: ManagementSummary }) {
  const label = summary.deleted ? '휴지통'
    : summary.reviewRequired ? '원천 변경 확인 필요'
      : summary.modified ? '관리자 수정 보호' : '등록됨'

  return (
    <span className="admin-status" title="데이터의 보관·관리 상태입니다. 입주 여부나 공고 모집 상태를 뜻하지 않습니다.">
      {label}
    </span>
  )
}
