import type { DataPipelineType } from './api'

export const pipelineLabels: Record<DataPipelineType, string> = {
  COMPLEX_COLLECTION: '단지 수집',
  COMPLEX_REFINEMENT: '단지 정제',
  ANNOUNCEMENT_COLLECTION: '공고 수집',
  ANNOUNCEMENT_REFINEMENT: '공고 정제',
  COMPLEX_SYNC: '단지 수집·정제',
  ANNOUNCEMENT_SYNC: '공고 수집·정제',
  ANNOUNCEMENT_REGISTRATION: '공고 단건 등록',
  SH_ANNOUNCEMENT_COLLECTION: 'SH 공고 수집',
}

import type { DataPipelineExecution } from './api'
export const pipelineStatusLabels: Record<DataPipelineExecution['status'], string> = {
  IDLE:'미실행', RUNNING:'실행 중', COMPLETED:'완료', COMPLETED_WARNINGS:'일부 실패',
  COMPLETED_WITH_SKIPS:'일부 건너뜀', FAILED:'실패', STOPPED:'중지됨',
}
