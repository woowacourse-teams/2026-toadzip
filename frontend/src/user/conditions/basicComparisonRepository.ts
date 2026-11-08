import { getApiBaseUrl } from '../../api/apiBaseUrl'
import { ConditionsConflictError, ConditionsSessionError, type AnnouncementRef, type CheckView, type ComparisonView, type ConditionProfile, type ConditionsRepository, type EvidenceRef, type ListConditionResult, type ProfileField, type StoredConditionProfile, type TypeView } from './conditionsModel'

type Json = Record<string, unknown>
type ApiEnvelope<T> = { readonly data: T }

function record(value: unknown): value is Json { return typeof value === 'object' && value !== null && !Array.isArray(value) }
function envelope<T>(value: unknown): T {
  if (!record(value) || !('data' in value)) throw new Error('비교 응답을 확인하지 못했어요.')
  return (value as unknown as ApiEnvelope<T>).data
}
function stored(value: unknown): StoredConditionProfile {
  if (!record(value) || !Number.isSafeInteger(value.version) || !record(value.profile) || !record(value.profile.facts)) throw new Error('내 조건 응답을 확인하지 못했어요.')
  return { version: Number(value.version), savedAt: typeof value.updatedAt === 'string' ? value.updatedAt : null, profile: value.profile as unknown as ConditionProfile }
}
function asText(value: unknown, fallback = ''): string { return typeof value === 'string' ? value : fallback }
function formatValue(value: unknown): string | null {
  if (typeof value === 'number') return new Intl.NumberFormat('ko-KR').format(value)
  if (typeof value === 'string') return value
  if (typeof value === 'boolean') return value ? '예' : '아니요'
  if (record(value) && typeof value.date === 'string') return value.date
  return null
}
function evidence(result: Json, ids: unknown): EvidenceRef | null {
  if (!Array.isArray(ids) || !Array.isArray(result.evidences) || !Array.isArray(result.documents)) return null
  const item = result.evidences.find((candidate) => record(candidate) && candidate.id === ids[0])
  if (!record(item)) return null
  const document = result.documents.find((candidate) => record(candidate) && candidate.id === item.documentId)
  return { page: Number(item.firstPage) || 1, location: typeof item.location === 'string' ? item.location : null, fileName: record(document) && typeof document.fileName === 'string' ? document.fileName : null }
}
function mapCheck(value: unknown, result: Json, missingInputs: unknown): CheckView | null {
  if (!record(value) || !record(value.reading)) return null
  const reading = value.reading
  const statusMap: Record<string, CheckView['status']> = { SATISFIED: 'SATISFIED', NOT_SATISFIED: 'NOT_SATISFIED', INPUT_NEEDED: 'INPUT_NEEDED', DIRECT_CHECK_REQUIRED: 'DIRECT_CHECK_REQUIRED' }
  const status = statusMap[String(reading.status)]
  if (!status) return null
  const compared = record(reading.comparedValues) ? reading.comparedValues : {}
  const field = typeof compared.field === 'string' ? compared.field as ProfileField : null
  const fields = Array.isArray(reading.missingInputs) ? reading.missingInputs.filter((x): x is ProfileField => typeof x === 'string') : field ? [field] : []
  const skipped = Array.isArray(missingInputs) && missingInputs.some((item) => record(item) && item.skipped === true && fields.includes(item.field as ProfileField))
  const mine = formatValue(compared.providedValue ?? compared.comparedValue)
  let criterion = ''
  if (compared.comparisonLimit !== undefined) criterion = `최대 ${formatValue(compared.comparisonLimit) ?? ''}${typeof compared.unit === 'string' ? compared.unit : ''}`
  else if (compared.minimum !== undefined || compared.maximum !== undefined) criterion = `${formatValue(compared.minimum) ?? ''}~${formatValue(compared.maximum) ?? ''}`
  else if (compared.expected !== undefined) criterion = formatValue(compared.expected) ?? ''
  else if (Array.isArray(compared.requiredRegions)) criterion = compared.requiredRegions.filter((x): x is string => typeof x === 'string').join(', ')
  const basis = record(compared.basis) ? Object.values(compared.basis).filter((x): x is string | number => typeof x === 'string' || typeof x === 'number').join(' · ') || null : null
  return {
    id: asText(value.id), label: asText(value.label), status, mine, criterion,
    basis, evidence: evidence(result, value.evidenceIds), fields,
    missingReason: status === 'INPUT_NEEDED' ? skipped ? 'FAMILY_SKIPPED' : 'UNANSWERED' : null,
  }
}
function mapComparison(ref: AnnouncementRef, value: unknown): ComparisonView {
  if (!record(value)) throw new Error('비교 응답을 확인하지 못했어요.')
  const availability = String(value.availability)
  const result = record(value.result) ? value.result : null
  if (availability === 'OUT_OF_SCOPE' || result?.scope === 'OUT_OF_SCOPE') return { announcementId: ref.announcementId, availability: 'OUT_OF_SCOPE', referenceDate: '', requiredQualification: null, types: [] }
  if (!result || availability !== 'AVAILABLE') return { announcementId: ref.announcementId, availability: 'NOT_STRUCTURED', referenceDate: '', requiredQualification: null, types: [] }
  const directChecks = Array.isArray(result.directChecks) ? result.directChecks.filter(record) : []
  const required = directChecks.filter((x) => x.kind === 'REQUIRED_QUALIFICATION')
  const requiredQualification = required.length ? {
    title: asText(required[0].title), body: asText(required[0].body),
    items: [...new Set(required.flatMap((x) => Array.isArray(x.items) ? x.items.filter((i): i is string => typeof i === 'string') : []))],
    evidence: evidence(result, required.flatMap((x) => Array.isArray(x.evidenceIds) ? x.evidenceIds : [])),
  } : null
  const options = Array.isArray(result.options) ? result.options.filter(record) : []
  const types: TypeView[] = options.map((option) => {
    const guides = directChecks.filter((g) => Array.isArray(option.directCheckIds) && option.directCheckIds.includes(g.id))
    const checks = (Array.isArray(option.checks) ? option.checks : []).map((check) => mapCheck(check, result, option.missingInputs)).filter((x): x is CheckView => x !== null)
    return {
      key: asText(option.id), label: asText(option.pathLabel, asText(option.unitLabel)),
      coverage: option.coverage === 'COMMON_ONLY' ? 'COMMON_ONLY' : 'GENERAL',
      status: ['MATCHED', 'PARTIALLY_COMPARED', 'INPUT_NEEDED', 'NOT_MATCHED', 'NOT_EVALUATED'].includes(String(option.status)) ? option.status as TypeView['status'] : 'NOT_EVALUATED',
      units: asText(option.unitLabel), checks,
      guidances: guides.map((g) => ({ kind: g.kind as TypeView['guidances'][number]['kind'], title: asText(g.title), body: asText(g.body), evidence: evidence(result, g.evidenceIds) })),
    }
  })
  return { announcementId: ref.announcementId, availability: 'AVAILABLE', referenceDate: asText(result.referenceDate), requiredQualification, types }
}

export function createBasicComparisonRepository(fetcher: typeof fetch = (...args) => globalThis.fetch(...args)): ConditionsRepository {
  const base = `${getApiBaseUrl()}/api/v2`
  async function request(path: string, signal?: AbortSignal, init: RequestInit = {}) {
    const bounded = signal ? AbortSignal.any([signal, AbortSignal.timeout(15_000)]) : AbortSignal.timeout(15_000)
    const response = await fetcher(`${base}${path}`, { credentials: 'include', cache: 'no-store', ...init, signal: bounded })
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) throw new ConditionsSessionError()
      if (response.status === 409) throw new ConditionsConflictError()
      throw new Error('요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.')
    }
    return response.json() as Promise<unknown>
  }
  async function loadComparison(ref: AnnouncementRef, signal?: AbortSignal): Promise<ComparisonView> {
    const data = envelope(await request(`/announcements/${encodeURIComponent(ref.announcementId)}/basic-comparison`, signal))
    return mapComparison(ref, data)
  }
  return {
    async loadProfile(signal) {
      const data = envelope(await request('/me/eligibility-profile', signal))
      return stored(data)
    },
    async saveProfile({ expectedVersion, profile }) {
      const csrfResponse = await fetcher(`${getApiBaseUrl()}/api/auth/csrf`, { credentials: 'include', cache: 'no-store' })
      if (!csrfResponse.ok) throw new ConditionsSessionError()
      const csrf: unknown = await csrfResponse.json()
      if (!record(csrf) || typeof csrf.headerName !== 'string' || !['X-XSRF-TOKEN', 'X-CSRF-TOKEN'].includes(csrf.headerName)
        || typeof csrf.token !== 'string' || !csrf.token) throw new Error('저장을 준비하지 못했어요.')
      const data = envelope(await request('/me/eligibility-profile', undefined, {
        method: 'PUT', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ expectedVersion, inputContractVersion: 3, profile }),
      }))
      return stored(data)
    },
    loadComparison,
    async loadListResults(refs, signal) {
      const entries = await Promise.all(refs.map(async (ref): Promise<[string, ListConditionResult]> => {
        const view = await loadComparison(ref, signal)
        const matched = view.types.find((type) => type.coverage === 'GENERAL' && type.status === 'MATCHED')
        const available = view.types.find((type) => type.coverage === 'GENERAL' && type.status !== 'NOT_EVALUATED')
        const best = matched ?? available ?? view.types[0]
        return [ref.announcementId, { announcementId: ref.announcementId, availability: view.availability, best: best ? { label: best.label, coverage: best.coverage, status: best.status } : null, generalMatched: Boolean(matched) }]
      }))
      return new Map(entries)
    },
  }
}

export const basicComparisonRepository = createBasicComparisonRepository()
