import type { DataPipelineExecution } from './api'
export const pipelineStatusLabels: Record<DataPipelineExecution['status'], string> = {
  IDLE:'미실행', RUNNING:'실행 중', COMPLETED:'완료', COMPLETED_WARNINGS:'일부 실패',
  COMPLETED_WITH_SKIPS:'일부 건너뜀', FAILED:'실패', STOPPED:'중지됨',
}
