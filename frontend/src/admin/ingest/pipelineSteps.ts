import type { DataPipelineExecution, DataPipelineType } from './api'

const definitions = {
  COLLECT_MYHOME_COMPLEXES: '마이홈 단지 수집',
  COLLECT_LH_LEASE_CATALOG: 'LH 임대 카탈로그 수집',
  MAP_MYHOME_COMPLEXES: '마이홈 단지 정제',
  ENRICH_LH_HOUSING_TYPE_HOUSEHOLDS: 'LH 주택형 세대수 보강',
  COLLECT_MYHOME_ANNOUNCEMENTS: '마이홈 공고 수집',
  COLLECT_SH_ANNOUNCEMENTS: 'SH 공고 원천 수집',
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
  SH_ANNOUNCEMENT_COLLECTION: ['COLLECT_SH_ANNOUNCEMENTS'],
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
  return execution.status === 'RUNNING' || execution.status === 'IDLE' ? 'waiting' : 'unexecuted'
}


export { definitions as pipelineStepDefinitions, sequences as pipelineStepSequences, stepState as pipelineStepState, states as pipelineStepStates }
export type { Step }
