export const applicationStatuses: Record<string, string> = {
  BEFORE_APPLICATION: '공고중', APPLYING: '접수중', CLOSED: '접수마감',
  CONDITIONAL: '조건부 접수', CANCELLED: '공고취소', UNKNOWN: '일정 미정',
}
export const attentionReasons: Record<string, string> = {
  ANY: '확인 필요', SCHEDULE_MISSING: '일정 누락', SUPPLY_MISSING: '공급정보 누락', UNCONFIRMED: '미확정',
}
