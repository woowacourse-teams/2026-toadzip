import { useEffect, useState } from 'react'
import type { DataPipelineExecution, DataPipelineWorkProgress } from './api'

export function DataPipelineProgress({ execution, label }: { execution: DataPipelineExecution, label: string }) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    if (execution.status !== 'RUNNING') return
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [execution.status])

  const settledNames = new Set([
    ...execution.completedSteps,
    ...execution.skippedSteps.map((step) => step.stepName),
    ...execution.partiallyFailedSteps.map((step) => step.stepName),
  ])
  const settled = Math.min(settledNames.size, execution.totalStepCount)
  const started = execution.startedAt ? Date.parse(execution.startedAt) : NaN
  const end = execution.finishedAt ? Date.parse(execution.finishedAt) : now
  const seconds = Math.max(0, Math.floor((end - started) / 1_000))
  const isCollection = execution.type.endsWith('_COLLECTION')
  const hasRequestCount = execution.status === 'RUNNING' || execution.lastProgressAt
    || (execution.externalRequestCount ?? 0) > 0
  const work = execution.workProgress
  const active = execution.status === 'RUNNING' && !execution.stopRequested
  return (
    <div className="pipeline-progress">
      <div className="pipeline-progress-caption">
        <span>단계 처리 {settled}/{execution.totalStepCount}</span>
        {Number.isFinite(seconds) ? <span>경과 {Math.floor(seconds / 60)}분 {seconds % 60}초</span> : null}
      </div>
      {work ? <>
        <div className="pipeline-work-caption"><strong>{work.label}</strong><span>
          {work.completedCount.toLocaleString()} / {work.totalCount >= 0 ? work.totalCount.toLocaleString() : '?'} {work.unit}
        </span></div>
        {work.totalCount > 0 ? <progress aria-label={`${label} 수집 대상 진행률`}
          value={Math.min(work.completedCount, work.totalCount)} max={work.totalCount} /> : null}
        {active ? <div className="pipeline-eta"><span>예상 남은 시간</span><strong>{remainingTime(work, now)}</strong></div> : null}
        <p className="ingest-meta">위 수집 범위의 실제 처리 속도로 추정합니다. 이후 작업·재시도에 따라 전체 종료 시각은 달라집니다.</p>
      </> : active && isCollection ? <p className="pipeline-eta">예상 남은 시간 · 수집 대상 확인 중</p> : null}
      {isCollection && hasRequestCount ? <p>외부 요청 처리 <strong>{(execution.externalRequestCount ?? 0).toLocaleString()}회</strong> <small>(실패·재시도 포함)</small></p> : null}
      {isCollection && !hasRequestCount ? <p className="ingest-meta">외부 요청 횟수 기록이 없습니다.</p> : null}
      {execution.lastRequestDescription ? <p className="pipeline-request"><span>최근 요청</span>{execution.lastRequestDescription}</p> : null}
      {execution.lastProgressAt ? <p className="ingest-meta">최근 활동 {new Date(execution.lastProgressAt).toLocaleTimeString('ko-KR', { hour12: false })}</p> : null}
      {execution.executionId ? <details><summary>실행 정보</summary><code>{execution.executionId}</code></details> : null}
    </div>
  )
}

function remainingTime(work: DataPipelineWorkProgress, now: number): string {
  if (work.totalCount < 0) return '전체 대상 확인 중'
  if (work.completedCount >= work.totalCount) return '수집 처리 완료 · 다음 작업 준비 중'
  if (work.completedCount < 2) return '처리 속도 계산 중'
  const elapsed = Math.max(0, (now - Date.parse(work.startedAt)) / 1_000)
  const sampledSeconds = Math.max(0, (Date.parse(work.updatedAt) - Date.parse(work.startedAt)) / 1_000)
  const idleSeconds = (now - Date.parse(work.updatedAt)) / 1_000
  if (idleSeconds > Math.max(60, sampledSeconds / work.completedCount * 3)) return '응답 지연 · 재계산 대기'
  if (elapsed <= 0) return '처리 속도 계산 중'
  const seconds = Math.ceil(elapsed / work.completedCount * (work.totalCount - work.completedCount))
  if (seconds < 60) return `약 ${Math.max(1, seconds)}초`
  if (seconds < 3_600) return `약 ${Math.ceil(seconds / 60)}분`
  return `약 ${Math.floor(seconds / 3_600)}시간 ${Math.ceil(seconds % 3_600 / 60)}분`
}

export function PipelineReport({ report }: { report: unknown }) {
  if (typeof report !== 'object' || report === null) return null
  const labels: Record<string, string> = {
    createdComplexCount: '신규 단지', updatedComplexCount: '갱신 단지', unchangedComplexCount: '변경 없는 단지',
    createdHousingTypeCount: '신규 주택형', createdAnnouncementCount: '신규 공고', updatedAnnouncementCount: '갱신 공고',
    unchangedAnnouncementCount: '변경 없는 공고', createdSupplyRowCount: '신규 공급행', updatedSupplyRowCount: '갱신 공급행',
    failedSourceCount: '실패한 원천', operationalFailedSourceRowCount: '운영 오류 행',
    storedRowCount: '저장한 행', failedRequestCount: '실패 요청', externalApiCallCount: 'API 호출',
    skippedRequestCount: '건너뛴 요청', rateLimitedRequestCount: '호출 제한',
    sourceRowCount: '원천 행', failedSourceRowCount: '실패한 행', mappedComplexCount: '정제한 단지',
    sourceComplexCount: '원천 단지', matchedComplexCount: '연결한 단지', failedSourceComplexCount: '실패한 단지',
    updatedHousingTypeCount: '갱신한 주택형', mappedAnnouncementCount: '정제한 공고',
    failedSourceAnnouncementCount: '실패한 공고', enrichedAnnouncementCount: '보강한 공고',
    unmatchedHousingTypeCount: '연결되지 않은 주택형',
  }
  const entries = Object.entries(report).filter(([key, value]) => key in labels && typeof value === 'number')
  if (entries.length === 0) return null
  return <dl className="pipeline-report">{entries.map(([key, value]) => (
    <div key={key}><dt>{labels[key]}</dt><dd>{String(value)}</dd></div>
  ))}</dl>
}
