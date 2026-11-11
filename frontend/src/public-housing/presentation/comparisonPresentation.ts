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

/** API의 경로 결과를 표시하며 공고 전체 결과를 다시 추론하지 않는다. */
export function verdictCopy(type: TypeView): { title: string; message: string; showFix: boolean; missingFields: ProfileField[] } {
  const missingFields = type.missingInputs ? [...type.missingInputs] : missingFieldsOf(type)
  const base = { showFix: type.status === 'INELIGIBLE', missingFields }
  switch (type.status) {
    case 'ELIGIBLE': return { ...base, title: '지원할 수 있어요', message: type.message ?? '입력한 정보로 신청자격을 충족해요.' }
    case 'INELIGIBLE': return { ...base, title: `${type.label} 미충족`, message: type.message ?? '확인된 미충족 조건을 먼저 보여드려요.' }
    case 'UNDETERMINED': return { ...base,
      title: missingFields.length ? '입력하면 확인할 수 있어요' : '신청자격을 직접 확인해 주세요',
      message: type.message ?? (missingFields.length ? '아래 조건에 필요한 항목을 입력해 주세요.' : '이 공고가 요구하는 정확한 자격을 확인해 주세요.') }
    case 'NOT_EVALUATED': return { ...base, title: '신청자격을 판정하지 않았어요', message: '이번 모집 여부와 공고문을 확인해 주세요.' }
  }
}

const STATUS_TEXT: Record<TypeStatus, string> = {
  ELIGIBLE: '충족', UNDETERMINED: '확인 필요', INELIGIBLE: '미충족', NOT_EVALUATED: '판정 없음',
}

export function typeStatusText(type: Pick<TypeView, 'status'>): string { return STATUS_TEXT[type.status] }

export function listResultText(result: ListConditionResult): string | null {
  if (result.availability === 'CANCELLED') return '취소된 모집이에요'
  if (result.availability === 'NOT_PUBLISHED') return '공개되지 않은 공고예요'
  if (result.availability === 'NOT_STRUCTURED') return '신청자격 판정 준비 중'
  if (result.freshness === 'RECHECKING') return '현재 정보로 다시 확인 중'
  if (result.freshness === 'NOT_AVAILABLE') return '현재 신청자격 판정 대상이 아니에요'
  if (!result.best) return null
  if (result.best.status === 'ELIGIBLE') return `${result.best.label} 지원 가능`
  return `${result.best.label} ${typeStatusText(result.best)}`
}

const MISSING_REASON_TEXT = { LATER: '나중에 입력하기로 한 항목', FAMILY_SKIPPED: '가족 정보를 건너뛴 항목', UNANSWERED: '아직 입력하지 않은 항목' } as const

export function missingNote(check: CheckView, typeStatus: TypeStatus): { text: string; quiet: boolean } | null {
  if (check.status !== 'INPUT_NEEDED') return null
  if (typeStatus === 'INELIGIBLE') return { text: `입력하면 ${check.label} 조건도 확인해드릴게요.`, quiet: true }
  return { text: `${MISSING_REASON_TEXT[check.missingReason ?? 'UNANSWERED']} · 입력하면 이 조건을 확인해드릴게요`, quiet: false }
}

export function evidenceLabel(evidence: EvidenceRef): string {
  return evidence.location ? `${evidence.page}쪽 ${evidence.location}` : `${evidence.page}쪽`
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

export const CHECK_STATUS_LABEL = { SATISFIED: '맞아요', NOT_SATISFIED: '맞지 않아요', INPUT_NEEDED: '정보 부족', DIRECT_CHECK_REQUIRED: '직접 확인' } as const
