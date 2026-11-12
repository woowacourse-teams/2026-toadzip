import {
  CHECK_STATUS_ORDER,
  TYPE_STATUS_ORDER,
  type CheckView,
  type EvidenceRef,
  type GuidanceKind,
  type ListConditionResult,
  type ProfileField,
  type TypeStatus,
  type TypeView,
} from '../../user/conditions/conditionsModel'

export function sortTypes(types: readonly TypeView[]): TypeView[] {
  return types.map((type, index) => ({ type, index }))
    .sort((left, right) => TYPE_STATUS_ORDER[left.type.status] - TYPE_STATUS_ORDER[right.type.status] || left.index - right.index)
    .map(({ type }) => type)
}

export function sortChecks(checks: readonly CheckView[]): CheckView[] {
  return checks.map((check, index) => ({ check, index }))
    .sort((left, right) => CHECK_STATUS_ORDER[left.check.status] - CHECK_STATUS_ORDER[right.check.status] || left.index - right.index)
    .map(({ check }) => check)
}

export function missingFieldsOf(type: TypeView): ProfileField[] {
  return [...new Set(type.checks.filter((check) => check.status === 'INPUT_NEEDED').flatMap((check) => check.fields))]
}

/**
 * 화면에 보일 유형 상태. API의 UNDETERMINED를 입력이 남았는지(NEED), 별도 신청자격만 남았는지(QUALIFICATION),
 * 공고문에서 확인할 항목이 남았는지(CHECK)로 나눈다. 판정 띠와 탭·알림 문구가 이 하나를 같이 쓴다.
 */
type DisplayState = 'MATCHED' | 'NEED' | 'QUALIFICATION' | 'CHECK' | 'NOT' | 'NONE'
function displayState(type: TypeView, missingFields: readonly ProfileField[]): DisplayState {
  switch (type.status) {
    case 'ELIGIBLE': return 'MATCHED'
    case 'INELIGIBLE': return 'NOT'
    case 'NOT_EVALUATED': return 'NONE'
    case 'UNDETERMINED':
      if (missingFields.length) return 'NEED'
      return type.qualifications?.length && !type.checks.some((check) => check.status === 'DIRECT_CHECK_REQUIRED') ? 'QUALIFICATION' : 'CHECK'
  }
}

const VERDICT: Record<DisplayState, { title: string; message: string }> = {
  MATCHED: { title: '기본 조건을 충족해요', message: '좋은 집과 인연이 닿길 응원할게요.' },
  NEED: { title: '입력하면 확인할 수 있어요', message: '' },
  QUALIFICATION: { title: '공통 조건은 맞아요', message: '위의 별도 신청자격도 꼭 확인해 주세요.' },
  CHECK: { title: '직접 확인할 항목이 남았어요', message: '거의 다 왔어요. 공고문에서 한 가지만 더 확인해 보세요.' },
  NOT: { title: '맞지 않는 조건이 있어요', message: '이번 유형은 아쉬워요. 다른 유형도 함께 살펴봐요.' },
  NONE: { title: '비교한 조건이 없어요', message: '이번 모집 여부를 공고문에서 확인해 주세요.' },
}

/** 유형 판정 띠의 제목과 한 줄. 상태는 API의 경로 결과를 그대로 쓰고 공고 전체 결과를 다시 추론하지 않는다. */
export function verdictCopy(type: TypeView): { title: string; message: string; showFix: boolean; bulkInput: boolean; missingFields: ProfileField[] } {
  const missingFields = type.missingInputs ? [...type.missingInputs] : missingFieldsOf(type)
  const state = displayState(type, missingFields)
  const copy = state === 'NEED' ? { ...VERDICT.NEED, message: `거의 다 왔어요. ${missingFields.length}개만 채우면 바로 알려드릴게요.` } : VERDICT[state]
  return { ...copy, showFix: state === 'NOT', bulkInput: state === 'NEED' && missingFields.length > 1, missingFields }
}

const STATUS_TEXT: Record<TypeStatus, string> = {
  ELIGIBLE: '충족', UNDETERMINED: '확인 필요', INELIGIBLE: '미충족', NOT_EVALUATED: '판정 없음',
}
const STATE_TEXT: Record<DisplayState, string> = {
  MATCHED: '충족', NEED: '입력 필요', QUALIFICATION: '공통 충족', CHECK: '확인 필요', NOT: '미충족', NONE: '비교 없음',
}

/** 탭·저장 알림의 유형 상태. 항목을 알면 판정 띠와 같은 기준으로, 목록처럼 상태만 알면 상태로 부른다. */
export function typeStatusText(type: TypeView | Pick<TypeView, 'status'>): string {
  if (!('checks' in type)) return STATUS_TEXT[type.status]
  return STATE_TEXT[displayState(type, type.missingInputs ? [...type.missingInputs] : missingFieldsOf(type))]
}

export function listResultText(result: ListConditionResult): string | null {
  if (result.availability === 'CANCELLED') return '취소된 모집이에요'
  if (result.availability === 'NOT_PUBLISHED') return '공개되지 않은 공고예요'
  if (result.availability === 'NOT_STRUCTURED') return '자격 비교 준비 중'
  if (result.freshness === 'RECHECKING') return '현재 정보로 다시 확인 중'
  if (result.freshness === 'NOT_AVAILABLE') return '현재 신청자격 판정 대상이 아니에요'
  if (!result.best) return null
  if (result.best.status === 'ELIGIBLE') return `${result.best.label} 지원 가능`
  return `${result.best.label} ${typeStatusText(result.best)}`
}

const MISSING_REASON_TEXT = { LATER: '나중에 입력하기로 한 항목', FAMILY_SKIPPED: '가족 정보를 건너뛴 항목', UNANSWERED: '아직 입력하지 않은 항목' } as const

/** 입력이 필요한 항목의 안내. 다른 조건이 이미 맞지 않으면 입력해도 결과가 같으므로 안내하지 않는다. */
export function missingNote(check: CheckView, typeStatus: TypeStatus): string | null {
  if (check.status !== 'INPUT_NEEDED' || typeStatus === 'INELIGIBLE') return null
  return `${MISSING_REASON_TEXT[check.missingReason ?? 'UNANSWERED']} · 입력하면 바로 확인돼요`
}

/** 근거 버튼 문구. PDF 추출로 만든 근거의 위치는 추출 데이터 경로(eligibility.common.requirements[1])라 보이지 않는다. */
export function evidenceLabel(evidence: EvidenceRef): string {
  const readable = evidence.location && !/^[A-Za-z_]+[.[]/.test(evidence.location)
  return readable ? `${evidence.page}쪽 ${evidence.location}` : `${evidence.page}쪽`
}

export function typeChipRows(targets: readonly string[]): [string[], string[]] {
  if (targets.length <= 2) return [[...targets], []]
  const top = Math.floor(targets.length / 2)
  return [targets.slice(0, top), targets.slice(top)]
}

export const GUIDANCE_KIND_LABEL: Record<GuidanceKind, string> = {
  REQUIRED_QUALIFICATION: '필수 자격',
  APPLICATION_EXCLUSION: '신청 불가능한 특수 경우',
  REQUIRED_CHECK: '신청 전 확인',
  OTHER_ROUTE: '다른 신청 경로',
  PRIORITY_OPPORTUNITY: '우선공급',
  PROCEDURE: '신청 절차',
}

export const CHECK_STATUS_LABEL = { SATISFIED: '맞아요', NOT_SATISFIED: '맞지 않아요', INPUT_NEEDED: '정보 부족', DIRECT_CHECK_REQUIRED: '공고문 확인' } as const
