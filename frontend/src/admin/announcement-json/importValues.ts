import { isRecord } from '../management/managementContract'
import { announcementSections, valueAt } from '../management/fields'
import { mergeSupplyImport } from '../supply/supplyImport'
import type { CorrectionRow } from '../supply/supplyCorrectionApi'
import type { ExtractedAnnouncement, JsonObject, JsonWorkspace, Selection } from './api'

export function parseAnnouncementJson(text: string): ExtractedAnnouncement {
  if (new TextEncoder().encode(text).length > 1_000_000) throw new Error('공고 JSON은 UTF-8 기준 1MB 이하여야 합니다.')
  // Quote integer won tokens before JSON.parse so 18-digit amounts never become rounded JS numbers.
  const exact = text.replace(/("(?:rentalDeposit|monthlyRent|convertedDeposit)"\s*:\s*)(-?(?:0|[1-9]\d*)(?:\.\d+)?)(?=\s*[,}])/g, '$1"$2"')
  const input: unknown = JSON.parse(exact.replace(/^\uFEFF/, ''))
  if (!isRecord(input) || input.schemaVersion !== 'admin-announcement-import/v1' || !isRecord(input.source)
    || !isRecord(input.announcement) || !(input.receptionPlace === null || isRecord(input.receptionPlace))) {
    throw new Error('admin-announcement-import/v1 형식의 공고 JSON을 넣어 주세요. 신규 등록과 같은 형식입니다.')
  }
  for (const [key, maximum] of [['supplyRows', 200], ['schedules', 100], ['attachments', 100], ['unresolvedFields', 200]] as const) {
    if (!Array.isArray(input[key]) || input[key].length > maximum || !input[key].every(isRecord)) throw new Error(`${key}의 배열 형식과 개수를 확인하세요.`)
  }
  for (const row of input.supplyRows as JsonObject[]) {
    if (!isRecord(row.complexReference) || !Array.isArray(row.targets) || row.targets.length > 100 || !row.targets.every(isRecord)) throw new Error('공급행의 complexReference와 targets 형식을 확인하세요.')
  }
  return input as ExtractedAnnouncement
}
const normalize = (value: unknown) => typeof value === 'string' ? value.normalize('NFKC').replace(/\s+/g, '').toLowerCase() : ''
export function isUnresolved(input: ExtractedAnnouncement, path: string, descendants = false) {
  return input.unresolvedFields.some(item => {
    if (typeof item.path !== 'string') return false
    const unresolved = item.path.replace(/^\$\.?/, '')
    return path === unresolved || path.startsWith(`${unresolved}.`) || path.startsWith(`${unresolved}[`)
      || descendants && (unresolved.startsWith(`${path}.`) || unresolved.startsWith(`${path}[`))
  })
}
export function confirmedSupplyRow(input: ExtractedAnnouncement, index: number): JsonObject {
  const prefix = `supplyRows[${index}]`
  const known = (value: JsonObject, path: string) => Object.fromEntries(Object.entries(value).filter(([key]) => !isUnresolved(input, `${path}.${key}`)))
  const row = known(input.supplyRows[index], prefix)
  return { ...row, complexReference: isRecord(row.complexReference) ? known(row.complexReference, `${prefix}.complexReference`) : {},
    targets: (Array.isArray(row.targets) ? row.targets : []).map((target: JsonObject, position: number) => known(target, `${prefix}.targets[${position}]`)) }
}
export function extractedField(input: ExtractedAnnouncement, field: string): unknown {
  if (field === 'originalUrl') return input.source.originalUrl
  if (field.startsWith('receptionPlace.')) return input.receptionPlace?.[field.split('.')[1]]
  return input.announcement[field]
}
export function metadataFields(input: ExtractedAnnouncement, workspace: JsonWorkspace) {
  return announcementSections.flatMap(group => group.fields).map(field => {
    const value = extractedField(input, field.name)
    const path = field.name === 'originalUrl' ? 'source.originalUrl' : field.name.startsWith('receptionPlace.') ? field.name : `announcement.${field.name}`
    const unresolved = isUnresolved(input, path)
    const locked = workspace.detail.scheduleReviewed && ['applicationStartDate', 'applicationEndDate'].includes(field.name)
    const changed = JSON.stringify(valueAt(workspace.detail.data, field.name) ?? null) !== JSON.stringify(value ?? null)
    return { ...field, value, before: valueAt(workspace.detail.data, field.name), available: typeof value === 'string' && value.trim().length > 0 && !unresolved && !locked, changed, locked, unresolved }
  })
}
export function defaultRowDestination(input: JsonObject, rows: CorrectionRow[]): string {
  const reference = isRecord(input.complexReference) ? input.complexReference : {}
  if (!normalize(reference.pnu) || !normalize(input.sourceHousingTypeName)) return ''
  const candidates = rows.filter(row => row.pnu === reference.pnu)
  const exact = candidates.filter(row => !row.excluded && normalize(row.typeName) === normalize(input.sourceHousingTypeName))
  if (exact.length === 1) return exact[0].key
  return candidates.length === 0 ? 'new' : ''
}
export function defaultTargetDestinations(input: JsonObject, row: CorrectionRow | undefined) {
  const targets = Array.isArray(input.targets) ? input.targets : []
  return targets.map((value: unknown) => {
    if (!isRecord(value) || !normalize(value.target)) return ''
    if (!row) return 'new'
    const matches = row.targets.filter(target => !target.excluded && normalize(target.target) === normalize(value.target)
      && normalize(target.supplyRank) === normalize(value.supplyRank))
    if (matches.length === 1 && matches[0].id) return String(matches[0].id)
    return matches.length === 0 ? 'new' : ''
  })
}
export function defaultChildDestination(value: JsonObject, candidates: { id: number; data: JsonObject }[], kind: 'schedule' | 'attachment') {
  const matches = candidates.filter(item => kind === 'attachment'
    ? value.fileUrl && value.fileUrl === item.data.fileUrl
    : value.scheduleType && value.scheduleType === item.data.scheduleType && normalize(value.name) === normalize(item.data.name))
  if (matches.length === 1) return String(matches[0].id)
  return matches.length === 0 ? 'new' : ''
}
function knownFields(input: JsonObject, fields: [string, string][]) {
  return Object.fromEntries(fields.filter(([from]) => input[from] !== null && input[from] !== undefined).map(([from, to]) => [to, input[from]]))
}
function money(value: unknown): string | null {
  if (value === null || value === undefined) return null
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return String(value)
  if (typeof value === 'string' && /^\d{1,18}$/.test(value)) return value
  throw new Error('금액은 원 단위 18자리 이하 정수 또는 숫자 문자열이어야 합니다. 지수·소수·만원 표기는 사용할 수 없습니다.')
}
export function mergeAnnouncementRows(rows: CorrectionRow[], input: ExtractedAnnouncement, destinations: string[], targetDestinations: string[][]) {
  const converted = input.supplyRows.map((_source, index) => {
    const row = confirmedSupplyRow(input, index)
    const reference = isRecord(row.complexReference) ? row.complexReference : {}
    const fields = { ...knownFields(reference, [['sourceComplexName', 'complexName'], ['pnu', 'pnu']]), ...knownFields(row, [
      ['sourceHousingTypeName', 'typeName'], ['expectedMoveInMonth', 'moveInMonth'], ['supplyCategory', 'category'], ['totalSupplyHouseholdCount', 'householdCount'], ['exclusiveArea', 'exclusiveArea'], ['supplyArea', 'supplyArea']]) }
    const existing = rows.find(item => item.key === destinations[index])
    if (existing?.groupId) delete fields.householdCount
    const targets = (Array.isArray(row.targets) ? row.targets : []).flatMap((item: unknown, position: number) => {
      const destination = targetDestinations[index]?.[position]
      if (!destination || !isRecord(item)) return []
      const values = knownFields(item, [['target', 'target'], ['supplyRank', 'supplyRank'], ['supplyHouseholdCount', 'householdCount'], ['reserveCount', 'reserveCount'], ['applicationCondition', 'condition']])
      for (const [from, to] of [['rentalDeposit', 'deposit'], ['monthlyRent', 'rent'], ['convertedDeposit', 'convertedDeposit']]) {
        if (item[from] !== null && item[from] !== undefined) values[to] = money(item[from])
      }
      return [{ ...values, ...(destination === 'new' ? {} : { id: Number(destination) }) }]
    })
    return { ...fields, targets }
  })
  return mergeSupplyImport(rows, { rows: converted }, destinations)
}
export function childSelections(destinations: string[]): Selection[] {
  return destinations.flatMap((value, inputIndex) => value ? [{ inputIndex, existingId: value === 'new' ? null : Number(value) }] : [])
}
