import type { DataPipelineExecution, DataPipelineType } from './api'

const definitions = {
  COLLECT_MYHOME_COMPLEXES: '마이홈 단지 수집',
  COLLECT_LH_LEASE_CATALOG: 'LH 임대 카탈로그 수집',
  MAP_MYHOME_COMPLEXES: '마이홈 단지 정제',
  ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS: 'LH 주택형 세대수 보강',
  COLLECT_SH_ANNOUNCEMENTS: 'SH 공고 원천 수집',
  COLLECT_GH_ANNOUNCEMENTS: 'GH 공고 원천 수집',
  COLLECT_LH_ANNOUNCEMENT_CATALOG: 'LH 공고 목록 수집',
  COLLECT_LH_ANNOUNCEMENT_SUPPLIES: 'LH 공고 공급 원본 수집',
  COLLECT_LH_ANNOUNCEMENT_DETAILS: 'LH 공고 상세 원본 수집',
  MAP_LH_ANNOUNCEMENTS: 'LH 공고 정제',
  // 지원 종료한 단계다. 지난 실행 이력의 단계 이름을 읽도록 남긴다.
  COLLECT_MYHOME_ANNOUNCEMENTS: '마이홈 공고 수집',
  MAP_MYHOME_ANNOUNCEMENTS: '마이홈 공고 정제',
  ENRICH_LH_ANNOUNCEMENTS: 'LH 공고 상세·공급 정보 보강',
} as const
type Step = keyof typeof definitions
const complexCollection: Step[] = ['COLLECT_MYHOME_COMPLEXES', 'COLLECT_LH_LEASE_CATALOG']
const complexRefinement: Step[] = ['MAP_MYHOME_COMPLEXES', 'ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS']
const announcementCollection: Step[] = ['COLLECT_LH_ANNOUNCEMENT_CATALOG',
  'COLLECT_LH_ANNOUNCEMENT_SUPPLIES', 'COLLECT_LH_ANNOUNCEMENT_DETAILS']
const announcementRefinement: Step[] = ['MAP_LH_ANNOUNCEMENTS']
const sequences: Record<DataPipelineType, Step[]> = {
  COMPLEX_COLLECTION: complexCollection, COMPLEX_REFINEMENT: complexRefinement,
  COMPLEX_SYNC: [...complexCollection, ...complexRefinement],
  ANNOUNCEMENT_COLLECTION: announcementCollection, ANNOUNCEMENT_REFINEMENT: announcementRefinement,
  ANNOUNCEMENT_SYNC: [...announcementCollection, ...announcementRefinement],
  SH_ANNOUNCEMENT_COLLECTION: ['COLLECT_SH_ANNOUNCEMENTS'],
  GH_ANNOUNCEMENT_COLLECTION: ['COLLECT_GH_ANNOUNCEMENTS'],
  // 지원 종료한 마이홈 공고 단건 등록이다. 지난 실행 이력을 보여 주려고 단계 구성을 남긴다.
  ANNOUNCEMENT_REGISTRATION: ['COLLECT_MYHOME_ANNOUNCEMENTS', 'COLLECT_LH_ANNOUNCEMENT_SUPPLIES',
    'COLLECT_LH_ANNOUNCEMENT_DETAILS', 'MAP_MYHOME_ANNOUNCEMENTS'],
}
// 공고 정제가 LH 공고 정제 한 단계로 바뀌기 전의 실행 이력은 기록된 옛 정제 단계로 보여 준다.
const legacyAnnouncementRefinement: Step[] = ['MAP_MYHOME_ANNOUNCEMENTS', 'ENRICH_LH_ANNOUNCEMENTS']
// 공고 수집에서 마이홈 공고 수집을 빼기 전의 실행 이력은 그 단계를 맨 앞에 보여 준다.
const legacyAnnouncementCollection: Step = 'COLLECT_MYHOME_ANNOUNCEMENTS'
function sequenceOf(execution: DataPipelineExecution): Step[] {
  const steps = sequences[execution.type]
  if (!steps.includes('COLLECT_LH_ANNOUNCEMENT_CATALOG') && !steps.includes('MAP_LH_ANNOUNCEMENTS')) return steps
  const recorded = new Set([...execution.completedSteps, execution.currentStepName, execution.failure?.stepName,
    ...execution.skippedSteps.map(item => item.stepName),
    ...[...execution.partiallyFailedSteps, ...(execution.completedStepResults ?? [])].flatMap(item => [item.step, item.stepName])])
  const wasRecorded = (step: Step) => recorded.has(step) || recorded.has(definitions[step])
  const collection = steps.includes('COLLECT_LH_ANNOUNCEMENT_CATALOG') && wasRecorded(legacyAnnouncementCollection)
    ? [legacyAnnouncementCollection, ...steps] : steps
  if (!steps.includes('MAP_LH_ANNOUNCEMENTS') || !legacyAnnouncementRefinement.some(wasRecorded)) return collection
  return [...collection.filter(step => step !== 'MAP_LH_ANNOUNCEMENTS'), ...legacyAnnouncementRefinement]
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
  return execution.status === 'RUNNING' || execution.status === 'IDLE' ? 'waiting' : 'unexecuted'
}


export { definitions as pipelineStepDefinitions, sequences as pipelineStepSequences, sequenceOf as pipelineStepSequenceOf, stepState as pipelineStepState, states as pipelineStepStates }
export type { Step }
