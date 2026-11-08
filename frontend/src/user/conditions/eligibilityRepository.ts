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

function display(value: unknown, name: ProfileField | null = null, unit = ''): string {
  if (typeof value === 'boolean' || value === 'true' || value === 'false') return value === true || value === 'true' ? '예' : '아니요'
  if (typeof value === 'number') {
    if (unit.startsWith('KRW') || name && FIELD_COPY[name].kind === 'money') return `${name && FIELD_COPY[name].monthly ? '월 ' : ''}${formatWon(value)}`
    const suffix: Record<string, string> = { YEARS: '세', MONTHS: '개월', DAYS: '일', PERSONS: '명', PEOPLE: '명', COUNT: '명' }
    return `${value.toLocaleString('ko-KR')}${suffix[unit] ?? ''}`
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
function criterion(compared: Json, rule: Json, name: ProfileField | null): string {
  const unit = text(compared.unit) || (rule.operator === 'AGE_RANGE' ? 'YEARS' : rule.operator === 'MONTHS_SINCE' || name === 'CURRENT_REGION_RESIDENCE_MONTHS' ? 'MONTHS' : name && FIELD_COPY[name].kind === 'count' ? 'PEOPLE' : '')
  if (compared.vehicleOwned === false) return '자동차 미보유 시 차량가액 기준 충족'
  if (compared.comparisonLimit !== undefined) return `${display(compared.comparisonLimit, name, unit)} 이하`
  if (compared.expected !== undefined || rule.operator === 'EQUALS') return display(compared.expected ?? rule.expected, name)
  if (Array.isArray(compared.requiredRegions) || rule.operator === 'REGION_IN') return strings(compared.requiredRegions ?? rule.regions).map(regionLabel).join(' 또는 ')
  if (compared.earliest && compared.latest) return `${text(compared.earliest)} 이후 · ${text(compared.latest)} 이전 (양 끝 날짜 포함)`
  if (rule.operator === 'MONEY_CEILING') return `${display(rule.maximum, name, 'KRW')} 이하`
  if (compared.minimum !== undefined || rule.minimum !== undefined && rule.minimum !== null) {
    return `${display(compared.minimum ?? rule.minimum, null, unit)} 이상 · ${display(compared.maximum ?? rule.maximum, null, unit)} 이하`
  }
  if (rule.operator === 'INCOME_CEILING') return '공고의 가구원수별 소득 기준표에 따름'
  if (rule.operator === 'ALL') return '아래 조건을 모두 충족'
  if (rule.operator === 'ANY') return '아래 조건 중 하나 이상 충족'
  return '공고에 명시된 조건'
}
function mapReading(id: string, label: string, raw: Json, rule: Json, source: EvidenceRef | null): CheckView {
  const compared = record(raw.comparedValues) ? raw.comparedValues : {}
  const name = field(compared.field ?? rule.field)
  const missing = fields(raw.missingInputs)
  const children = Array.isArray(compared.children) ? compared.children.map((child, index) => {
    const childRule = Array.isArray(rule.children) && record(rule.children[index]) ? rule.children[index] : {}
    const childField = field(childRule.field)
    return mapReading(`${id}-${index}`, childField ? FIELD_COPY[childField].label : `${label} 조건 ${index + 1}`, object(child), childRule, source)
  }) : []
  const supplied = display(compared.providedValue, name, text(compared.unit))
  const calculated = compared.comparedValue !== undefined && compared.comparedValue !== compared.providedValue
    ? ` (기준일 환산 ${display(compared.comparedValue, null, text(compared.unit))})` : ''
  const mine = compared.vehicleOwned === false ? '자동차 없음' : compared.inputState === 'NOT_APPLICABLE' ? '해당 없음' : supplied ? supplied + calculated : null
  const sourceBasis = record(compared.basis) ? compared.basis : {}
  const basis = [typeof sourceBasis.standardYear === 'number' ? `${sourceBasis.standardYear}년` : '',
    sourceBasis.standardKind === 'URBAN_WORKER_MONTHLY_INCOME' ? '도시근로자 월평균 소득' : '',
    typeof sourceBasis.familyCount === 'number' ? `${sourceBasis.familyCount}인 가구` : '',
    typeof sourceBasis.percent === 'number' ? `${sourceBasis.percent}%` : ''].filter(Boolean).join(' · ') || null
  return { id, label, status: oneOf(raw.status, ['SATISFIED', 'NOT_SATISFIED', 'INPUT_NEEDED', 'DIRECT_CHECK_REQUIRED']),
    mine, criterion: criterion(compared, rule, name), basis, evidence: source,
    fields: missing.length ? missing : name ? [name] : [], missingReason: missing.length ? 'UNANSWERED' : null, children }
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
    status: result ? status(result.status) : null, headline: result ? nullableText(result.headline) : null,
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
    return {
      key: text(option.optionId), label: text(option.pathLabel), units: text(option.unitLabel), status: status(option.status),
      regionCodes: strings(option.regionCodes), message: text(option.message), missingInputMessage: nullableText(option.missingInputMessage),
      missingInputs: fields(option.missingInputs),
      checks: [...common, ...list(path?.checks ?? []).map((check) => mapCheck(check, list(authored?.checks ?? []).map(object), content))],
      qualifications: qualifications.filter((item) => requiredIds.includes(text(item.id))).map((item) => ({
        title: text(item.exactName), body: text(item.conditionsAsWritten), items: item.legalBasis ? [text(item.legalBasis)] : [],
        evidence: evidence(content, item.evidenceIds),
      })),
      guidances: guidances.filter((item) => directIds.includes(text(item.id))).map((item) => guidance(item, content)),
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
