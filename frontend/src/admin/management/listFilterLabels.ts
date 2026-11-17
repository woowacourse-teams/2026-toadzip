export const applicationStatuses: Record<string, string> = {
  BEFORE_APPLICATION: '공고중', APPLYING: '접수중', CLOSED: '접수마감',
  CONDITIONAL: '조건부 접수', CANCELLED: '공고취소', UNKNOWN: '일정 미정',
}
/** 목록 필터에만 쓰는 접수 상태. 마감 전(OPEN)은 접수마감·공고취소가 아닌 공고로, 운영 홈 처리할 일과 같은 범위다. */
export const applicationStatusFilters: Record<string, string> = { OPEN: '마감 전', ...applicationStatuses }
/** 공고문 PDF 처리 단계. 서버가 저장하지 않고 계산하며 운영 홈 처리할 일과 공고 목록이 같은 기준을 쓴다. */
export const processingStages: Record<string, string> = {
  PDF_MISSING: 'PDF 없음', NOT_EXTRACTED: '추출 전', EXTRACTING: '추출 대기·처리 중', EXTRACTION_FAILED: '추출 실패',
  REVIEW_PENDING: '검토 대기', CONFIRMED: '확정', RECHECK_REQUIRED: '재확인 필요',
}
export const attentionReasons: Record<string, string> = {
  ANY: '확인 필요', SCHEDULE_MISSING: '일정 누락', SUPPLY_MISSING: '공급정보 누락', UNCONFIRMED: '미확정',
}
