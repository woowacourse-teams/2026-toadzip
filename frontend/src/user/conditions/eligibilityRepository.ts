import { getApiBaseUrl } from '../../api/apiBaseUrl'
import { FAMILY_TYPE_COPY, FIELD_COPY } from './conditionFieldCopy'
import { formatWon } from './conditionFormat'
import { regionLabel } from './conditionRegions'
import {
  ConditionsConflictError, ConditionsSessionError, PROFILE_FIELDS, TYPE_STATUS_ORDER,
  type AnnouncementRef, type CheckView, type ComparisonView, type ConditionsRepository,
  type DiscoveryPage, type EligibilityProcessing, type EvidenceRef, type Fact,
  type GuidanceView, type ListConditionResult, type ProfileField, type StoredConditionProfile, type TypeStatus,
} from './conditionsModel'

type Json = Record<string, unknown>
function record(value: unknown): value is Json { return typeof value === 'object' && value !== null && !Array.isArray(value) }
function object(value: unknown): Json {
  if (!record(value)) throw new Error('신청자격 응답을 확인하지 못했어요.')
  return value
}
function text(value: unknown, fallback = ''): string { return typeof value === 'string' ? value : fallback }
function nullableText(value: unknown): string | null { return typeof value === 'string' ? value : null }
function number(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new Error('응답의 수치를 확인하지 못했어요.')
  return value
}
function list(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error('응답의 목록을 확인하지 못했어요.')
  return value
}
function strings(value: unknown): string[] {
  return list(value).map((item) => {
    if (typeof item !== 'string') throw new Error('응답의 항목을 확인하지 못했어요.')
    return item
  })
}
function oneOf<const T extends string>(value: unknown, values: readonly T[]): T {
  const match = values.find((item) => item === value)
  if (!match) throw new Error('응답의 상태를 확인하지 못했어요.')
  return match
}
function field(value: unknown): ProfileField | null { return PROFILE_FIELDS.find((item) => item === value) ?? null }
function fields(value: unknown): ProfileField[] { return strings(value).map((item) => oneOf(item, PROFILE_FIELDS)) }
function status(value: unknown): TypeStatus { return oneOf(value, ['ELIGIBLE', 'INELIGIBLE', 'UNDETERMINED', 'NOT_EVALUATED']) }
function envelope(value: unknown): unknown { return object(value).data }

function stored(value: unknown): StoredConditionProfile {
  const data = object(value)
  const profile = object(data.profile)
  const facts: Partial<Record<ProfileField, Fact>> = {}
  for (const [key, raw] of Object.entries(object(profile.facts))) {
    const name = oneOf(key, PROFILE_FIELDS)
    const fact = object(raw)
    const state = oneOf(fact.state, ['PROVIDED', 'UNKNOWN', 'NOT_APPLICABLE'])
    if (state !== 'PROVIDED') { facts[name] = { state }; continue }
    const answer = fact.value
    if (typeof answer === 'string' || typeof answer === 'boolean' || typeof answer === 'number' && Number.isFinite(answer)) {
      facts[name] = { state, value: answer }
    } else if (record(answer) && typeof answer.date === 'string') {
      facts[name] = { state, value: { kind: oneOf(answer.kind, ['REGISTERED', 'PLANNED', 'BORN', 'EXPECTED']), date: answer.date } }
    } else throw new Error('저장된 입력값을 확인하지 못했어요.')
  }
  return { version: number(data.version), savedAt: nullableText(data.updatedAt),
    reevaluationState: oneOf(data.reevaluationState, ['PENDING', 'RUNNING', 'DONE']),
    profile: { familyDetailsState: oneOf(profile.familyDetailsState, ['NOT_STARTED', 'PROVIDED', 'SKIPPED']), facts } }
}

/** 예/아니요로 답한 항목의 내 값 문구 [예, 아니요]. 없으면 예/아니요로 보인다. */
const BOOL_MINE: Partial<Record<ProfileField, readonly [string, string]>> = {
  APPLICANT_OWNS_HOUSE: ['주택 있음', '주택 없음'], FAMILY_OWNS_HOUSE: ['가족 중 있음', '가족 모두 없음'],
  APPLICANT_OWNS_CAR: ['자동차 있음', '자동차 없음'], FAMILY_OWNS_CAR: ['가족 중 있음', '가족 모두 없음'],
  IS_UNIVERSITY_STUDENT: ['대학생', '대학생 아님'], IS_JOB_SEEKER: ['취업준비생', '취업준비생 아님'],
  HAS_CHILDREN_OR_PREGNANCY: ['자녀 있음', '자녀 없음'], IS_DUAL_INCOME: ['맞벌이', '맞벌이 아님'],
  IS_HOUSEHOLD_SEPARATED: ['세대분리', '세대분리 안 함'],
}
/** 예/아니요 조건의 기준 [예여야 할 때, 아니요여야 할 때]. 화면이 '기준 …'으로 읽는다. */
const BOOL_RULE: Partial<Record<ProfileField, readonly [string, string]>> = {
  APPLICANT_OWNS_HOUSE: ['본인 주택 있음', '본인 무주택'],
  FAMILY_OWNS_HOUSE: ['세대원 중 주택 있음', '세대원 모두 무주택'],
  APPLICANT_OWNS_CAR: ['본인 자동차 있음', '본인 자동차 없음'],
  FAMILY_OWNS_CAR: ['세대원 중 자동차 있음', '세대원 모두 자동차 없음'],
  IS_UNIVERSITY_STUDENT: ['재학 중인 대학생', '대학생 아님'],
  IS_JOB_SEEKER: ['취업준비생', '취업준비생 아님'],
  HAS_CHILDREN_OR_PREGNANCY: ['자녀 있음 또는 임신 중', '자녀 없음'],
  IS_DUAL_INCOME: ['맞벌이', '맞벌이 아님'],
  IS_HOUSEHOLD_SEPARATED: ['부모님과 세대 분리', '부모님과 같은 세대'],
}
/** 조건 줄의 제목. 관리자가 붙인 이름은 공고문 문장 그대로인 경우가 많아, 무엇을 비교하는지로 짧게 부른다. */
const TITLES: Record<ProfileField, string> = {
  BIRTH_DATE: '나이', YOUNGEST_CHILD: '자녀 나이', RESIDENCE_REGION: '거주지역', CURRENT_REGION_RESIDENCE_MONTHS: '거주기간',
  CURRENT_LEASE_END_DATE: '계약 종료일', IS_UNIVERSITY_STUDENT: '대학생', IS_JOB_SEEKER: '취업준비생',
  APPLICANT_OWNS_HOUSE: '본인 무주택', FAMILY_OWNS_HOUSE: '무주택 세대', APPLICANT_OWNS_CAR: '자동차', FAMILY_OWNS_CAR: '자동차',
  APPLICANT_CAR_VALUE: '자동차가액', FAMILY_CAR_VALUE: '자동차가액', APPLICANT_ASSETS: '자산', FAMILY_TOTAL_ASSETS: '총자산',
  APPLICANT_MONTHLY_INCOME: '본인 소득', HOUSEHOLD_MONTHLY_INCOME: '세대 소득', PARENTS_MONTHLY_INCOME: '부모님 소득', FAMILY_TYPE: '가족 형태',
  IS_HOUSEHOLD_SEPARATED: '세대분리', REGISTERED_FAMILY_COUNT: '가족 수', MARRIAGE_EVENT: '혼인기간',
  HAS_CHILDREN_OR_PREGNANCY: '자녀', CHILDREN_COUNT_INCLUDING_FETUS: '자녀 수', IS_DUAL_INCOME: '맞벌이',
}

function display(value: unknown, name: ProfileField | null = null, unit = ''): string {
  if (typeof value === 'boolean' || value === 'true' || value === 'false') {
    const yes = value === true || value === 'true'
    const words = name ? BOOL_MINE[name] : undefined
    return words ? words[yes ? 0 : 1] : yes ? '예' : '아니요'
  }
  if (typeof value === 'number') {
    if (unit.startsWith('KRW') || name && FIELD_COPY[name].kind === 'money') return `${name && FIELD_COPY[name].monthly ? '월 ' : ''}${formatWon(value)}`
    const suffix: Record<string, string> = { YEARS: '세', MONTHS: '개월', DAYS: '일', PERSONS: '명', PEOPLE: '명', COUNT: '명' }
    return `${value.toLocaleString('ko-KR')}${suffix[unit] ?? (name && FIELD_COPY[name].kind === 'count' ? '명' : '')}`
  }
  if (typeof value === 'string') {
    if (name === 'RESIDENCE_REGION') return regionLabel(value)
    if (name === 'FAMILY_TYPE') return Object.entries(FAMILY_TYPE_COPY).find(([key]) => key === value)?.[1].label ?? value
    return value
  }
  if (record(value)) return text(value.date)
  return ''
}
function evidence(content: Json, ids: unknown): EvidenceRef | null {
  const id = strings(ids)[0]
  const item = list(content.evidences).map(object).find((candidate) => candidate.id === id)
  if (!item) return null
  const document = list(content.documents).map(object).find((candidate) => candidate.id === item.documentId)
  return { page: number(item.firstPage), location: nullableText(item.location), fileName: nullableText(document?.fileName) }
}
function numeric(value: unknown): number | null { return typeof value === 'number' ? value : null }
/** 조건 줄의 공고 기준(명사구). 화면이 앞에 '기준 '을 붙여 '기준 만 19~39세'처럼 읽는다. */
function criterion(compared: Json, rule: Json, name: ProfileField | null): string {
  const unit = text(compared.unit) || (rule.operator === 'AGE_RANGE' ? 'YEARS' : rule.operator === 'MONTHS_SINCE' || name === 'CURRENT_REGION_RESIDENCE_MONTHS' ? 'MONTHS' : name && FIELD_COPY[name].kind === 'count' ? 'PEOPLE' : '')
  const minimum = numeric(compared.minimum ?? rule.minimum)
  const maximum = numeric(compared.maximum ?? rule.maximum)
  if (compared.vehicleOwned === false) {
    const limit = compared.comparisonLimit ?? rule.maximum
    return typeof limit === 'number' ? `차량 ${display(limit, null, 'KRW')} 이하` : '자동차가 없으면 충족'
  }
  if (compared.comparisonLimit !== undefined) return `${display(compared.comparisonLimit, name, unit)} 이하`
  if (compared.expected !== undefined || rule.operator === 'EQUALS') {
    const expected = compared.expected ?? rule.expected
    const sentence = name && (expected === 'true' || expected === 'false') ? BOOL_RULE[name]?.[expected === 'true' ? 0 : 1] : undefined
    return sentence ?? display(expected, name)
  }
  if (Array.isArray(compared.requiredRegions) || rule.operator === 'REGION_IN') return `${strings(compared.requiredRegions ?? rule.regions).map(regionLabel).join(' 또는 ')} 거주`
  if (rule.operator === 'MONTHS_SINCE' && maximum !== null) return `혼인 ${maximum % 12 === 0 ? `${maximum / 12}년` : `${maximum}개월`} 이내`
  if (rule.operator === 'MONEY_CEILING') return `${display(rule.maximum, name, 'KRW')} 이하`
  if (rule.operator === 'AGE_RANGE' && minimum !== null && maximum !== null) {
    const who = name === 'YOUNGEST_CHILD' ? '막내 자녀 ' : ''
    if (maximum >= 150) return `${who}만 ${minimum}세 이상`
    return minimum === 0 ? `${who}만 ${maximum}세 이하` : `${who}만 ${minimum}~${maximum}세`
  }
  if (minimum !== null) {
    if (maximum === null || maximum >= 1800) return `${display(minimum, null, unit)} 이상`
    return `${display(minimum, null, unit)} 이상 · ${display(maximum, null, unit)} 이하`
  }
  if (rule.operator === 'INCOME_CEILING') {
    const limit = record(rule.incomeLimit) ? numeric(rule.incomeLimit.percent) : null
    return limit === null ? '공고문의 소득 기준표' : `도시근로자 월평균 소득의 ${limit}% 이하`
  }
  if (rule.operator === 'ALL') return '아래 조건 모두 충족'
  if (rule.operator === 'ANY') return '아래 조건 중 하나 이상 충족'
  return '공고문에 적힌 조건'
}
function children(rule: Json): Json[] { return Array.isArray(rule.children) ? rule.children.filter(record) : [] }
function leaf(rule: Json): boolean { return children(rule).length === 0 }
/** 조건이 비교하는 항목들(묶음이면 안의 모든 항목). */
function leafFields(rule: Json): ProfileField[] {
  const own = field(rule.field)
  return leaf(rule) ? own ? [own] : [] : children(rule).flatMap(leafFields)
}
/**
 * 가산 기준이 붙은 조건: ANY(기본 기준, 가산 대안…). 서버는 출산자녀 가산을 ALL(출산자녀, 자녀 수 범위, 높인 기준)으로,
 * 계산할 수 없는 가산은 그 밖의 대안으로 만든다. 사용자에게는 중첩 줄 대신 한 줄로 요약한다.
 */
type Tier = { index: number; minimum: number; maximum: number; raised: Json }
function adjusted(rule: Json): { base: Json; tiers: Tier[]; other: boolean } | null {
  const [base, ...alternatives] = children(rule)
  // 서버 가산 모양만 요약한다: 금액·소득 기본 기준 하나와 ALL 묶음 대안들. 그 밖의 ANY(한부모 또는 부부 등)는 선택지를 그대로 보인다.
  if (rule.operator !== 'ANY' || !base || !leaf(base) || !['MONEY_CEILING', 'INCOME_CEILING'].includes(text(base.operator))
    || !alternatives.length || alternatives.some((alternative) => alternative.operator !== 'ALL')) return null
  const tiers: Tier[] = []
  let other = false
  alternatives.forEach((alternative, index) => {
    const parts = children(alternative)
    const count = parts.find((part) => part.operator === 'NUMBER_RANGE' && part.field === 'CHILDREN_COUNT_INCLUDING_FETUS')
    const raised = parts.find((part) => part.operator === base.operator && part.field === base.field)
    const minimum = numeric(count?.minimum)
    if (alternative.operator === 'ALL' && count && raised && minimum !== null) tiers.push({ index: index + 1, minimum, maximum: numeric(count.maximum) ?? 1800, raised })
    else other = true
  })
  return { base, tiers, other }
}
/**
 * 가산 기준 한 칸. 소득은 가구원 수 가산이 더해진 실제 금액을 서버 비교값에서 읽고, 소득을 입력하지 않아 금액이 없으면
 * 기본 기준보다 몇 %p 높은지로 보인다(가산 비율 그대로 쓰면 가구원 수 가산이 붙은 기본 비율과 같아 보일 수 있다).
 */
function tierText(tier: Tier, base: Json, name: ProfileField | null, compared: unknown[]): string {
  const people = tier.maximum >= 1800 ? `${tier.minimum}명 이상` : tier.minimum === tier.maximum ? `${tier.minimum}명` : `${tier.minimum}~${tier.maximum}명`
  if (base.operator === 'INCOME_CEILING') {
    const parts = list(object(object(compared[tier.index]).comparedValues).children).map((part) => object(object(part).comparedValues))
    const limit = parts.find((part) => part.field === base.field && part.comparisonLimit !== undefined)?.comparisonLimit
    if (limit !== undefined) return `${people} ${display(limit, name, 'KRW_PER_MONTH')}`
    const raised = record(tier.raised.incomeLimit) ? numeric(tier.raised.incomeLimit.percent) : null
    const basic = record(base.incomeLimit) ? numeric(base.incomeLimit.percent) : null
    return raised !== null && basic !== null ? `${people} +${raised - basic}%p` : people
  }
  return `${people} ${display(tier.raised.maximum, name, 'KRW')}`
}
function titleOf(rule: Json, label: string): string {
  const base = adjusted(rule)?.base
  const titles = [...new Set((base ? leafFields(base) : leafFields(rule)).map((name) => TITLES[name]))]
  return titles.length === 1 && titles[0] ? titles[0] : label
}
function joiner(rule: Json): string { return rule.operator === 'ANY' ? ' 또는 ' : ' · ' }
/** 묶음 조건의 제목. 한 단계 묶음이면 항목 이름을 이어 부르고, 묶음 안에 묶음이 있으면 공고의 조건 이름을 쓴다. */
function groupTitle(rule: Json, label: string): string {
  const parts = children(rule)
  const single = titleOf(rule, label)
  if (single !== label || parts.some((part) => !leaf(part))) return single
  const titles = [...new Set(parts.map((part) => { const name = field(part.field); return name ? TITLES[name] : '' }))]
  return titles.every(Boolean) && titles.length <= 3 ? titles.join(joiner(rule)) : label
}
/**
 * 묶음 조건(ANY·ALL)을 다른 조건과 같은 한 줄로 요약한다. 기준은 항목 기준을 '또는'·'·'로 잇고 안쪽 묶음은 괄호로 감싼다.
 * 내 값은 충족이면 맞은 항목의 값, 미충족이면 고칠 수 있게 모든 항목의 값을 보인다.
 */
function mapGroup(id: string, label: string, rule: Json, raw: Json, readings: unknown[], source: EvidenceRef | null): CheckView {
  const missing = fields(raw.missingInputs)
  const status = oneOf(raw.status, ['SATISFIED', 'NOT_SATISFIED', 'INPUT_NEEDED', 'DIRECT_CHECK_REQUIRED'])
  const rules = children(rule)
  const parts = readings.map((reading, index) => {
    const partRule = rules[index] ?? {}
    const partField = field(partRule.field)
    return { rule: partRule, view: mapReading(`${id}-${index}`, partField ? FIELD_COPY[partField].label : `${label} 조건 ${index + 1}`, object(reading), partRule, source) }
  })
  const criterion = parts.map(({ rule: partRule, view }) => {
    const line = `${view.criterion}${view.basis ? ` (${view.basis})` : ''}`
    return leaf(partRule) ? line : `(${line})`
  }).join(joiner(rule))
  const shown = status === 'SATISFIED' ? parts.filter(({ view }) => view.status === 'SATISFIED') : status === 'NOT_SATISFIED' ? parts : []
  const mine = [...new Set(shown.map(({ view }) => view.mine).filter((value) => value !== null))].join(' · ') || null
  return { id, label: groupTitle(rule, label), status, mine, criterion, basis: null, evidence: source,
    fields: missing.length ? missing : [...new Set(leafFields(rule))], missingReason: missing.length ? 'UNANSWERED' : null }
}
function mapReading(id: string, label: string, raw: Json, rule: Json, source: EvidenceRef | null): CheckView {
  const compared = record(raw.comparedValues) ? raw.comparedValues : {}
  const missing = fields(raw.missingInputs)
  const status = oneOf(raw.status, ['SATISFIED', 'NOT_SATISFIED', 'INPUT_NEEDED', 'DIRECT_CHECK_REQUIRED'])
  const summary = adjusted(rule)
  if (summary && Array.isArray(compared.children) && record(compared.children[0])) {
    // 기본 기준의 비교로 내 값·기준을 보이고, 가산 기준은 한 줄로 덧붙인다.
    const base = mapReading(id, label, compared.children[0], summary.base, source)
    const name = field(summary.base.field)
    const tiers = summary.tiers.length ? ` · 출산자녀 ${summary.tiers.map((tier) => tierText(tier, summary.base, name, list(compared.children))).join(' · ')}` : ''
    const other = summary.other ? ' · 가족 상황에 따라 기준이 더 높을 수 있어요' : ''
    // 소득 기준표 설명(연도·가구원 수)은 기본 금액 바로 뒤에 둬, 가산 기준 뒤에 붙어 그쪽 설명처럼 읽히지 않게 한다.
    return { ...base, label: titleOf(rule, label), status, basis: null,
      criterion: `${base.criterion}${base.basis ? ` (${base.basis})` : ''}${tiers}${other}`,
      fields: missing.length ? missing : base.fields, missingReason: missing.length ? 'UNANSWERED' : null }
  }
  if (Array.isArray(compared.children) && compared.children.length) return mapGroup(id, label, rule, raw, compared.children, source)
  const name = field(compared.field ?? rule.field)
  const supplied = display(compared.providedValue, name, text(compared.unit))
  const calculated = compared.comparedValue !== undefined && compared.comparedValue !== compared.providedValue
    ? ` (기준일 기준 ${display(compared.comparedValue, null, text(compared.unit))})` : ''
  const age = rule.operator === 'AGE_RANGE' && typeof compared.comparedValue === 'number' ? `만 ${compared.comparedValue}세` : null
  const mine = compared.vehicleOwned === false ? '자동차 없음' : compared.inputState === 'NOT_APPLICABLE' ? '해당 없음' : age ?? (supplied ? supplied + calculated : null)
  const sourceBasis = record(compared.basis) ? compared.basis : {}
  const basis = [typeof sourceBasis.standardYear === 'number' ? `${sourceBasis.standardYear}년` : '',
    sourceBasis.standardKind === 'URBAN_WORKER_MONTHLY_INCOME' ? '도시근로자 월평균 소득' : '',
    typeof sourceBasis.familyCount === 'number' ? `${sourceBasis.familyCount}인 가구` : '',
    typeof sourceBasis.percent === 'number' ? `${sourceBasis.percent}%` : ''].filter(Boolean).join(' · ') || null
  return { id, label: titleOf(rule, label), status,
    // NOT_APPLIED: 가산 대상 등 이 기준이 그대로 적용되지 않는 사람이라 비교하지 않았다. 판정은 막지 않는다.
    mine, criterion: compared.reason === 'NOT_APPLIED'
      ? `${criterion(compared, rule, name)}(기본)${mine ? ` · 내 정보 ${mine}` : ''} · 가족 상황에 따라 다른 기준이 적용돼요. 아래 '신청 전 직접 확인할 것'을 봐 주세요.`
      : criterion(compared, rule, name), basis, evidence: source,
    fields: missing.length ? missing : name ? [name] : [], missingReason: missing.length ? 'UNANSWERED' : null }
}
function mapCheck(raw: unknown, authored: Json[], content: Json): CheckView {
  const check = object(raw)
  const source = authored.find((item) => item.id === check.id)
  return mapReading(text(check.id), text(check.label), object(check.reading), record(source?.rule) ? source.rule : {}, evidence(content, check.evidenceIds))
}
function guidance(raw: Json, content: Json): GuidanceView {
  return { kind: oneOf(raw.kind, ['REQUIRED_QUALIFICATION', 'APPLICATION_EXCLUSION', 'REQUIRED_CHECK', 'OTHER_ROUTE', 'PRIORITY_OPPORTUNITY', 'PROCEDURE']),
    title: text(raw.title), body: [text(raw.body), ...strings(raw.items)].filter(Boolean).join('\n'), evidence: evidence(content, raw.evidenceIds) }
}
function mapComparison(value: unknown): ComparisonView {
  const data = object(value)
  const content = record(data.content) ? data.content : null
  const freshness = oneOf(data.freshness, ['CURRENT', 'RECHECKING', 'NOT_AVAILABLE'])
  const result = freshness === 'CURRENT' && record(data.result) ? data.result : null
  const base = { announcementId: String(number(data.announcementId)),
    availability: oneOf(data.availability, ['AVAILABLE', 'NOT_STRUCTURED', 'NOT_PUBLISHED', 'CANCELLED']), freshness,
    status: result ? status(result.status) : null,
    profileVersion: number(data.profileVersion), evaluatedProfileVersion: data.evaluatedProfileVersion == null ? null : number(data.evaluatedProfileVersion),
    eligibleOptionIdsInRegion: result ? strings(data.eligibleOptionIdsInRegion) : [],
    referenceDate: result ? text(result.referenceDate) : content ? text(object(content.metadata).referenceDate) : '',
    scopeNote: content ? nullableText(object(content.metadata).scopeNote) : null }
  if (!result || !content) return { ...base, types: [] }
  const authoredPaths = list(content.paths).map(object)
  const paths = list(result.paths).map(object)
  const common = list(result.commonChecks).map((check) => mapCheck(check, list(content.commonChecks).map(object), content))
  const guidances = list(content.guidances).map(object)
  const qualifications = list(content.qualifications).map(object)
  return { ...base, types: list(result.options).map((raw) => {
    const option = object(raw)
    const path = paths.find((item) => item.pathId === option.pathId)
    const authored = authoredPaths.find((item) => item.id === option.pathId)
    const requiredIds = strings(option.requiredQualificationIds)
    const directIds = strings(option.directCheckGuidanceIds)
    const exclusionIds = strings(option.exclusionGuidanceIds)
    // 판정을 막지 않는 신청 전 확인 안내. 이 필드가 생기기 전에 저장된 결과에는 없다.
    const reminderIds = option.reminderGuidanceIds == null ? [] : strings(option.reminderGuidanceIds)
    return {
      key: text(option.optionId), label: text(option.pathLabel), units: text(option.unitLabel), status: status(option.status),
      regionCodes: strings(option.regionCodes),
      missingInputs: fields(option.missingInputs),
      checks: [...common, ...list(path?.checks ?? []).map((check) => mapCheck(check, list(authored?.checks ?? []).map(object), content))],
      qualifications: qualifications.filter((item) => requiredIds.includes(text(item.id))).map((item) => ({
        title: text(item.exactName), body: text(item.conditionsAsWritten), items: item.legalBasis ? [text(item.legalBasis)] : [],
        evidence: evidence(content, item.evidenceIds),
      })),
      guidances: guidances.filter((item) => directIds.includes(text(item.id)) || reminderIds.includes(text(item.id)))
        .map((item) => guidance(item, content)),
      exclusions: guidances.filter((item) => exclusionIds.includes(text(item.id))).map((item) => guidance(item, content)),
    }
  }) }
}

export function createEligibilityRepository(fetcher: typeof fetch = (...args) => globalThis.fetch(...args)): ConditionsRepository {
  const base = `${getApiBaseUrl()}/api/v2`
  async function request(path: string, signal?: AbortSignal, init: RequestInit = {}) {
    const bounded = signal ? AbortSignal.any([signal, AbortSignal.timeout(15_000)]) : AbortSignal.timeout(15_000)
    const response = await fetcher(`${base}${path}`, { credentials: 'include', cache: 'no-store', ...init, signal: bounded })
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) throw new ConditionsSessionError()
      if (response.status === 409) throw new ConditionsConflictError()
      throw new Error('요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.')
    }
    if (response.status === 204) return null
    return response.json() as Promise<unknown>
  }
  async function csrfHeaders(): Promise<Record<string, string>> {
    const response = await fetcher(`${getApiBaseUrl()}/api/auth/csrf`, { credentials: 'include', cache: 'no-store', signal: AbortSignal.timeout(15_000) })
    if (!response.ok) throw new ConditionsSessionError()
    const csrf = object(await response.json())
    const header = oneOf(csrf.headerName, ['X-XSRF-TOKEN', 'X-CSRF-TOKEN'])
    if (typeof csrf.token !== 'string' || !csrf.token) throw new Error('저장을 준비하지 못했어요.')
    return { 'Content-Type': 'application/json', [header]: csrf.token }
  }
  async function loadComparison(ref: AnnouncementRef, signal?: AbortSignal) {
    const region = ref.region ? `?region=${encodeURIComponent(ref.region)}` : ''
    return mapComparison(envelope(await request(`/announcements/${encodeURIComponent(ref.announcementId)}/eligibility${region}`, signal)))
  }
  return {
    async loadProfile(signal) { return stored(envelope(await request('/me/eligibility-profile', signal))) },
    async saveProfile({ expectedVersion, profile }) {
      return stored(envelope(await request('/me/eligibility-profile', undefined, {
        method: 'PUT', headers: await csrfHeaders(), body: JSON.stringify({ expectedVersion, inputContractVersion: 3, profile }),
      })))
    },
    loadComparison,
    async loadListResults(refs, signal) {
      const results = new Map<string, ListConditionResult>()
      // Limit concurrent requests when the loaded list grows.
      for (let start = 0; start < refs.length; start += 4) {
        if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
        const batch = await Promise.all(refs.slice(start, start + 4).map(async (ref) => {
          const view = await loadComparison(ref, signal)
          const best = [...view.types].sort((left, right) => TYPE_STATUS_ORDER[left.status] - TYPE_STATUS_ORDER[right.status])[0]
          return [ref.announcementId, { announcementId: view.announcementId, availability: view.availability, freshness: view.freshness,
            best: best ? { label: best.label, status: best.status } : null,
            eligibleInRegion: view.freshness === 'CURRENT' && view.eligibleOptionIdsInRegion.length > 0,
            missingInputs: [...new Set(view.types.flatMap((item) => item.missingInputs ?? []))] }] as const
        }))
        batch.forEach(([id, result]) => results.set(id, result))
      }
      return results
    },
    async loadProcessing(signal): Promise<EligibilityProcessing> {
      const data = object(envelope(await request('/eligibility/processing', signal)))
      return { profileVersion: number(data.profileVersion), requestedVersion: number(data.requestedVersion),
        state: oneOf(data.state, ['PENDING', 'RUNNING', 'DONE']), failedAttempts: number(data.failedAttempts) }
    },
    async loadDiscoveries(afterAnnouncementId, signal): Promise<DiscoveryPage> {
      const data = object(envelope(await request(`/eligibility/discoveries?afterAnnouncementId=${afterAnnouncementId}&limit=25`, signal)))
      if (typeof data.hasMore !== 'boolean') throw new Error('발견 목록을 확인하지 못했어요.')
      return { nextCursor: number(data.nextCursor), hasMore: data.hasMore, items: list(data.items).map((raw) => {
        const item = object(raw)
        return { eventId: text(item.eventId), rootAnnouncementId: String(number(item.rootAnnouncementId)), announcementId: String(number(item.announcementId)),
          optionIds: strings(item.optionIds), discoveredAt: text(item.discoveredAt) }
      }) }
    },
    async acknowledgeDiscovery(item) {
      await request(`/eligibility/discoveries/${encodeURIComponent(item.rootAnnouncementId)}/ack?eventId=${encodeURIComponent(item.eventId)}`, undefined,
        { method: 'POST', headers: await csrfHeaders() })
    },
  }
}

export const eligibilityRepository = createEligibilityRepository()
