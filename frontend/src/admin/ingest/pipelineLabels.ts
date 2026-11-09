import type { DataPipelineType } from './api'

export const pipelineLabels: Record<DataPipelineType, string> = {
  COMPLEX_COLLECTION: '단지 수집',
  COMPLEX_REFINEMENT: '단지 정제',
  ANNOUNCEMENT_COLLECTION: '공고 수집',
  ANNOUNCEMENT_REFINEMENT: '공고 정제',
  COMPLEX_SYNC: '단지 수집·정제',
  ANNOUNCEMENT_SYNC: 'LH 공고 갱신',
  ANNOUNCEMENT_REGISTRATION: '공고 단건 등록',
  SH_ANNOUNCEMENT_COLLECTION: 'SH 공고 수집',
  GH_ANNOUNCEMENT_COLLECTION: 'GH 공고 수집',
}

// 단지 정제는 지원을 종료했다. 지난 실행 이력과 결과는 계속 보여 준다.
export const retiredPipelineTypes: readonly DataPipelineType[] = ['COMPLEX_REFINEMENT', 'COMPLEX_SYNC']
export const retiredComplexRefinementNotice = '지원 종료: 단지·주택형은 공고와 PDF 보정으로만 바뀝니다.'
// 마이홈 공고 단건 등록도 지원을 종료했다. 지난 단건 등록 실행 이력은 계속 보여 준다.
export const retiredAnnouncementRegistrationNotice = '마이홈 공고 단건 등록은 지원을 종료했습니다. 공고는 LH 공고 정제와 PDF 보정으로만 바뀝니다.'

import type { DataPipelineExecution } from './api'
export const pipelineStatusLabels: Record<DataPipelineExecution['status'], string> = {
  IDLE:'미실행', RUNNING:'실행 중', COMPLETED:'완료', COMPLETED_WARNINGS:'일부 실패',
  COMPLETED_WITH_SKIPS:'일부 건너뜀', FAILED:'실패', STOPPED:'중지됨',
}
