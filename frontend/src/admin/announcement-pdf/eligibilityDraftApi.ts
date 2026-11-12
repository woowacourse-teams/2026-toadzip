import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

/** 신청자격 작성 내용(EligibilityContent). 화면은 보기·판정에서 빼기에 쓰는 칸만 다루고 나머지는 그대로 돌려 저장한다. */
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

const base = (id: string) => `/api/admin/announcements/${encodeURIComponent(id)}/basic-comparison-rule-set`
const path = (id: string) => `${base(id)}/draft`

/** 관리자 화면에 보일 신청자격. 초안이 있으면(공고 공개 전 등) 초안, 없으면 지금 사용자에게 보이는 공개본이다. */
export async function loadEligibility(id: string, signal?: AbortSignal): Promise<RuleSet | null> {
  const draft = ruleSet(await requestManagementApi(path(id), 'GET', undefined, signal))
  return draft ?? ruleSet(await requestManagementApi(`${base(id)}/published`, 'GET', undefined, signal))
}

/** PDF 추출로 만든 신청자격과, 관리자가 알아야 할 안내(잇지 못한 경로 등). published면 사용자 화면에 반영된 공개본이다. */
export type ExtractionDraft = { ruleSet: RuleSet; published: boolean; notices: string[] }

/**
 * 최신 PDF 추출의 신청자격으로 만들어 곧바로 사용자에게 반영한다(공고가 공개 전이면 서버가 저장만 한다).
 * 초안이 있으면 그 초안의 ID·revision일 때만 덮어쓴다. 공개본은 서버가 새 버전으로 바꾸므로 넘기지 않는다.
 */
export async function createEligibilityDraft(id: string, pdfSha256: string, current: RuleSet | null): Promise<ExtractionDraft> {
  const draft = current?.status === 'DRAFT' ? current : null
  const body = { pdfSha256, expectedRevision: draft?.revision ?? 0, ...(draft ? { expectedDraftId: draft.id } : {}) }
  const value = await requestManagementApi(`${path(id)}/from-extraction`, 'POST', body)
  if (!isRecord(value)) throw new Error('신청자격 응답이 비어 있습니다.')
  return { ruleSet: required(value.ruleSet), published: value.published === true,
    notices: Array.isArray(value.notices) ? value.notices.filter((item): item is string => typeof item === 'string') : [] }
}

/** 보정 적용 전 미리보기: 최신 PDF 추출을 서버 변환 규칙으로 바꾼 신청자격. 공급행에 잇지 않고 저장하지 않는다. */
export async function previewEligibilityDraft(id: string, pdfSha256: string, signal?: AbortSignal): Promise<Content> {
  const value = await requestManagementApi(`${path(id)}/from-extraction/preview?pdfSha256=${encodeURIComponent(pdfSha256)}`, 'GET', undefined, signal)
  if (!isRecord(value) || !Array.isArray(value.paths) || !Array.isArray(value.guidances)) throw new Error('신청자격 미리보기 응답이 비어 있습니다.')
  return value as Content
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

/** 고친 결과. 공개하지 못했으면(공고가 공개 전 등) 저장한 초안과 그 이유다. */
export type Applied = { ruleSet: RuleSet; published: true } | { ruleSet: RuleSet; published: false; reason: string }

/**
 * 고친 신청자격을 곧바로 사용자에게 반영한다. 공개본이면 초안으로 복사해 고친 뒤 공개하고, 초안이면 저장한 뒤 공개한다.
 * 저장은 했는데 공개가 막히면 저장한 초안을 이유와 함께 돌려준다(화면이 그 초안의 revision으로 이어서 고칠 수 있게).
 */
export async function applyEligibility(id: string, current: RuleSet, content: Content): Promise<Applied> {
  const reviewed = { ...content, metadata: { ...content.metadata, reviewStatus: 'REVIEWED' as const } }
  if (current.status !== 'PUBLISHED') return publishSaved(id, await saveEligibilityDraft(id, current, reviewed))
  const copied: RuleSet = required(await requestManagementApi(`${path(id)}/copy`, 'POST'))
  // 복사는 지금 공개본을 가져오므로, 화면이 읽은 뒤 다른 곳에서 바뀐 공개본을 예전 화면 내용으로 덮어쓰지 않게 비교한다.
  if (JSON.stringify(copied.content) !== JSON.stringify(current.content)) {
    return { ruleSet: copied, published: false, reason: '다른 곳에서 바뀐 신청자격을 불러왔어요. 다시 확인한 뒤 고쳐 주세요.' }
  }
  try {
    return publishSaved(id, await saveEligibilityDraft(id, copied, reviewed))
  } catch (error) {
    // 복사한 초안이 남았으니 화면이 그 초안으로 이어 가게 돌려준다(공개본으로 남으면 다음 복사가 충돌한다).
    return { ruleSet: copied, published: false, reason: error instanceof Error ? error.message : '저장 요청이 실패했습니다.' }
  }
}

async function publishSaved(id: string, saved: RuleSet): Promise<Applied> {
  try {
    return { ruleSet: await publishEligibilityDraft(id, saved), published: true }
  } catch (error) {
    return { ruleSet: saved, published: false, reason: error instanceof Error ? error.message : '공개 요청이 실패했습니다.' }
  }
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
  // 자산·자동차처럼 같은 원문 문구를 쓰는 조건을 옮겨도 어느 기준인지 알 수 있게 종류를 앞에 붙인다.
  const kind = ruleKind(check.rule)
  const item = kind ? `${kind}: ${check.label}` : check.label
  const existing = content.guidances.find(item => item.id === guidanceId)
  const guidance: Guidance = existing
    ? { ...existing, items: [...existing.items, item] }
    : { id: guidanceId, kind: 'REQUIRED_QUALIFICATION', title: '공고문에서 확인해야 하는 신청자격', body: '입력한 정보로는 판단할 수 없는 자격이에요. 해당하는지 공고문에서 확인해 주세요.',
      items: [item], targets: [common ? { scope: 'ANNOUNCEMENT', pathId: null, supplyUnitId: null } : { scope: 'PATH', pathId: scopeId, supplyUnitId: null }], evidenceIds: check.evidenceIds }
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

/** 워커 요건 경고의 머리: 신청자격 요건 '<문구>'(<종류>): . 종류가 없는 것은 이전 워커의 경고다. */
const REQUIREMENT_WARNING = /^신청자격 요건 '(.+?)'(?:\(([^)]+)\))?: (.+)$/s

/** 워커 요건 종류 → 경고 머리의 종류 이름(워커 eligibility.py의 _KIND_NAMES와 같다). */
export const KIND_NAMES: Record<string, string> = {
  AGE: '나이', NO_HOUSE: '무주택', INCOME: '소득', ASSETS: '자산', CAR_VALUE: '자동차가액', NO_CAR: '자동차 미보유',
  RESIDENCE: '거주', MARRIAGE_PERIOD: '혼인기간', FAMILY_TYPE: '가족 형태', UNIVERSITY_STUDENT: '대학생', JOB_SEEKER: '취업준비생',
}
const KNOWN_KINDS = new Set(Object.values(KIND_NAMES))
/** 판정 조건 필드 → 그 조건을 만든 요건 종류 이름. */
const FIELD_KINDS: Record<string, string> = {
  BIRTH_DATE: '나이', YOUNGEST_CHILD: '나이', APPLICANT_OWNS_HOUSE: '무주택', FAMILY_OWNS_HOUSE: '무주택',
  APPLICANT_MONTHLY_INCOME: '소득', HOUSEHOLD_MONTHLY_INCOME: '소득', APPLICANT_ASSETS: '자산', FAMILY_TOTAL_ASSETS: '자산',
  APPLICANT_CAR_VALUE: '자동차가액', FAMILY_CAR_VALUE: '자동차가액', APPLICANT_OWNS_CAR: '자동차 미보유', FAMILY_OWNS_CAR: '자동차 미보유',
  RESIDENCE_REGION: '거주', CURRENT_REGION_RESIDENCE_MONTHS: '거주', MARRIAGE_EVENT: '혼인기간', FAMILY_TYPE: '가족 형태',
  IS_UNIVERSITY_STUDENT: '대학생', IS_JOB_SEEKER: '취업준비생',
}

/** 경고를 붙일 곳: 화면에 보이는 문구와, 알면 그 문구를 만든 요건 종류. */
export type WarningTarget = { label: string; kind?: string }

/** 판정 조건. 종류는 조건의 첫 비교(가산 대안이 붙은 조건은 기본 기준)의 필드로 안다. */
export function checkTarget(check: Check): WarningTarget {
  return { label: check.label, kind: ruleKind(check.rule) }
}

function ruleKind(rule: Rule): string | undefined {
  return rule.field ? FIELD_KINDS[rule.field] : rule.children[0] && ruleKind(rule.children[0])
}

/**
 * 목록·직접 확인 항목. '종류: 문구'로 옮긴 직접 확인은 종류를 안다. 가산 알림은 문구 전체가 라벨이라 어떤 경고도 붙지 않는다.
 * 서버는 가산 알림을 늘 그 요건의 판정 조건과 함께 만들므로 경고는 조건 곁에 한 번만 둔다.
 */
export function itemTarget(item: string): WarningTarget {
  const separator = item.indexOf(': ')
  const kind = item.slice(0, separator)
  return separator > 0 && KNOWN_KINDS.has(kind) ? { label: item.slice(separator + 2), kind } : { label: item }
}

/**
 * 워커 경고 중 이 곳에 붙는 것. 워커는 요건 문구(40자가 넘으면 앞 40자에 '…')와 종류로 경고를 시작하고, 서버는 같은 문구를
 * 조건·목록 문구로 쓴다. 짧은 문구가 그 문구를 품은 다른 조건에 붙지 않게 같은 문구와만 맞추고, 자산·자동차처럼 같은 문구를 쓰는
 * 요건은 양쪽 종류를 알 때 종류까지 맞춘다. 돌려주는 값은 요건 이름을 뗀 이유(어떻게 확인했고 무엇을 확인하지 못했는지)다.
 */
export function requirementWarnings(target: WarningTarget, warnings: string[]): string[] {
  return warnings.flatMap(warning => {
    const match = REQUIREMENT_WARNING.exec(warning)
    if (!match) return []
    const [, quoted, kind, reason] = match
    const same = quoted.endsWith('…') ? target.label.startsWith(quoted.slice(0, -1)) : target.label === quoted
    const sameKind = !target.kind || !kind || !KNOWN_KINDS.has(kind) || target.kind === kind
    return same && sameKind ? [reason] : []
  })
}

/** 판정에 쓰지 않는 항목(직접 확인·목록)의 이유. 워커 경고는 판정에 쓴다고 말하므로 그 부분만 바꾼다. */
export function unjudgedReason(reason: string): string {
  return reason
    .replace('확정하지 못했고, 그대로 판정에 씁니다', '확정하지 못했어요. 이 항목은 판정에 쓰지 않아요')
    .replace('그대로 판정에 씁니다', '이 항목은 판정에 쓰지 않아요')
}

/** 어느 조건·목록 문구에도 붙지 않은 경고. 탭 위 목록에 남긴다. */
export function unmatchedWarnings(targets: WarningTarget[], warnings: string[]): string[] {
  return warnings.filter(warning => !targets.some(target => requirementWarnings(target, [warning]).length > 0))
}

/** 판정에 쓰는 숫자를 원문과 확정하지 못했다는 요건 경고인지(목록으로 뺀 요건의 경고는 판정 조건과 무관하다). */
export function isNumberWarning(warning: string): boolean {
  return REQUIREMENT_WARNING.exec(warning)?.[3].startsWith('숫자 확인 필요') ?? false
}

/**
 * 이번 PDF 추출의 숫자 대조를 이 조건에 대해 말할 수 있는지. unknown은 추출 경고가 없는 화면(결과 화면),
 * unmatched는 어느 조건에도 붙이지 못한 요건 경고가 있어 그 경고가 이 조건 것일 수도 있는 경우다(이전 추출로 만든 초안 등).
 */
export type NumberCheck = 'unknown' | 'checked' | 'unmatched'

/** 조건을 어떻게 확인했는지. 근거 문구를 원문 쪽에서 찾은 조건만 근거가 있고, 숫자 대조 결과는 이번 PDF 추출의 경고로 안다. */
export function checkedHow(check: Check, content: Content, reasons: string[], numbers: NumberCheck): string {
  const evidence = content.evidences.find(item => check.evidenceIds.includes(item.id))
  const found = evidence ? `원문 p.${evidence.firstPage}에서 이 문구를 찾았어요.` : '근거 쪽이 없어요.'
  if (reasons.length > 0) return `${found} ${reasons.join(' ')}`
  if (numbers === 'unknown' || !hasNumber(check.rule)) return found
  if (numbers === 'unmatched') return `${found} 이번 PDF 추출 경고 중 조건에 붙이지 못한 것이 있어요. 위 'PDF에서 읽으며 확인할 점'에 이 조건 숫자가 있는지 함께 보세요.`
  return `${found} 이번 PDF 추출에서 숫자를 문구·쪽 글과 대조했고 맞지 않는 숫자는 없었어요.`
}

function hasNumber(rule: Rule): boolean {
  return rule.minimum !== null || rule.maximum !== null || rule.incomeLimit !== null || rule.children.some(hasNumber)
}

const FIELDS: Record<string, string> = {
  BIRTH_DATE: '나이', YOUNGEST_CHILD: '막내 자녀 나이', APPLICANT_OWNS_HOUSE: '본인 주택', FAMILY_OWNS_HOUSE: '본인 포함 가족 주택',
  APPLICANT_OWNS_CAR: '본인 자동차', FAMILY_OWNS_CAR: '본인 포함 가족 자동차', APPLICANT_ASSETS: '본인 자산', FAMILY_TOTAL_ASSETS: '가족 총자산',
  APPLICANT_CAR_VALUE: '본인 차량가액', FAMILY_CAR_VALUE: '가족 차량가액', APPLICANT_MONTHLY_INCOME: '본인 월평균소득',
  HOUSEHOLD_MONTHLY_INCOME: '세대 월평균소득', RESIDENCE_REGION: '거주지역', CURRENT_REGION_RESIDENCE_MONTHS: '현재 지역 거주기간',
  MARRIAGE_EVENT: '혼인기간', FAMILY_TYPE: '가족 형태', HAS_CHILDREN_OR_PREGNANCY: '자녀가 있거나 임신 중', IS_UNIVERSITY_STUDENT: '대학생', IS_JOB_SEEKER: '취업준비생',
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
    case 'ALL': return rule.children.map(grouped).join(' 그리고 ')
    case 'ANY': return rule.children.map(grouped).join(' 또는 ')
    default: return field || rule.operator
  }
}

/** 묶음 안의 묶음은 괄호로 감싸 '그리고'·'또는'이 어디까지인지 보이게 한다. */
function grouped(rule: Rule): string {
  return rule.operator === 'ALL' || rule.operator === 'ANY' ? `(${ruleText(rule)})` : ruleText(rule)
}

function ruleSet(value: unknown): RuleSet | null {
  if (value === null || value === undefined) return null
  if (!isRecord(value) || typeof value.id !== 'number' || typeof value.revision !== 'number' || !isRecord(value.content)) {
    throw new Error('신청자격 응답이 올바르지 않습니다.')
  }
  return value as unknown as RuleSet
}

function required(value: unknown): RuleSet {
  const result = ruleSet(value)
  if (!result) throw new Error('신청자격 응답이 비어 있습니다.')
  return result
}
