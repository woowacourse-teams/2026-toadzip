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

/** 판정 띠 제목과 응원 한 줄 */
export function verdictCopy(type: TypeView): { title: string; message: string; showFix: boolean; missingFields: ProfileField[] } {
  const missingFields = missingFieldsOf(type)
  const base = { showFix: false, missingFields }
  switch (type.status) {
    case 'MATCHED':
      return type.coverage === 'COMMON_ONLY'
        ? { ...base, title: '공통 조건은 맞아요', message: '위의 별도 신청자격도 꼭 확인해 주세요.' }
        : { ...base, title: '기본 조건을 충족해요', message: '좋은 집과 인연이 닿길 응원할게요.' }
    case 'PARTIALLY_COMPARED': return { ...base, title: '직접 확인할 항목이 남았어요', message: '거의 다 왔어요. 공고문에서 한 가지만 더 확인해 보세요.' }
    case 'INPUT_NEEDED': return { ...base, title: '입력하면 확인할 수 있어요', message: `거의 다 왔어요. ${missingFields.length}개만 채우면 바로 알려드릴게요.` }
    case 'NOT_MATCHED': return { ...base, showFix: true, title: '맞지 않는 조건이 있어요', message: '이번 유형은 아쉬워요. 다른 유형도 함께 살펴봐요.' }
    case 'NOT_EVALUATED': return { ...base, title: '비교한 조건이 없어요', message: '이번 모집 여부를 공고문에서 확인해 주세요.' }
  }
}

const STATUS_TEXT: Record<TypeStatus, string> = {
  MATCHED: '충족', PARTIALLY_COMPARED: '확인 필요', INPUT_NEEDED: '입력 필요', NOT_MATCHED: '미충족', NOT_EVALUATED: '비교 없음',
}

/** 유형 탭의 화면 낭독기용 글자 */
export function typeStatusText(type: Pick<TypeView, 'status' | 'coverage'>): string {
  if (type.status === 'MATCHED' && type.coverage === 'COMMON_ONLY') return '공통 충족'
  return STATUS_TEXT[type.status]
}

/** 목록 카드 아래 한 줄. "지원 가능"은 일반 경로 충족에만 쓴다. */
export function listResultText(result: ListConditionResult): string | null {
  if (result.availability === 'NOT_STRUCTURED') return '자격 비교 준비 중'
  if (!result.best) return null
  if (result.best.status === 'MATCHED' && result.best.coverage === 'GENERAL') return `${result.best.label} 지원 가능`
  return `${result.best.label} ${typeStatusText(result.best)}`
}

const MISSING_REASON_TEXT = { LATER: '나중에 입력하기로 한 항목', FAMILY_SKIPPED: '가족 정보를 건너뛴 항목', UNANSWERED: '아직 입력하지 않은 항목' } as const

export function missingNote(check: CheckView, typeStatus: TypeStatus): { text: string; quiet: boolean } | null {
  if (check.status !== 'INPUT_NEEDED') return null
  if (typeStatus === 'NOT_MATCHED') return { text: '다른 조건이 맞지 않아 입력해도 결과는 같아요', quiet: true }
  return { text: `${MISSING_REASON_TEXT[check.missingReason ?? 'UNANSWERED']} · 입력하면 바로 확인돼요`, quiet: false }
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
  REQUIRED_CHECK: '신청 전 확인',
  OTHER_ROUTE: '다른 신청 경로',
  PRIORITY_OPPORTUNITY: '우선공급',
  PROCEDURE: '신청 절차',
}

export const CHECK_STATUS_LABEL = { SATISFIED: '맞아요', NOT_SATISFIED: '맞지 않아요', INPUT_NEEDED: '판정 불가', DIRECT_CHECK_REQUIRED: '직접 확인' } as const
