import { pipelineStepDefinitions as definitions, pipelineStepSequenceOf as sequenceOf, pipelineStepState as stepState, pipelineStepStates as states, type Step } from './pipelineSteps'
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import type { DataPipelineExecution } from './api'
import { DataPipelineProgress, PipelineReport } from './DataPipelineProgress'
import { StoredDataTable } from '../shared/StoredDataTable'
import { pipelineLabels, pipelineStatusLabels, retiredAnnouncementRegistrationNotice } from './pipelineLabels'
import styles from './PipelineExecutionSteps.module.css'
import { isRecord } from '../management/managementContract'
import { PipelineNextActions } from './PipelineNextActions'

export function PipelineExecutionSteps({ execution, onStop, stopping = false, onRefine, recoveryDisabled, focused = false, pending = false, children }: {
  execution: DataPipelineExecution; onStop?: () => void; stopping?: boolean
  onRefine?: () => void; recoveryDisabled?: boolean; focused?: boolean; pending?: boolean; children?: ReactNode
}) {
  const [selected, setSelected] = useState<Step | null>(null)
  const label = pipelineLabels[execution.type]
  const domain = execution.type.startsWith('COMPLEX_') ? 'complex' : 'announcement'
  const board = execution.type === 'SH_ANNOUNCEMENT_COLLECTION' ? 'SH' : execution.type === 'GH_ANNOUNCEMENT_COLLECTION' ? 'GH' : null
  // 합친 수집·정제 단계와 합치기 전의 정제 단계는 정제 실패 원천을 보고서에서 읽는다.
  const legacyBoardRefinement = selected === 'MAP_SH_ANNOUNCEMENTS' || selected === 'MAP_GH_ANNOUNCEMENTS'
  const boardRefinement = legacyBoardRefinement
    || selected === 'COLLECT_REFINE_SH_ANNOUNCEMENTS' || selected === 'COLLECT_REFINE_GH_ANNOUNCEMENTS'
  const selectedName = selected ? definitions[selected] : null
  const skipped = execution.skippedSteps.find(item => item.stepName === selectedName)
  const partial = execution.partiallyFailedSteps.find(item => item.stepName === selectedName)
  const completed = execution.completedStepResults?.find(item => item.stepName === selectedName)
  const failure = execution.failure?.stepName === selectedName ? execution.failure : null
  const report = partial?.report ?? failure?.serverResponse ?? skipped?.serverResponse ?? completed?.report
  const refinementFailures = refinementFailuresOf(report)
  const category = selected?.startsWith('COLLECT_') ? 'collection' : selected === 'ENRICH_LH_ANNOUNCEMENTS' ? 'enrichment' : domain

  return <article className={`${styles.execution} ${focused ? styles.focused : ''}`} aria-label="현재 작업 단계">
    <header className={styles.heading}><h3>{label}</h3>
      <span className={`pipeline-badge pipeline-${execution.status.toLowerCase()}`}>{pending ? '접수 확인 중' : execution.status === 'IDLE' && focused ? '실행 준비' : pipelineStatusLabels[execution.status]}</span>
    </header>
    {execution.status !== 'IDLE' && <p className="ingest-meta">시작: {formatTime(execution.startedAt)} · 종료: {formatTime(execution.finishedAt)}</p>}
    <div className={styles.body}>
    <ol className={styles.steps} aria-label="실행 단계">
      {sequenceOf(execution).map((step, index) => {
        const state = stepState(execution, step)
        const name = execution.type === 'ANNOUNCEMENT_REGISTRATION' ? ({
          COLLECT_MYHOME_ANNOUNCEMENTS: '마이홈 원천 확보', COLLECT_LH_ANNOUNCEMENT_SUPPLIES: 'LH 공급 수집',
          COLLECT_LH_ANNOUNCEMENT_DETAILS: 'LH 상세 수집', MAP_MYHOME_ANNOUNCEMENTS: '정제·저장',
        } as Partial<Record<Step, string>>)[step] : definitions[step]
        const notApplicable = execution.skippedSteps.find(item => item.stepName === definitions[step])?.reason.startsWith('해당 없음')
        return <li key={step} className={styles[state]}>
          <button type="button" aria-pressed={selected === step} aria-label={`${name} ${notApplicable ? '해당 없음' : states[state][1]}`}
            aria-current={state === 'running' ? 'step' : undefined} onClick={() => setSelected(step)}>
            <span className={styles.marker} aria-hidden="true">{focused ? index + 1 : states[state][0]}</span>
            <span>{name}</span><small>{notApplicable ? '해당 없음' : states[state][1]}</small>
          </button>
        </li>
      })}
    </ol>
    <div className={styles.content}>
    {children}
    {focused && execution.status === 'RUNNING' && <h4>{execution.currentStepName ?? '첫 단계 준비 중'}</h4>}
    {execution.status === 'RUNNING' && <DataPipelineProgress execution={execution} label={label} />}
    {execution.failure && <p role="alert" className="form-error">{execution.failure.message}</p>}
    {execution.type === 'ANNOUNCEMENT_REGISTRATION' && <p role="note">{retiredAnnouncementRegistrationNotice}</p>}
    {execution.status === 'RUNNING' && onStop && <button type="button" disabled={stopping || execution.stopRequested || !execution.executionId}
      onClick={onStop}>{stopping || execution.stopRequested ? '중지 요청 중…' : `${label} 실행 중지`}</button>}
    {execution.stopRequested && execution.status === 'RUNNING' && <p role="status">현재 처리가 끝나면 중지합니다. 이미 저장한 데이터는 유지됩니다.</p>}
    <PipelineNextActions execution={execution} onRefine={onRefine} disabled={recoveryDisabled} />
    {execution.status !== 'IDLE' && execution.status !== 'RUNNING' && execution.type !== 'ANNOUNCEMENT_REGISTRATION'
      && !board && <p><Link to={`/admin/failures?domain=${domain}&category=all&executionId=${encodeURIComponent(execution.executionId ?? '')}`}>
        실패 내역 확인</Link></p>}
    {selected && <section className={styles.detail} aria-label="단계 상세">
      <header className={styles.heading}><h4>{selectedName}</h4><button type="button" onClick={() => setSelected(null)}>접기</button></header>
      <p>{failure?.message ?? skipped?.reason ?? (partial ? '일부 대상 처리에 실패했습니다. 상세 보고서와 실패 대상을 확인해 주세요.'
        : `단계 상태: ${states[stepState(execution, selected)][1]}`)}</p>
      {(partial || failure || skipped) && !legacyBoardRefinement && <Link to={`/admin/failures?domain=${domain}&category=${category}&executionId=${encodeURIComponent(execution.executionId ?? '')}`}>
        실패 대상 확인</Link>}
      {boardRefinement && board && (partial || failure || skipped) && (legacyBoardRefinement || refinementFailures.length > 0) && <>
        <p>실패한 원천을 확인한 뒤 위의 {board} 공고 수집을 다시 실행하세요. 저장된 원천을 다시 정제하며 관리자 보정값은 유지합니다.</p>
        {refinementFailures.length > 0 && <ul aria-label="정제 실패 원천">
          {refinementFailures.filter(isRecord).filter(item => typeof item.sourceKey === 'string' && typeof item.detail === 'string').map((item, index) =>
            <li key={index}><Link to={`/admin/sources?category=${board}_ANNOUNCEMENT&keyword=${encodeURIComponent(String(item.sourceKey))}`}>
              {String(item.sourceKey)}</Link>: {String(item.detail)}</li>)}
        </ul>}
      </>}
      {report != null && <><PipelineReport report={report} /><details><summary>상세 보고서</summary>
        <StoredDataTable data={report} label={`${selectedName} 실행 보고서`} /></details></>}
    </section>}
    {execution.type === 'ANNOUNCEMENT_REGISTRATION' && execution.status.startsWith('COMPLETED')
      && <p role="status">공고 {execution.targetAnnouncementIdentifier} 등록이 완료되었습니다.{' '}
        <Link to="/admin/announcements">공고 관리로 이동</Link></p>}
    </div>
    </div>
  </article>
}

/** 합친 단계의 보고서는 정제 수치를 refinement 안에, 옛 정제 단계의 보고서는 바로 담고 있다. */
function refinementFailuresOf(report: unknown): unknown[] {
  if (!isRecord(report)) return []
  const refinement = isRecord(report.refinement) ? report.refinement : report
  return Array.isArray(refinement.failures) ? refinement.failures : []
}

function formatTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('ko-KR') : '기록 없음'
}
