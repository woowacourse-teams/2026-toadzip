import { ruleText, withDirectItem, type Check, type Content, type Guidance, type Rule } from './eligibilityDraftApi'

/**
 * 관리자가 신청자격을 직접 고칠 때 쓰는 작성 내용 변경. 화면은 고친 내용을 그대로 서버에 저장하고, 판정 규칙의 검증은 서버가 한다.
 * scopeId는 'common'(모든 신청자) 또는 경로 ID다.
 */

/** 관리자가 직접 더한 조건의 ID 머리. 이 조건은 '판정에서 빼기' 대신 그냥 지울 수 있다. */
const MANUAL = 'manual-'

export function isManualCheck(check: Check): boolean {
  return check.id.startsWith(MANUAL)
}

export function scopeChecks(content: Content, scopeId: string): Check[] {
  return scopeId === 'common' ? content.commonChecks : content.paths.find(path => path.id === scopeId)?.checks ?? []
}

function withScopeChecks(content: Content, scopeId: string, checks: Check[]): Content {
  return scopeId === 'common'
    ? { ...content, commonChecks: checks }
    : { ...content, paths: content.paths.map(path => path.id === scopeId ? { ...path, checks } : path) }
}

export function renamePath(content: Content, pathId: string, label: string): Content {
  return { ...content, paths: content.paths.map(path => path.id === pathId ? { ...path, label } : path) }
}

export function renameQualification(content: Content, qualificationId: string, exactName: string): Content {
  return { ...content, qualifications: content.qualifications.map(item => item.id === qualificationId ? { ...item, exactName } : item) }
}

/** at은 묶음(ALL·ANY) 안에서 고칠 비교까지의 자식 순번이다. 빈 배열이면 조건의 맨 위 비교다. */
export function updateRule(content: Content, scopeId: string, checkId: string, at: number[], patch: Partial<Rule>): Content {
  const patched = (rule: Rule, rest: number[]): Rule => rest.length === 0
    ? { ...rule, ...patch }
    : { ...rule, children: rule.children.map((child, index) => index === rest[0] ? patched(child, rest.slice(1)) : child) }
  return withScopeChecks(content, scopeId, scopeChecks(content, scopeId).map(check => check.id === checkId ? { ...check, rule: patched(check.rule, at) } : check))
}

export function removeManualCheck(content: Content, scopeId: string, checkId: string): Content {
  return withScopeChecks(content, scopeId, scopeChecks(content, scopeId).filter(check => check.id !== checkId || !isManualCheck(check)))
}

const leaf = (operator: string, field: string, values: Partial<Rule>): Rule =>
  ({ operator, field, expected: null, minimum: null, maximum: null, regions: [], incomeLimit: null, children: [], ...values })

/**
 * 더할 수 있는 조건. 사용자가 늘 입력하는 항목만 둔다(자녀·혼인처럼 일부 사용자만 입력하는 항목은 서버가 대체 안내를 요구한다).
 * 거주기간은 지역과 함께 봐야 하는데 이 화면은 지역을 고치지 못하므로 두지 않는다(공고문에서 확인할 자격으로 더한다).
 * 금액은 비워 두어 관리자가 채우기 전에는 저장하지 않는다.
 */
export const CHECK_PRESETS: { key: string; name: string; rule: Rule }[] = [
  { key: 'age', name: '나이', rule: leaf('AGE_RANGE', 'BIRTH_DATE', { minimum: 19, maximum: 39 }) },
  { key: 'family-house', name: '무주택 (본인 포함 가족)', rule: leaf('EQUALS', 'FAMILY_OWNS_HOUSE', { expected: 'false' }) },
  { key: 'applicant-house', name: '무주택 (본인)', rule: leaf('EQUALS', 'APPLICANT_OWNS_HOUSE', { expected: 'false' }) },
  { key: 'household-income', name: '세대 월평균소득 상한 (금액)', rule: leaf('MONEY_CEILING', 'HOUSEHOLD_MONTHLY_INCOME', { maximum: Number.NaN }) },
  { key: 'applicant-income', name: '본인 월평균소득 상한 (금액)', rule: leaf('MONEY_CEILING', 'APPLICANT_MONTHLY_INCOME', { maximum: Number.NaN }) },
  { key: 'family-assets', name: '가족 총자산 상한', rule: leaf('MONEY_CEILING', 'FAMILY_TOTAL_ASSETS', { maximum: Number.NaN }) },
  { key: 'applicant-assets', name: '본인 자산 상한', rule: leaf('MONEY_CEILING', 'APPLICANT_ASSETS', { maximum: Number.NaN }) },
  { key: 'family-car', name: '가족 차량가액 상한', rule: leaf('MONEY_CEILING', 'FAMILY_CAR_VALUE', { maximum: Number.NaN }) },
  { key: 'applicant-car', name: '본인 차량가액 상한', rule: leaf('MONEY_CEILING', 'APPLICANT_CAR_VALUE', { maximum: Number.NaN }) },
  { key: 'family-type', name: '가족 형태', rule: leaf('EQUALS', 'FAMILY_TYPE', { expected: 'UNMARRIED' }) },
  { key: 'student', name: '대학생', rule: leaf('EQUALS', 'IS_UNIVERSITY_STUDENT', { expected: 'true' }) },
  { key: 'job-seeker', name: '취업준비생', rule: leaf('EQUALS', 'IS_JOB_SEEKER', { expected: 'true' }) },
]

/** 관리자가 더한 조건·자격의 근거. 서버가 근거를 요구하므로 공고 분류 근거(없으면 첫 근거)를 쓴다. */
function defaultEvidenceId(content: Content): string | undefined {
  return typeof content.metadata.classificationEvidenceId === 'string' ? content.metadata.classificationEvidenceId : content.evidences[0]?.id
}

/** 조건 이름은 저장할 때 조건 문장으로 채운다. */
export function addCheck(content: Content, scopeId: string, presetKey: string): Content {
  const preset = CHECK_PRESETS.find(item => item.key === presetKey)
  const evidenceId = defaultEvidenceId(content)
  if (!preset || !evidenceId) return content
  const used = new Set([...content.commonChecks, ...content.paths.flatMap(path => path.checks)].map(check => check.id))
  let number = 1
  while (used.has(`${MANUAL}${scopeId}-${number}`)) number++
  const check: Check = { id: `${MANUAL}${scopeId}-${number}`, label: preset.name, rule: preset.rule, appliesWhen: null, fallbackGuidanceId: null, evidenceIds: [evidenceId] }
  return withScopeChecks(content, scopeId, [...scopeChecks(content, scopeId), check])
}

/** 이 범위에 걸린 안내. 공고 전체 범위는 ANNOUNCEMENT, 경로는 그 경로 PATH 대상이다. */
export function scopeGuidances(content: Content, scopeId: string, kind: string): Guidance[] {
  const common = scopeId === 'common'
  return content.guidances.filter(item => item.kind === kind
    && item.targets.some(target => common ? target.scope === 'ANNOUNCEMENT' : target.scope === 'PATH' && target.pathId === scopeId))
}

/** 조건의 대체 안내로 쓰이는 안내. 판정 한계를 알리는 안내라 항목을 다 지워도 남긴다. */
export function fallbackIds(content: Content): Set<string | null> {
  return new Set([...content.commonChecks, ...content.paths.flatMap(path => path.checks)].map(check => check.fallbackGuidanceId))
}

/** 안내 항목을 고친다. 빈 문자열로 바꾸는 대신 removeGuidanceItem을 쓴다. */
export function editGuidanceItem(content: Content, guidanceId: string, index: number, text: string): Content {
  return { ...content, guidances: content.guidances.map(item => item.id === guidanceId ? { ...item, items: item.items.map((value, row) => row === index ? text : value) } : item) }
}

/**
 * 안내 항목을 지운다. 항목이 하나도 남지 않은 안내는(대체 안내가 아니면) 함께 지운다. 공고문에서 확인할 자격 안내는 걸린 경로를
 * 지원 가능으로 확정하지 않게 하므로, 빈 안내를 남기면 화면에 아무것도 없는데 판정만 막힌다.
 */
export function removeGuidanceItem(content: Content, guidanceId: string, index: number): Content {
  const fallbacks = fallbackIds(content)
  const guidances = content.guidances.map(item => item.id === guidanceId ? { ...item, items: item.items.filter((_, row) => row !== index) } : item)
    .filter(item => item.id !== guidanceId || item.items.length > 0 || fallbacks.has(item.id))
  return { ...content, guidances }
}

/** 공고문에서 확인할 자격(판정을 막는 직접 확인)을 더한다. '판정에서 빼기'와 같은 안내에 모은다. */
export function addDirectItem(content: Content, scopeId: string, text: string): Content {
  const evidenceId = defaultEvidenceId(content)
  return withDirectItem(content, scopeId, text, evidenceId ? [evidenceId] : [])
}

/** 저장 전 확인. 서버도 검증하지만, 비운 숫자·빈 이름은 서버 문구보다 어디를 고칠지 바로 알려 준다. */
export function editProblems(content: Content): string[] {
  const problems: string[] = []
  content.paths.forEach(path => { if (!path.label.trim()) problems.push('신청 유형 이름을 비울 수 없어요.') })
  content.qualifications.forEach(item => { if (!item.exactName.trim()) problems.push('자격 이름을 비울 수 없어요.') })
  content.guidances.forEach(item => { if (item.items.some(value => !value.trim())) problems.push('공고문에서 확인할 자격 문구를 비울 수 없어요. 필요 없으면 지워 주세요.') })
  const scopes = [{ name: '모든 신청자', checks: content.commonChecks }, ...content.paths.map(path => ({ name: path.label || '이름 없는 신청 유형', checks: path.checks }))]
  for (const scope of scopes) {
    for (const check of scope.checks) {
      const problem = ruleProblem(check.rule)
      if (problem) problems.push(`${scope.name}: ${ruleText(check.rule)} — ${problem}`)
    }
  }
  return [...new Set(problems)]
}

function ruleProblem(rule: Rule): string | null {
  if (rule.operator === 'ALL' || rule.operator === 'ANY') return rule.children.map(ruleProblem).find(Boolean) ?? null
  const numbers = [rule.minimum, rule.maximum].filter((value): value is number => value !== null)
  if (numbers.some(value => !Number.isFinite(value))) return '숫자를 채워 주세요.'
  if (rule.operator !== 'DAYS_FROM_REFERENCE' && numbers.some(value => value < 0)) return '0 이상으로 적어 주세요.'
  if (rule.minimum !== null && rule.maximum !== null && rule.minimum > rule.maximum) return '최소가 최대보다 커요.'
  if (rule.incomeLimit && (!Number.isFinite(rule.incomeLimit.percent) || rule.incomeLimit.percent <= 0)) return '소득 비율을 채워 주세요.'
  if (rule.operator === 'EQUALS' && rule.expected !== null && !rule.expected.trim()) return '값을 채워 주세요.'
  return null
}

/** 저장할 내용. 직접 더한 조건은 이름을 조건 문장으로 채우고, 이름·문구 앞뒤 공백을 지운다. */
export function finishEdit(content: Content): Content {
  const named = (check: Check): Check => isManualCheck(check) ? { ...check, label: ruleText(check.rule).slice(0, 300) } : check
  return {
    ...content,
    commonChecks: content.commonChecks.map(named),
    paths: content.paths.map(path => ({ ...path, label: path.label.trim(), checks: path.checks.map(named) })),
    qualifications: content.qualifications.map(item => ({ ...item, exactName: item.exactName.trim() })),
    guidances: content.guidances.map(item => ({ ...item, items: item.items.map(value => value.trim()) })),
  }
}
