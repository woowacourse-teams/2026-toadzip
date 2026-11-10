import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

/** 신청자격 작성 내용(EligibilityContent). 화면은 보기·직접 확인 전환·검토 표시에 쓰는 칸만 다루고 나머지는 그대로 돌려 저장한다. */
export type Rule = {
  operator: string; field: string | null; expected: string | null; minimum: number | null; maximum: number | null
  regions: string[]; incomeLimit: { standardTableId: number; percent: number; sizeExtraPercent: Record<string, number> } | null
  children: Rule[]
}
export type Check = { id: string; label: string; rule: Rule; appliesWhen: Rule | null; fallbackGuidanceId: string | null; evidenceIds: string[] }
export type Guidance = { id: string; kind: string; title: string; body: string; items: string[]; targets: { scope: string; pathId: string | null; supplyUnitId: string | null }[]; evidenceIds: string[] }
export type Content = {
  metadata: { referenceDate: string; reviewStatus: 'REVIEWING' | 'REVIEWED'; scopeNote: string | null; [key: string]: unknown }
  evidences: { id: string; firstPage: number; description: string | null }[]
  supplyUnits: { id: string; label: string }[]
  paths: { id: string; label: string; checks: Check[]; qualificationIds: string[]; [key: string]: unknown }[]
  options: { id: string; supplyUnitId: string; pathId: string; recruitmentStatus: string }[]
  guidances: Guidance[]
  commonChecks: Check[]
  qualifications: { id: string; exactName: string; conditionsAsWritten: string; evidenceIds: string[] }[]
  [key: string]: unknown
}
export type RuleSet = { id: number; revision: number; status: string; content: Content }

const path = (id: string) => `/api/admin/announcements/${encodeURIComponent(id)}/basic-comparison-rule-set/draft`

export async function loadEligibilityDraft(id: string, signal?: AbortSignal): Promise<RuleSet | null> {
  return ruleSet(await requestManagementApi(path(id), 'GET', undefined, signal))
}

/** AI 초안과, 검토할 때 알아야 할 안내(잇지 못한 경로·경로가 없는 공급대상). 안내는 공개 내용에 들어가지 않는다. */
export type ExtractionDraft = { ruleSet: RuleSet; notices: string[] }

/** 최신 PDF 추출의 신청자격으로 초안을 만든다. 초안이 있으면 그 초안의 ID·revision일 때만 덮어쓴다. */
export async function createEligibilityDraft(id: string, pdfSha256: string, current: RuleSet | null): Promise<ExtractionDraft> {
  const body = { pdfSha256, expectedRevision: current?.revision ?? 0, ...(current ? { expectedDraftId: current.id } : {}) }
  const value = await requestManagementApi(`${path(id)}/from-extraction`, 'POST', body)
  if (!isRecord(value)) throw new Error('신청자격 초안 응답이 비어 있습니다.')
  return { ruleSet: required(value.ruleSet), notices: Array.isArray(value.notices) ? value.notices.filter((item): item is string => typeof item === 'string') : [] }
}

export async function saveEligibilityDraft(id: string, current: RuleSet, content: Content): Promise<RuleSet> {
  return required(await requestManagementApi(path(id), 'PUT', { expectedDraftId: current.id, expectedRevision: current.revision, content }))
}

/** 공개 전 검증을 거친 뒤 공개한다. 검증에 실패하면 공개하지 않는다. */
export async function publishEligibilityDraft(id: string, current: RuleSet): Promise<RuleSet> {
  const version = { draftId: current.id, expectedRevision: current.revision }
  await requestManagementApi(`${path(id)}/validation`, 'POST', version)
  return required(await requestManagementApi(`${path(id)}/publish`, 'POST', version))
}

/**
 * 조건을 지우지 않고 그 범위의 필수 확인 자격 안내 항목으로 옮긴다. 판정기는 이 안내가 걸린 선택지를 지원 가능으로 확정하지 않으므로,
 * 잘못 읽힌 조건을 빼도 판정이 넓어지지 않는다.
 */
export function moveToDirectCheck(content: Content, scopeId: string, checkId: string): Content {
  const common = scopeId === 'common'
  const checks = common ? content.commonChecks : content.paths.find(item => item.id === scopeId)?.checks ?? []
  const check = checks.find(item => item.id === checkId)
  if (!check) return content
  const guidanceId = `direct-${scopeId}`
  const existing = content.guidances.find(item => item.id === guidanceId)
  const guidance: Guidance = existing
    ? { ...existing, items: [...existing.items, check.label] }
    : { id: guidanceId, kind: 'REQUIRED_QUALIFICATION', title: '공고문에서 직접 확인할 신청자격', body: '자동으로 판정하지 못한 요건이에요. 공고문 원문에서 해당하는지 확인해 주세요.',
      items: [check.label], targets: [common ? { scope: 'ANNOUNCEMENT', pathId: null, supplyUnitId: null } : { scope: 'PATH', pathId: scopeId, supplyUnitId: null }], evidenceIds: check.evidenceIds }
  const remaining = checks.filter(item => item.id !== checkId)
  // 옮긴 조건의 대체 안내만, 다른 조건이 더 쓰지 않을 때 뺀다. 사람이 쓴 다른 안내는 그대로 둔다.
  const stillUsed = [...content.commonChecks, ...content.paths.flatMap(item => item.checks)]
    .some(item => item.id !== checkId && item.fallbackGuidanceId === check.fallbackGuidanceId)
  const guidances = [...content.guidances.filter(item => item.id !== guidanceId), guidance]
    .filter(item => item.id !== check.fallbackGuidanceId || stillUsed)
  return {
    ...content, guidances,
    commonChecks: common ? remaining : content.commonChecks,
    paths: content.paths.map(item => item.id === scopeId ? { ...item, checks: remaining } : item),
  }
}

const FIELDS: Record<string, string> = {
  BIRTH_DATE: '나이', YOUNGEST_CHILD: '막내 자녀 나이', APPLICANT_OWNS_HOUSE: '본인 주택', FAMILY_OWNS_HOUSE: '본인 포함 가족 주택',
  APPLICANT_OWNS_CAR: '본인 자동차', FAMILY_OWNS_CAR: '본인 포함 가족 자동차', APPLICANT_ASSETS: '본인 자산', FAMILY_TOTAL_ASSETS: '가족 총자산',
  APPLICANT_CAR_VALUE: '본인 차량가액', FAMILY_CAR_VALUE: '가족 차량가액', APPLICANT_MONTHLY_INCOME: '본인 월평균소득',
  HOUSEHOLD_MONTHLY_INCOME: '세대 월평균소득', RESIDENCE_REGION: '거주지역', CURRENT_REGION_RESIDENCE_MONTHS: '현재 지역 거주기간',
  MARRIAGE_EVENT: '혼인기간', FAMILY_TYPE: '가족 형태', IS_UNIVERSITY_STUDENT: '대학생', IS_JOB_SEEKER: '취업준비생',
}
const FAMILY: Record<string, string> = { UNMARRIED: '미혼', SINGLE_PARENT: '한부모', COUPLE_OR_ENGAGED: '부부·예비부부' }
const won = (value: number) => value % 10_000 === 0 ? `${(value / 10_000).toLocaleString('ko-KR')}만원` : `${value.toLocaleString('ko-KR')}원`

/** 운영자가 읽는 조건 문장. 판정 규칙의 원본은 서버이며 이 문장은 보기용이다. */
export function ruleText(rule: Rule): string {
  const field = rule.field ? FIELDS[rule.field] ?? rule.field : ''
  switch (rule.operator) {
    case 'AGE_RANGE': return rule.field === 'YOUNGEST_CHILD' ? `막내 자녀 만 ${rule.maximum}세 이하`
      : rule.maximum !== null && rule.maximum < 150 ? `만 ${rule.minimum}~${rule.maximum}세` : `만 ${rule.minimum}세 이상`
    case 'EQUALS':
      if (rule.field === 'FAMILY_TYPE') return `가족 형태 ${FAMILY[rule.expected ?? ''] ?? rule.expected}`
      if (rule.expected === 'false') return `${field} 없음`
      return rule.expected === 'true' ? field : `${field} ${rule.expected}`
    case 'MONEY_CEILING': return `${field} ${won(rule.maximum ?? 0)} 이하`
    case 'INCOME_CEILING': {
      const extra = Object.entries(rule.incomeLimit?.sizeExtraPercent ?? {}).map(([size, percent]) => `${size}인 +${percent}%p`).join(', ')
      return `${field} 기준표 #${rule.incomeLimit?.standardTableId} ${rule.incomeLimit?.percent}% 이하${extra ? `(${extra})` : ''}`
    }
    case 'REGION_IN': return `거주지역 코드 ${rule.regions.join(', ')}`
    case 'MONTHS_SINCE': return `혼인 ${Math.round((rule.maximum ?? 0) / 12)}년 이내`
    case 'NUMBER_RANGE': return rule.field === 'CURRENT_REGION_RESIDENCE_MONTHS' ? `현재 지역 ${rule.minimum}개월 이상 거주` : `${field} ${rule.minimum}~${rule.maximum}`
    case 'ALL': return rule.children.map(ruleText).join(' 그리고 ')
    case 'ANY': return rule.children.map(ruleText).join(' 또는 ')
    default: return field || rule.operator
  }
}

function ruleSet(value: unknown): RuleSet | null {
  if (value === null || value === undefined) return null
  if (!isRecord(value) || typeof value.id !== 'number' || typeof value.revision !== 'number' || !isRecord(value.content)) {
    throw new Error('신청자격 초안 응답이 올바르지 않습니다.')
  }
  return value as unknown as RuleSet
}

function required(value: unknown): RuleSet {
  const result = ruleSet(value)
  if (!result) throw new Error('신청자격 초안 응답이 비어 있습니다.')
  return result
}
