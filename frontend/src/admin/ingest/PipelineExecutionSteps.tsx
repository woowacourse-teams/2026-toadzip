import { useState } from 'react'
import { Link } from 'react-router'
import type { DataPipelineExecution, DataPipelineType } from './api'
import { DataPipelineProgress, PipelineReport } from './DataPipelineProgress'
import { StoredDataTable } from '../shared/StoredDataTable'
import { pipelineLabels, pipelineStatusLabels } from './pipelineLabels'
import styles from './PipelineExecutionSteps.module.css'
import { AnnouncementSupplyMatchingForm } from './AnnouncementSupplyMatchingForm'

const definitions = {
  COLLECT_MYHOME_COMPLEXES: '마이홈 단지 수집',
  COLLECT_LH_LEASE_CATALOG: 'LH 임대 카탈로그 수집',
  MAP_MYHOME_COMPLEXES: '마이홈 단지 정제',
  ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS: 'LH 주택형 세대수 보강',
  COLLECT_MYHOME_ANNOUNCEMENTS: '마이홈 공고 수집',
  COLLECT_LH_ANNOUNCEMENT_CATALOG: 'LH 공고 목록 수집',
  COLLECT_LH_ANNOUNCEMENT_SUPPLIES: 'LH 공고 공급 원본 수집',
  COLLECT_LH_ANNOUNCEMENT_DETAILS: 'LH 공고 상세 원본 수집',
  MAP_MYHOME_ANNOUNCEMENTS: '마이홈 공고 정제',
  ENRICH_LH_ANNOUNCEMENTS: 'LH 공고 상세·공급 정보 보강',
} as const
type Step = keyof typeof definitions
const complexCollection: Step[] = ['COLLECT_MYHOME_COMPLEXES', 'COLLECT_LH_LEASE_CATALOG']
const complexRefinement: Step[] = ['MAP_MYHOME_COMPLEXES', 'ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS']
const announcementCollection: Step[] = ['COLLECT_MYHOME_ANNOUNCEMENTS', 'COLLECT_LH_ANNOUNCEMENT_CATALOG',
  'COLLECT_LH_ANNOUNCEMENT_SUPPLIES', 'COLLECT_LH_ANNOUNCEMENT_DETAILS']
const announcementRefinement: Step[] = ['MAP_MYHOME_ANNOUNCEMENTS', 'ENRICH_LH_ANNOUNCEMENTS']
const sequences: Record<DataPipelineType, Step[]> = {
  COMPLEX_COLLECTION: complexCollection, COMPLEX_REFINEMENT: complexRefinement,
  COMPLEX_SYNC: [...complexCollection, ...complexRefinement],
  ANNOUNCEMENT_COLLECTION: announcementCollection, ANNOUNCEMENT_REFINEMENT: announcementRefinement,
  ANNOUNCEMENT_SYNC: [...announcementCollection, ...announcementRefinement],
  ANNOUNCEMENT_REGISTRATION: ['COLLECT_MYHOME_ANNOUNCEMENTS', 'COLLECT_LH_ANNOUNCEMENT_SUPPLIES',
    'COLLECT_LH_ANNOUNCEMENT_DETAILS', 'MAP_MYHOME_ANNOUNCEMENTS'],
}
const states = {
  waiting: ['○', '대기'], running: ['●', '진행'], success: ['✓', '성공'],
  partial: ['!', '부분 실패'], failed: ['×', '실패'], skipped: ['−', '생략'],
  stopped: ['■', '중지'], unexecuted: ['○', '미실행'],
} as const

function stepState(execution: DataPipelineExecution, step: Step): keyof typeof states {
  const name = definitions[step]
  if (execution.partiallyFailedSteps.some(item => item.step === step || item.stepName === name)) return 'partial'
  if (execution.skippedSteps.some(item => item.stepName === name)) return 'skipped'
  if (execution.completedSteps.includes(name)) return 'success'
  if (execution.status === 'FAILED' && execution.failure?.stepName === name) return 'failed'
  if (execution.currentStepName === name && execution.status === 'RUNNING') return 'running'
  if (execution.currentStepName === name && execution.status === 'STOPPED') return 'stopped'
  return execution.status === 'RUNNING' ? 'waiting' : 'unexecuted'
}

export function PipelineExecutionSteps({ execution, onStop, stopping = false }: {
  execution: DataPipelineExecution; onStop?: () => void; stopping?: boolean
}) {
  const [selected, setSelected] = useState<Step | null>(null)
  const [matchingOpen, setMatchingOpen] = useState(false)
  const label = pipelineLabels[execution.type]
  const domain = execution.type.startsWith('COMPLEX_') ? 'complex' : 'announcement'
  const selectedName = selected ? definitions[selected] : null
  const skipped = execution.skippedSteps.find(item => item.stepName === selectedName)
  const partial = execution.partiallyFailedSteps.find(item => item.stepName === selectedName)
  const completed = execution.completedStepResults?.find(item => item.stepName === selectedName)
  const failure = execution.failure?.stepName === selectedName ? execution.failure : null
  const report = partial?.report ?? failure?.serverResponse ?? skipped?.serverResponse ?? completed?.report
  const category = selected?.startsWith('COLLECT_') ? 'collection' : selected === 'ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS'
    ? 'household' : selected === 'ENRICH_LH_ANNOUNCEMENTS' ? 'enrichment' : domain

  return <article className={styles.execution} aria-label="현재 작업 단계">
    <header className={styles.heading}><h3>{label}</h3>
      <span className={`pipeline-badge pipeline-${execution.status.toLowerCase()}`}>{pipelineStatusLabels[execution.status]}</span>
    </header>
    <p className="ingest-meta">시작: {formatTime(execution.startedAt)} · 종료: {formatTime(execution.finishedAt)}</p>
    <ol className={styles.steps} aria-label="실행 단계">
      {sequences[execution.type].map(step => {
        const state = stepState(execution, step)
        const name = execution.type === 'ANNOUNCEMENT_REGISTRATION' ? ({
          COLLECT_MYHOME_ANNOUNCEMENTS: '마이홈 원천 확보', COLLECT_LH_ANNOUNCEMENT_SUPPLIES: 'LH 공급 수집',
          COLLECT_LH_ANNOUNCEMENT_DETAILS: 'LH 상세 수집', MAP_MYHOME_ANNOUNCEMENTS: '정제·저장',
        } as Partial<Record<Step, string>>)[step] : definitions[step]
        const notApplicable = execution.skippedSteps.find(item => item.stepName === definitions[step])?.reason.startsWith('해당 없음')
        return <li key={step} className={styles[state]}>
          <button type="button" aria-pressed={selected === step} aria-label={`${name} ${notApplicable ? '해당 없음' : states[state][1]}`}
            aria-current={state === 'running' ? 'step' : undefined} onClick={() => setSelected(step)}>
            <span className={styles.marker} aria-hidden="true">{states[state][0]}</span>
            <span>{name}</span><small>{notApplicable ? '해당 없음' : states[state][1]}</small>
          </button>
        </li>
      })}
    </ol>
    {execution.status === 'RUNNING' && <DataPipelineProgress execution={execution} label={label} />}
    {execution.failure && <p role="alert" className="form-error">{execution.failure.message}</p>}
    {execution.type === 'ANNOUNCEMENT_REGISTRATION' && execution.status === 'FAILED'
      && execution.targetAnnouncementIdentifier && execution.failure?.stepName === definitions.MAP_MYHOME_ANNOUNCEMENTS
      && <div>
        <button type="button" aria-expanded={matchingOpen} onClick={() => setMatchingOpen(value => !value)}>단지·주택형 매칭</button>
        {matchingOpen && <AnnouncementSupplyMatchingForm identifier={execution.targetAnnouncementIdentifier} />}
      </div>}
    {execution.status === 'RUNNING' && onStop && <button type="button" disabled={stopping || execution.stopRequested || !execution.executionId}
      onClick={onStop}>{stopping || execution.stopRequested ? '중지 요청 중…' : `${label} 실행 중지`}</button>}
    {execution.stopRequested && execution.status === 'RUNNING' && <p role="status">현재 처리가 끝나면 중지합니다.</p>}
    {selected && <section className={styles.detail} aria-label="단계 상세">
      <header className={styles.heading}><h4>{selectedName}</h4><button type="button" onClick={() => setSelected(null)}>접기</button></header>
      <p>{failure?.message ?? skipped?.reason ?? (partial ? '일부 대상 처리에 실패했습니다. 상세 보고서와 실패 대상을 확인해 주세요.'
        : `단계 상태: ${states[stepState(execution, selected)][1]}`)}</p>
      {(partial || failure || skipped) && <Link to={`/admin/failures?domain=${domain}&category=${category}&executionId=${encodeURIComponent(execution.executionId ?? '')}`}>
        실패 대상 확인</Link>}
      {report != null && <details><summary>상세 보고서</summary><PipelineReport report={report} />
        <StoredDataTable data={report} label={`${selectedName} 실행 보고서`} /></details>}
    </section>}
    {execution.type === 'ANNOUNCEMENT_REGISTRATION' && execution.status.startsWith('COMPLETED')
      && <p role="status">공고 {execution.targetAnnouncementIdentifier} 등록이 완료되었습니다.{' '}
        <Link to="/admin/announcements">공고 관리로 이동</Link></p>}
  </article>
}

function formatTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('ko-KR') : '기록 없음'
}
