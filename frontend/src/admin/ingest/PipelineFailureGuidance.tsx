import type { DataPipelineExecution, DataPipelineFailureCause } from './api'

const guidance: Record<DataPipelineFailureCause, string> = {
  TRANSIENT: '외부 기관 응답 문제나 서버 중단으로 멈췄습니다. 잠시 뒤 다시 실행하면 이어서 받습니다.',
  PARTIAL: '일부 대상만 실패했습니다. 실패 대상에서 원인을 확인한 뒤 다시 실행하세요.',
  ALL_TARGETS_FAILED: '대상이 모두 실패했습니다. 기관 응답 형식이 바뀌었을 수 있어 다시 실행해도 같을 수 있습니다. 실패 대상에서 원인을 확인하세요.',
  INTERNAL: '서버 내부 오류입니다. 다시 실행해도 같으면 아래 실행 ID를 개발자에게 전달해 주세요.',
}

/** 실패한 실행을 다시 실행하면 되는지와 원인 요약, 서버 로그를 찾을 실행 ID를 보인다. 원인을 남기기 전의 실패는 보이지 않는다. */
export function PipelineFailureGuidance({ execution }: { execution: DataPipelineExecution }) {
  const cause = execution.failure?.cause
  if (execution.status !== 'FAILED' || !cause) return null
  return <div role="note" aria-label="실패 원인 안내" className="ingest-meta">
    <p>{guidance[cause]}</p>
    {execution.failure?.detail && <p>원인: {execution.failure.detail}</p>}
    {execution.executionId && <p>실행 ID <code>{execution.executionId}</code></p>}
  </div>
}
