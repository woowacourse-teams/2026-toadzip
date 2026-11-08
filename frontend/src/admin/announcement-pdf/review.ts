import type { ExtractedAnnouncement, HousingTypeValue, JsonObject, JsonUpdateInput, JsonWorkspace } from '../announcement-json/api'
import { blankRow, blankTarget, nextDisplayOrder, type CorrectionRow, type CorrectionTarget, type LinkedHousingType } from '../supply/supplyCorrectionApi'
import { evidenceFor, noteFor, text, type Evidence, type Extraction, type Subtype } from './extraction'
import { createdTypeName, foldSameHousingType, renamedTypeName, withoutTrailingParentheses } from './subtypes'
import { applicationTypeKey } from '../supply/applicationType'
import { announcementHousingType, type AnnouncementType } from './areas'
import { areaValue, defaultTargetDestinations, missingHousingType, originalDate, otherTypeNames, pickSubtypes, proposeRows, proposeSchedules, type HousingCandidates, type RowProposal, type ScheduleProposal, canonicalTarget, describeRow } from './matching'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'

type Kind = 'text' | 'count' | 'area' | 'money' | 'date' | 'datetime' | 'month' | 'method' | 'scheduleType' | 'url' | 'housingType'
export type CellState = 'unresolved' | 'notApplicable' | 'invalid' | 'confirm' | 'same' | 'changed' | 'new'
export type ValueMode = 'keep' | 'extracted' | 'manual'
export type Cell = { path: string; field: string; label: string; kind: Kind; before: unknown; value: unknown; selected: boolean; edited: boolean; reviewed: boolean; mode: ValueMode; input?: string; manualInput?: string; extracted: { value: unknown; state: CellState; note: string }; locked: boolean; note: string; state: CellState; evidence: Evidence | null }
type FieldSpec = [field: string, label: string, kind: Kind, target?: string]
export const rowFields: FieldSpec[] = [['sourceHousingTypeName', '이 공고의 주택형 표기', 'text', 'typeName'], ['exclusiveArea', '전용면적 (㎡)', 'area'], ['supplyArea', '공급면적 (㎡)', 'area'], ['totalSupplyHouseholdCount', '모집수', 'count', 'householdCount'], ['expectedMoveInMonth', '입주 예정월', 'month', 'moveInMonth'], ['housingTypeId', '연결 주택타입', 'housingType']]
// 공급 순위는 사용자 화면에 쓰지 않고 공고문의 신분별 순위와도 맞지 않아 PDF 보정에서 가져오지 않는다. 기존 값은 그대로 둔다.
export const targetFields: FieldSpec[] = [['target', '공급 대상', 'text'], ['supplyHouseholdCount', '대상별 모집수', 'count', 'householdCount'], ['reserveCount', '예비자 수', 'count'], ['rentalDeposit', '보증금 (원)', 'money', 'deposit'], ['monthlyRent', '월 임대료 (원)', 'money', 'rent'], ['convertedDeposit', '전환보증금 (원)', 'money'], ['applicationCondition', '신청 조건', 'text', 'condition']]
export const scheduleFields: FieldSpec[] = [['scheduleType', '일정 유형', 'scheduleType'], ['name', '일정명', 'text'], ['startAt', '시작 일시', 'datetime'], ['endAt', '종료 일시', 'datetime']]
export const metadataFields: FieldSpec[] = [['postedDate', '공고일', 'date'], ['applicationStartDate', '접수 시작일', 'date'], ['applicationEndDate', '접수 종료일', 'date'], ['winnerAnnouncementDate', '당첨자 발표일', 'date']]
export const receptionFields: FieldSpec[] = [['name', '접수처명', 'text'], ['method', '접수 방식', 'method'], ['address', '접수처 주소', 'text'], ['contact', '연락처', 'text'], ['url', '접수처 URL', 'url']]
export type TargetReview = { destination: string; cells: Cell[] }
/** 세부 타입이 연결할 주택형. 기존 주택형 ID, PDF 형명·면적으로 만들 새 주택형('new'), 연결 안 함('none'), 아직 못 정함(null)이다. */
export type SubtypeChoice = number | 'new' | 'none' | null
/** options는 전용면적(면적이 없으면 이름)이 맞는 단지 주택형 ID다. 둘 이상이거나 면적이 없으면 화면에서 고른다. */
export type SubtypeReview = Subtype & { choice: SubtypeChoice; options: number[] }
export type RowReview = { proposal: RowProposal; include: boolean; reviewed: boolean; key: string; cells: Cell[]; targets: TargetReview[]
  /**
   * 세부 타입 묶음 행(26A/B/C)의 세부 타입마다 연결할 주택형. 공급행 하나가 모집수를 갖고 이 주택형을 모두 가리킨다.
   * 대표 주택형은 연결 주택형 칸 대신 여기서 정한다(representativeSubtype).
   */
  subtypes?: SubtypeReview[]
  /** 주택형 기본 임대조건으로 쓸 대상 순번. null이면 지금 값 유지, 없으면 housingTypePrice가 정한 기본값 */
  priceTarget?: number | null
  /**
   * 저장할 곳의 지금 주택타입이 이 행의 공고문 주택타입 이름과 다를 때 다시 연결할 단지 주택타입. 이름이 같은 주택타입 ID, 단지에 없으면
   * 'new'(저장할 때 새로 만듦). 전용면적을 PDF 값으로 반영하고 연결 주택타입 칸을 직접 고르지 않았을 때만 쓴다(relinkTarget).
   */
  relinkTo?: number | 'new'
  /** 이 행이 연결할 단지 주택타입의 공고문 이름. PDF 주택타입 열의 이름, 없으면 공고 주택형(신청형)이다(housingTypeNameOf). */
  housingTypeName?: string | null
  /** 이 공고의 다른 공고문 주택타입(otherTypeNames). 그 이름이나 같은 면적이 있으면 이름 형식만 다른 단지 주택타입에 잇지 않는다. */
  otherTypeNames?: AnnouncementType[] }
export type ScheduleReview = { proposal: ScheduleProposal; include: boolean; reviewed: boolean; cells: Cell[] }
export type Review = { extraction: Extraction; rows: RowReview[]; schedules: ScheduleReview[]; metadata: Cell[]; reception: Cell[]
  /** PDF 행이 가리키지 않아 그대로 둘 기존 공급행 중 관리자가 이번 보정에서 제외하기로 고른 행(leftoverRows). 지울 수 있는 행은 지운다. */
  excludedRows?: string[] }
export const cellStates: Record<Cell['state'], string> = { confirm: '확인 필요', unresolved: '미확인 · 현재값 유지', notApplicable: '해당 없음', invalid: '형식 확인 필요', same: '같음', changed: '변경', new: '추가' }
/** 접수 방식·일정 유형의 화면 표기 */
export const choices = {
  method: [['ONLINE', '온라인'], ['VISIT', '방문'], ['MAIL', '우편'], ['ETC', '기타']],
  scheduleType: [['APPLICATION', '접수'], ['DOCUMENT_SUBMISSION', '서류 제출'], ['WINNER_ANNOUNCEMENT', '당첨자 발표'], ['CONTRACT', '계약'], ['MOVE_IN', '입주'], ['ETC', '기타']],
}
/** 일정 일시는 자정이면 날짜만, 시각이 있으면 시:분까지 보여 준다(2026-06-01T00:00:00 → 2026-06-01). */
export function shownDateTime(value: unknown) {
  const match = typeof value === 'string' ? /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(?::\d{2})?$/.exec(value) : null
  if (!match) return shown(value)
  return match[2] === '00' && match[3] === '00' ? match[1] : `${match[1]} ${match[2]}:${match[3]}`
}
export function shown(value: unknown) { return value === null || value === undefined || value === '' ? '미확인' : String(value) }
/** 금액은 세 자리마다 쉼표를 넣어 기존 값·PDF 값의 자릿수를 바로 대조하게 한다. 숫자가 아니면 그대로 보인다. */
export function shownMoney(value: unknown) {
  const text = shown(value)
  return /^\d+$/.test(text) ? Number(text).toLocaleString('ko-KR') : text
}

/** 저장된 일시는 초까지(10:00:00), PDF와 입력칸은 분까지(10:00) 적으므로 초를 붙여 같은 시각을 변경으로 보지 않는다. */
const fullDateTime = (value: string) => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value) ? `${value}:00` : value
function normalized(kind: Kind, value: unknown): unknown {
  if (value === null || value === undefined || value === '') return null
  if (kind === 'money' && typeof value === 'number' && Number.isSafeInteger(value)) return String(value)
  if (typeof value !== 'string') return value
  if (kind === 'month') {
    // 공고문은 입주 연월을 2020.10이나 일까지 적은 2020.10.19로 쓴다. 연월로 읽을 수 있으면 YYYY-MM으로 맞춘다.
    const month = /^(\d{4})\s*[.\-/년]\s*(\d{1,2})(?:\s*[.\-/월].*)?$/.exec(value.trim())
    if (month && Number(month[2]) >= 1 && Number(month[2]) <= 12) return `${month[1]}-${month[2].padStart(2, '0')}`
  }
  return kind === 'datetime' ? fullDateTime(value.trim()) : value.trim()
}
export function validCellValue(kind: Kind, value: unknown): boolean {
  if (kind === 'housingType' && value === null) return true
  if (value === null || value === undefined || value === '') return false
  if (kind === 'count') return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 2147483647
  if (kind === 'housingType') return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 && value <= 2147483647
  if (kind === 'area') return areaValue(value)
  if (kind === 'money') return typeof value === 'string' && /^\d{1,18}$/.test(value)
  if (typeof value !== 'string' || !value.trim() || value.length > 255) return false
  if (kind === 'date' || kind === 'datetime') {
    const format = kind === 'date' ? /^\d{4}-\d{2}-\d{2}$/ : /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/
    const date = value.slice(0, 10)
    return format.test(value) && Number.isFinite(Date.parse(value)) && new Date(date).toISOString().slice(0, 10) === date
      && (kind !== 'datetime' || Number(value.slice(11, 13)) < 24 && Number(value.slice(14, 16)) < 60 && Number(value.slice(17, 19) || '0') < 60)
  }
  if (kind === 'month') return /^\d{4}-(0[1-9]|1[0-2])$/.test(value)
  if (kind === 'method') return ['ONLINE', 'VISIT', 'MAIL', 'ETC'].includes(value)
  if (kind === 'scheduleType') return ['APPLICATION', 'DOCUMENT_SUBMISSION', 'WINNER_ANNOUNCEMENT', 'CONTRACT', 'MOVE_IN', 'ETC'].includes(value)
  // 서버 검증(^https?://[^\s]+$)과 같이 소문자 http(s)://로 시작하고 공백이 없어야 한다.
  if (kind === 'url') { try { return /^https?:\/\/\S+$/.test(value) && Boolean(new URL(value)) } catch { return false } }
  return true
}
const compact = (text: string) => text.replace(/\s+/g, '')
// 공급대상 괄호는 병합 셀이라 표 한 줄 인용에 없을 수 있어, 괄호 앞 표기(39(창업인) → 39, 24A)만 대조한다.
const typeCore = (name: string) => compact(name).replace(/\(.*$/, '')
const numberKey = (value: unknown) => typeof value === 'string' ? value.trim().replace(/\d+(?:\.\d+)?/g, number => String(Number(number))) : null
function cells(extraction: Extraction, prefix: string, specs: FieldSpec[], source: JsonObject, before: JsonObject = {}): Cell[] {
  return specs.map(([field, label, kind, target = field]) => {
    const path = `${prefix}.${field}`, value = normalized(kind, source[field]), previous = normalized(kind, before[target])
    const unused = noteFor(extraction.notApplicable, path), evidence = evidenceFor(extraction.evidence, path)
    // 미확인으로 적혔어도 근거와 올바른 값이 있으면(목록 모집수와 다름 같은 참고) 막지 않고 확인 후 고르게 한다. 안내는 칸 옆에 남긴다.
    const unresolvedNote = noteFor(extraction.unresolved, path)
    const informational = unresolvedNote && evidence !== null && value !== null && validCellValue(kind, value) ? unresolvedNote : null
    const missing = informational ? null : unresolvedNote
    const areaOnly = field === 'sourceHousingTypeName' && areaOnlyTypeName(value)
    // 주택형 표기는 숫자 앞뒤의 0만 다르면(074.9900A ↔ 74.9900A) 같은 표기로 보고 기존 표기를 유지한다.
    const sameValue = JSON.stringify(value) === JSON.stringify(previous) || field === 'sourceHousingTypeName' && numberKey(value) === numberKey(previous)
    let state: Cell['state'] = unused || areaOnly || kind === 'housingType' && value === null ? 'notApplicable' : missing || value === null ? 'unresolved' : !validCellValue(kind, value) ? 'invalid' : sameValue ? 'same' : previous === null ? 'new' : 'changed'
    const moreDetailed = typeof previous === 'string' && typeof value === 'string' && previous.length > value.length && previous.includes(value) && prefix === 'receptionPlace'
    const foreign = kind === 'text' ? foreignCharacters(value) : ''
    // 모델이 PDF 공급 표 대신 함께 보낸 LH 주택형 이름(24 → 24A)을 쓰기도 한다. 근거 인용에 없는 표기는 사람이 대조한다.
    // 하위 항목(임대조건 행)에서 빌려 온 근거에는 주택형이 원래 없으므로, 공급행 자기 근거나 이 칸의 근거일 때만 본다.
    // 접은 행의 표기(26A·26B)는 표기마다 대조한다.
    // 접은 행은 뒤에 접힌 행의 근거가 그 행이 더한 대상 칸으로 옮겨 가므로, 그 공급행 아래 모든 근거 인용 중 하나에 각 표기가 있으면 된다.
    const quotes = extraction.evidence.filter(item => item.path === prefix || item.path.startsWith(`${prefix}.`)).map(item => compact(item.quote))
    const unquoted = field === 'sourceHousingTypeName' && typeof value === 'string' && evidence !== null && [prefix, path].includes(evidence.path)
      && !value.split('·').every(part => quotes.some(quote => quote.includes(typeCore(part))))
    if (['changed', 'new'].includes(state) && (!evidence || moreDetailed || foreign || unquoted || informational)) state = 'confirm'
    const note = (kind === 'housingType' && value === null ? previous === null ? autoTypeNote : keepTypeNote : undefined) ?? (informational ? `참고: ${informational.reason}` : undefined) ?? unused?.reason ?? (areaOnly ? '면적만 적힌 값이라 주택형 표기로 쓰지 않습니다.' : undefined)
      ?? (foreign && ['confirm'].includes(state) ? `한자·외국 문자(${foreign})가 섞여 있습니다. 원문과 대조해 직접 수정하세요.` : undefined) ?? missing?.reason ?? (moreDetailed ? '현재값이 더 자세합니다.' : !evidence ? '이 항목의 PDF 근거를 직접 확인하세요.' : unquoted && state === 'confirm' ? `PDF 근거 인용에 '${String(value)}'가 없습니다. LH 주택형 이름일 수 있으니 공급 표 표기와 대조하세요.` : '')
    const selected = state === 'changed' || state === 'new'
    return { path, field, label, kind, before: previous, value, selected, edited: false, reviewed: false, mode: selected ? 'extracted' : 'keep', extracted: { value, state, note }, locked: false, note, state, evidence }
  })
}
/** PDF 추출값을 고를 수 없는 까닭. 해당 없음과 고를 수 있는 값은 빈 문자열이다. */
export function extractedBlockReason(cell: Cell) {
  if (cell.locked || cell.kind === 'housingType') return ''
  if (cell.extracted.state === 'invalid') return '형식이 올바르지 않습니다. 원문과 대조해 직접 입력하세요.'
  if (cell.extracted.state !== 'unresolved') return ''
  return cell.extracted.value == null ? 'PDF에서 값을 읽지 못했습니다.' : '근거 인용이 없어 원문 위치를 확인할 수 없습니다.'
}
export function editCell(cell: Cell, input: string): Cell {
  const value = input === '' ? null : cell.kind === 'area' || cell.kind === 'count' || cell.kind === 'housingType' ? Number(input) : cell.kind === 'datetime' ? fullDateTime(input) : input
  const state = value === null && cell.kind !== 'housingType' ? 'unresolved' : !validCellValue(cell.kind, value) ? 'invalid' : JSON.stringify(value) === JSON.stringify(cell.before) ? 'same' : cell.before === null ? 'new' : 'changed'
  return { ...cell, input, manualInput: input, value, state, mode: 'manual', selected: state === 'changed' || state === 'new', edited: true, reviewed: true, note: '' }
}
export function chooseCellValue(cell: Cell, mode: ValueMode): Cell {
  if (cell.locked) return cell
  if (mode === 'keep') return { ...cell, ...cell.extracted, mode, selected: false, edited: false, reviewed: true, input: undefined }
  if (mode === 'manual') return editCell(cell, cell.manualInput ?? String(cell.value ?? ''))
  const original = cell.extracted
  return { ...cell, ...original, mode, input: undefined, edited: false, reviewed: true, selected: validCellValue(cell.kind, original.value) && !['unresolved', 'notApplicable', 'invalid'].includes(original.state) }
}
export function preserveCellChoice(fresh: Cell, previous?: Cell): Cell {
  if (!previous) return fresh
  const updated = chooseCellValue({ ...fresh, manualInput: previous.manualInput }, previous.mode)
  return { ...updated, reviewed: previous.reviewed, selected: !fresh.locked && updated.selected }
}
/**
 * 세부 타입마다 단지 주택형을 고른다. 전용면적이 맞는 주택형이 하나로 좁혀지면 그 주택형, 단지를 정했는데 같은 면적 주택형이 없으면
 * PDF 형명·면적으로 새 주택형을 만들고, 그 밖에는 화면에서 고르게 비워 둔다.
 */
function subtypeReviews(extraction: Extraction, index: number, proposal: RowProposal): SubtypeReview[] {
  return pickSubtypes(extraction, index, proposal.candidates).map((pick, at) => {
    const subtype = extraction.supplyRows[index].subtypes[at]
    // 단지를 정하기 전에는 고를 주택형이 없다(단지 정하기가 확인 항목이다). 면적을 모르는 세부 타입은 연결하지 않는다.
    const choice: SubtypeChoice = proposal.complexId === null ? null : subtype.exclusiveArea === null ? 'none' : pick.housingTypeId ?? 'new'
    return { ...subtype, choice, options: pick.options.map(type => type.id) }
  })
}
/** 연결 주택형 칸이 비어 있을 때. 서버가 단지·전용면적으로 주택형을 정한다. */
const keepTypeNote = '지금 연결한 주택타입을 유지합니다. 다른 주택타입에 연결할 때만 직접 고르세요.'
const autoTypeNote = '저장하면 같은 단지·전용면적(소수 둘째 자리) 주택타입에 연결하고, 없으면 PDF 주택타입·면적으로 새로 만듭니다. 다른 주택타입에 연결할 때만 직접 고르세요.'
/** 대표 주택형이 될 세부 타입 번호. 저장 위치의 지금 주택형을 고른 세부 타입이 있으면 그 타입, 없으면 연결하는 첫 세부 타입이다. 없으면 -1. */
function representativeOf(subtypes: SubtypeReview[], current: unknown) {
  const kept = typeof current === 'number' ? subtypes.findIndex(subtype => subtype.choice === current) : -1
  return kept >= 0 ? kept : subtypes.findIndex(subtype => subtype.choice !== 'none')
}
export function representativeSubtype(row: RowReview) {
  return row.subtypes ? representativeOf(row.subtypes, row.cells.find(cell => cell.field === 'housingTypeId')?.before) : -1
}
/** 세부 타입 묶음 행의 대표 주택형을 PDF 형명·면적으로 새로 만드는지 */
export function newRepresentative(row: RowReview) {
  return row.subtypes ? row.subtypes[representativeSubtype(row)]?.choice === 'new' : false
}
/** 저장하면 서버가 단지·전용면적으로 주택형을 정하는 행인지. 세부 타입이 없고 연결 주택형을 비워 둔 채 전용면적이 있는 행이다. */
export function autoHousingType(row: RowReview) {
  if (row.subtypes || linkedHousingTypeId(row) !== null) return false
  const area = row.cells.find(cell => cell.field === 'exclusiveArea')
  return typeof savedValue(area!) === 'number' && Number(savedValue(area!)) > 0
}
/**
 * 연결 주택형을 정하지 않은 행이 저장할 때 연결할 단지 주택타입. 공고문 주택타입 이름(housingTypeName, 없으면 공고 표기)이 같은 주택타입이고,
 * 없으면 null이라 저장할 때 그 이름으로 새로 만든다(assembleReview). 단지 주택타입은 공고문 표기 이름으로 나누므로 면적이 같아도 이름이 다르면 잇지 않는다.
 */
export function expectedHousingType(row: RowReview) {
  if (!autoHousingType(row)) return null
  const value = (field: string) => { const cell = row.cells.find(item => item.field === field); return cell ? savedValue(cell) : null }
  return announcementHousingType(row.proposal.candidates, housingTypeNameOf(row), value('exclusiveArea'), row.otherTypeNames)
}
/** 행이 연결할 단지 주택타입의 공고문 이름. PDF 주택타입 열 이름, 없으면 저장할 공고 표기의 첫 표기(신청자 표기 제외)다. */
export function housingTypeNameOf(row: RowReview) {
  const label = row.cells.find(item => item.field === 'sourceHousingTypeName'), shown = label ? savedValue(label) : null
  return createdTypeName(row.housingTypeName ?? (typeof shown === 'string' ? shown : ''))
}
/** 세부 타입 묶음 행의 연결 주택형 칸은 대표 세부 타입의 주택형을 보여 주기만 한다. */
function syncRepresentative(row: RowReview): RowReview {
  if (!row.subtypes) return row
  const choice = row.subtypes[representativeSubtype(row)]?.choice, value = typeof choice === 'number' ? choice : null
  return { ...row, cells: row.cells.map(cell => {
    if (cell.field !== 'housingTypeId') return cell
    const state: CellState = JSON.stringify(value) === JSON.stringify(cell.before) ? 'same' : cell.before === null ? 'new' : 'changed'
    return { ...cell, value, state, extracted: { ...cell.extracted, value, state }, locked: true, selected: false, mode: 'keep',
      note: choice === 'new' ? '아래 세부 타입 연결에서 정합니다. 대표 주택형을 새로 만듭니다.' : '아래 세부 타입 연결에서 정합니다.' }
  }) }
}
/** 세부 타입 하나의 연결 주택형을 바꾸고 대표 주택형 칸을 맞춘다. 대표가 바뀌면 주택형 가격 선택을 다시 정한다. */
export function chooseSubtype(row: RowReview, at: number, choice: SubtypeChoice): RowReview {
  const next = syncRepresentative({ ...row, subtypes: row.subtypes?.map((subtype, item) => item === at ? { ...subtype, choice } : subtype) })
  return linkedHousingTypeId(next) === linkedHousingTypeId(row) && newRepresentative(next) === newRepresentative(row) ? next : { ...next, priceTarget: undefined }
}
const sharedCountNote = '공고문이 이 행의 모집수를 다른 단지의 공급행과 합쳐 적어 행 모집수를 정하지 않았습니다. 필요하면 이 공급행의 모집수를 직접 입력하세요.'
/** 저장할 행이 속한 옛 공통 모집 묶음의 다른 행이 모두 이 공급행에 합쳐져(supersedes) 묶음에서 빠지는지 */
export function leavesGroup(proposal: Pick<RowProposal, 'destination' | 'supersedes'>, workspace: JsonWorkspace) {
  const current = workspace.supply.rows.find(row => row.key === proposal.destination)
  if (!current?.groupId) return false
  return workspace.supply.rows.filter(row => row.groupId === current.groupId && row.key !== current.key && !row.excluded)
    .every(row => proposal.supersedes?.includes(row.key))
}
export function createRowReview(extraction: Extraction, index: number, proposal: RowProposal, workspace: JsonWorkspace): RowReview {
  const source = extraction.supplyRows[index], current = workspace.supply.rows.find(row => row.key === proposal.destination)
  const targets = defaultTargetDestinations({ targets: source.targets }, current)
  const subtypes = source.subtypes.length ? subtypeReviews(extraction, index, proposal) : undefined
  const choice = subtypes?.[representativeOf(subtypes, current?.housingTypeId)]?.choice
  // 세부 타입이 없는 행은 연결 주택형을 비워 두고 서버가 단지·전용면적으로 정하게 한다(칸에서 직접 고를 수는 있다).
  const housingTypeId = subtypes && typeof choice === 'number' ? choice : null
  // 신청자 구분마다 나뉜 옛 공급행이 이 행에 합쳐지면, 지금 값은 합쳐지는 행들의 모집수 합이다. PDF 값과도 이 합으로 비교한다.
  const grouped = Boolean(current?.groupId) && !leavesGroup(proposal, workspace)
  const merging = (proposal.supersedes ?? []).flatMap(key => workspace.supply.rows.filter(row => row.key === key && !row.excluded))
  const merged = current && !grouped ? mergedCount(current, proposal.supersedes, workspace.supply.rows) : null
  const before = current && merged !== null ? { ...current, householdCount: merged } : current
  const rowCells = cells(extraction, `supplyRows[${index}]`, rowFields, { ...source.values, housingTypeId }, before as unknown as JsonObject)
  const count = rowCells.find(cell => cell.field === 'totalSupplyHouseholdCount')!
  // 저장할 곳의 지금 주택타입 이름이 이 행의 공고문 주택타입 이름과 다르면(55D-1 행이 같은 면적의 55C-1에 이어져 있으면) 이름이 같은 단지 주택타입에
  // 다시 잇고, 없으면 저장할 때 그 이름으로 새로 만든다(newHousingType). 단지 주택타입은 공고문 표기 이름으로 나누므로 옛 주택타입에 PDF 값·가격을
  // 덮어쓰지 않게 한다. 관리자는 연결 주택형 칸에서 기존 값 사용을 골라 지금 주택타입을 그대로 둘 수 있다.
  const area = source.values.exclusiveArea, now = proposal.candidates.find(type => type.id === current?.housingTypeId)
  const housingTypeName = text(source.values.houseTypeName) ?? null, typeNameForLink = createdTypeName(housingTypeName ?? source.typeName ?? '')
  const others = otherTypeNames(extraction, index)
  const linked = !subtypes && areaValue(area) && typeNameForLink ? announcementHousingType(proposal.candidates, typeNameForLink, area, others) : null
  const relink = Boolean(current?.housingTypeId != null && !subtypes && areaValue(area) && typeNameForLink && (now ? linked?.id !== now.id : true))
  const target = relink ? linked : null
  if (relink) {
    const type = rowCells.find(cell => cell.field === 'housingTypeId')!
    const note = target ? `저장할 곳의 지금 주택타입이 공고문 주택타입 '${typeNameForLink}'과 달라 '${target.name}'에 연결합니다.`
      : `저장할 곳의 지금 주택타입이 공고문 주택타입 '${typeNameForLink}'과 달라(공고문이 그 주택타입을 따로 적었거나 같은 집을 찾지 못함), 저장하면 '${typeNameForLink}' 주택타입을 새로 만들어 연결합니다.`
    Object.assign(type, { note, extracted: { ...type.extracted, note } })
  }
  // 이미 저장된 공통 모집 묶음은 묶음이 모집수를 갖는다. 묶음의 행이 모두 이 공급행에 합쳐지면 묶음에서 빠져 이 행이 모집수를 갖는다.
  // 새 묶음은 만들지 않으므로 묶음 밖 공통 모집수는 행 모집수로 정하지 않는다.
  if (grouped) { count.locked = true; count.selected = false; count.mode = 'keep'; count.note = '공통 모집수는 묶음에 한 번만 기록합니다.' }
  else if (proposal.sharedIndex !== null && count.value === null) Object.assign(count, { note: sharedCountNote, extracted: { ...count.extracted, note: sharedCountNote } })
  if (merged !== null && merged !== current?.householdCount) count.note = [count.note, `합쳐지는 옛 공급행의 모집수를 더한 ${merged}세대를 지금 값으로 봅니다.`].filter(Boolean).join(' ')
  // 합쳐지는 행의 모집수를 몰라 합을 만들지 못하면, 아는 행의 수를 조용히 버리지 않게 관리자가 모집수를 정하게 한다.
  if (current && !grouped && merging.length && merged === null && count.value === null) {
    const note = '합쳐지는 옛 공급행 중 모집수를 모르는 행이 있어 합을 만들지 못했습니다. 모집수를 직접 입력하거나 지금 값을 그대로 둘지 정하세요.'
    Object.assign(count, { state: 'confirm', note, extracted: { ...count.extracted, state: 'confirm', note } })
  }
  return syncRepresentative({ proposal, include: true, reviewed: false, key: current?.key ?? crypto.randomUUID(), cells: rowCells, subtypes, housingTypeName, otherTypeNames: others, ...relink ? { relinkTo: target?.id ?? 'new' as const } : {},
    targets: source.targets.map((target, at) => ({ destination: targets[at], cells: cells(extraction, `supplyRows[${index}].targets[${at}]`, targetFields, target,
      current?.targets.find(item => (item.id ? String(item.id) : item.key) === targets[at]) as unknown as JsonObject) })) })
}
/**
 * 저장할 곳을 바꾼 제안으로 공급행 검토를 다시 만든다(단지 정하기, 저장할 곳 바꾸기). 관리자가 고른 칸 값은 이어받고, 대상 대응은
 * 제외·새로 추가와 새 저장할 곳에 있는 대상만 이어받는다. 새 공급행이 되면 기존 행 키를 쓰지 않아야 저장할 때 그 행과 겹치지 않는다.
 */
export function reproposeRow(review: Review, index: number, proposal: RowProposal, workspace: JsonWorkspace): RowReview {
  const old = review.rows[index], next = createRowReview(review.extraction, index, proposal, workspace)
  const oldCells = [...old.cells, ...old.targets.flatMap(target => target.cells)]
  // 기본 선택은 저장할 곳의 지금 값에 따라 달라지므로, 관리자가 직접 고르거나 입력한 칸만 이어받는다.
  const chosen = (cell: Cell) => cell.reviewed || cell.manualInput !== undefined
  const retain = (cell: Cell) => {
    const previous = oldCells.find(item => item.path === cell.path)
    return cell.field === 'housingTypeId' || !previous || !chosen(previous) ? cell : preserveCellChoice(cell, previous)
  }
  const saved = workspace.supply.rows.find(row => row.key === proposal.destination)?.targets ?? []
  const nextReview = { ...review, rows: review.rows.map((row, at) => at === index ? { ...next, targets: old.targets } : row) }
  const targets = next.targets.map((target, at) => {
    const previous = old.targets[at]?.destination
    return previous === 'exclude' || (previous === 'new' && old.proposal.destination === proposal.destination) || saved.some(item => (item.id ? String(item.id) : item.key) === previous)
      ? remapTarget(nextReview, index, at, previous, workspace)
      : { ...target, cells: target.cells.map(retain) }
  })
  const key = proposal.destination !== 'new' || old.proposal.destination !== 'new' ? next.key : old.key
  return { ...next, key, include: old.include, reviewed: false, cells: next.cells.map(retain), targets }
}
/**
 * fold가 true면 같은 PDF 단지에서 신청형이 같거나 행 모집수를 함께 쓰는 공고문 행을 공급행 하나로 접는다(foldSameHousingType).
 * 이미 접은 검토를 다시 만들 때는 접지 않는다. 저장할 곳은 행마다 전용면적·신청형으로 정한다(proposeRows).
 */
export function createReview(source: Extraction, workspace: JsonWorkspace, housing: HousingCandidates, fold = true): Review {
  const extraction = fold ? foldSameHousingType(source) : source
  const proposals = proposeRows(extraction, workspace.supply, housing)
  const metadata = cells(extraction, 'announcement', metadataFields, extraction.announcement, workspace.detail.data)
  // 정정공고 PDF에는 원공고 공고일이 그대로 적혀 있는 경우가 많아, 다른 공고일은 기본으로 반영하지 않는다.
  const posted = metadata.find(cell => cell.field === 'postedDate')
  if (posted?.state === 'changed' && typeof posted.value === 'string' && originalDate(posted.value, workspace.detail.data)) Object.assign(posted, { selected: false, mode: 'keep', note: '정정공고는 PDF에 원공고 공고일이 적혀 있을 수 있어 기존 공고일을 유지합니다. 정정공고 공고일이 맞는지 확인하세요.' })
  if (workspace.detail.scheduleReviewed) metadata.filter(cell => ['applicationStartDate', 'applicationEndDate'].includes(cell.field)).forEach(cell => { cell.locked = true; cell.selected = false; cell.mode = 'keep'; cell.note = '확인된 접수 기간은 접수 일정에서 수정하세요.' })
  return { extraction, metadata, reception: cells(extraction, 'receptionPlace', receptionFields, extraction.receptionPlace, (workspace.detail.data.receptionPlace ?? {}) as JsonObject),
    rows: proposals.map((proposal, index) => createRowReview(extraction, index, proposal, workspace)),
    schedules: proposeSchedules(extraction, workspace.events).map((proposal, index) => {
      const values = cells(extraction, `schedules[${index}]`, scheduleFields, extraction.schedules[index], workspace.events.find(event => event.id === proposal.existingIds[0])?.data)
      return { proposal, cells: values, reviewed: false, include: !proposal.needsConfirmation && values.every(cell => validCellValue(cell.kind, cell.value) && !['notApplicable', 'unresolved'].includes(cell.state)) }
    }) }
}
function applyCells(target: JsonObject, values: Cell[], specs: FieldSpec[]) {
  const invalid = values.find(cell => !cell.locked && cell.mode === 'manual' && !validCellValue(cell.kind, cell.value))
  if (invalid) throw new ReviewError(`${invalid.label}의 직접 입력값을 확인하거나 기존 값 사용을 선택하세요.`, `cell:${invalid.path}`)
  for (const cell of values.filter(item => item.selected && !item.locked)) {
    if (!validCellValue(cell.kind, cell.value) || ['notApplicable', 'unresolved'].includes(cell.state)) throw new ReviewError(`${cell.label}의 값과 근거를 확인하세요.`, `cell:${cell.path}`)
    const spec = specs.find(([field]) => field === cell.field)!
    target[spec[3] ?? spec[0]] = cell.value
  }
}
export type ReviewSection = 'supply' | 'schedule' | 'reception'
/** 적용 전 검증 실패. reviewId는 화면의 data-review-id와 같아 그 항목으로 바로 이동할 수 있다. */
export class ReviewError extends Error {
  readonly reviewId?: string
  constructor(message: string, reviewId?: string) { super(message); this.reviewId = reviewId }
}
export function reviewSection(reviewId: string): ReviewSection {
  if (reviewId.startsWith('schedule:') || reviewId.startsWith('cell:schedules[')) return 'schedule'
  if (reviewId.startsWith('release:') || reviewId.startsWith('cell:announcement') || reviewId.startsWith('cell:receptionPlace')) return 'reception'
  return 'supply'
}
/** 확인 항목 ID(row:2, cell:supplyRows[2].…, target:2:0, subtype:2:1, price:2)가 가리키는 공급행 번호. 공급행 밖이면 null */
export function rowOfReviewId(reviewId: string): number | null {
  const match = /^(?:(?:row|target|subtype|price):(\d+)|cell:supplyRows\[(\d+)\])/.exec(reviewId)
  return match ? Number(match[1] ?? match[2]) : null
}
export type ReviewFilter = 'all' | 'pending' | 'changed' | 'new'
const pendingKinds: [prefix: string, label: string, counted: boolean][] = [['row:', '저장할 곳', false], ['cell:', '값', true], ['subtype:', '세부 타입', true], ['target:', '임대조건', true], ['price:', '주택형 가격', false]]
/** 공급행 하나에 남은 확인 항목. pendingItems 순서(편집 영역 순서)를 그대로 따른다. */
export function rowPendingItems(items: PendingItem[], index: number) { return items.filter(item => rowOfReviewId(item.id) === index) }
/** 남은 확인을 종류별로 줄인 설명. 예: '저장할 곳 · 값 2 · 임대조건 1' */
export function pendingSummary(items: PendingItem[]) {
  return pendingKinds.flatMap(([prefix, label, counted]) => {
    const count = items.filter(item => item.id.startsWith(prefix)).length
    return count ? [counted ? `${label} ${count}` : label] : []
  }).join(' · ')
}
export type PendingItem = { id: string; section: ReviewSection }
/** 사람이 아직 판단하지 않은 확인 필요·형식 오류 값. 직접 입력값은 형식이 맞을 때까지 남는다. */
export function pendingCell(cell: Cell) {
  return !cell.locked && (cell.mode === 'manual' ? cell.state === 'invalid' : !cell.reviewed && ['confirm', 'invalid'].includes(cell.extracted.state))
}
/** 칸 하나가 확인 필요인지. needs는 칸끼리 함께 맞춰야 하는 확인(fieldNeeds)이다. 개수·표시·필터가 모두 이 판정을 쓴다. */
export function pendingField(cell: Cell, needs?: Map<string, string>) { return pendingCell(cell) || Boolean(needs?.has(cell.path)) }
/** 필터는 추출 당시 상태로 고정해 값을 고르는 동안 항목이 사라지지 않게 한다. */
export function cellMatches(cell: Cell, filter: ReviewFilter) {
  return filter === 'all' || (filter === 'pending' ? pendingCell(cell) : cell.extracted.state === filter)
}
export function pendingRow(row: RowReview) { return row.include && (!row.proposal.destination || row.proposal.needsConfirmation && !row.reviewed) }
export function pendingPrice(row: RowReview) { return row.include && housingTypePrice(row, linkedHousingType(row)).confirm }
export function pendingTarget(target: TargetReview) { return !target.destination || target.destination !== 'exclude' && (target.cells.some(pendingCell) || unnamedNewTarget(target)) }
/** 새로 추가할 공급대상은 이름이 있어야 저장된다. 적용 전에 확인 필요로 보여 적용 단계에서야 막히지 않게 한다. */
function unnamedNewTarget(target: TargetReview) {
  const name = target.cells.find(cell => cell.field === 'target')
  return target.destination === 'new' && !(name?.selected && !name.locked && String(name.value ?? '').trim())
}
/**
 * 칸마다 값은 맞아도 함께 저장하면 서버가 거절하는 접수처·기본정보 칸과 안내. 칸 경로로 찾는다.
 * 접수처는 접수처명·접수 방식이 있어야 저장되어, 접수처가 없던 공고에 PDF의 주소·연락처만 고르면 이름 없는 접수처가 된다.
 * 접수 종료일은 시작일보다 빠를 수 없어, 한쪽만 PDF 값으로 바꾸면 기존 값과 앞뒤가 뒤집힐 수 있다.
 */
/** 저장(assembleReview)과 같이 반영을 고른 칸은 그 값, 아니면 기존 값 */
export function savedValue(cell: Cell) { return cell.selected && !cell.locked ? cell.value : cell.before }
export function fieldNeeds({ reception, metadata }: Pick<Review, 'reception' | 'metadata'>, workspace?: JsonWorkspace, release?: ReleaseChoice): Map<string, string> {
  const saved = savedValue
  const needs = new Map<string, string>()
  if (reception.some(cell => saved(cell) != null)) {
    for (const cell of reception.filter(item => ['name', 'method'].includes(item.field) && !validCellValue(item.kind, saved(item)))) {
      needs.set(cell.path, `접수처를 저장하려면 ${cell.label}이 필요합니다. 직접 입력하거나, 접수처를 바꾸지 않으려면 다른 접수처 항목을 비워 두세요.`)
    }
  }
  const start = metadata.find(cell => cell.field === 'applicationStartDate'), end = metadata.find(cell => cell.field === 'applicationEndDate')
  if (start && end && typeof saved(start) === 'string' && typeof saved(end) === 'string' && String(saved(end)) < String(saved(start))) {
    for (const cell of [start, end]) needs.set(cell.path, `접수 종료일(${String(saved(end))})이 시작일(${String(saved(start))})보다 빠릅니다. 원문과 대조해 두 날짜를 맞추세요.`)
  }
  if (workspace?.detail.summary.announcement?.releaseStatus === 'UNCONFIRMED') {
    for (const cell of metadata.filter(item => ['applicationStartDate', 'applicationEndDate', 'winnerAnnouncementDate'].includes(item.field))) {
      if (!validCellValue(cell.kind, saved(cell))) needs.set(cell.path, `미확정 공고를 저장하려면 ${cell.label}이 필요합니다. 공고문을 확인해 입력하세요.`)
    }
    for (const [field, label] of [['rentalType', '공급 유형'], ['recruitmentType', '모집 유형']] as const) {
      if (!(release?.[field] || workspace.detail.data[field])) needs.set(`release:${field}`, `미확정 공고를 저장하려면 ${label}을 선택하세요.`)
    }
  }
  return needs
}
/**
 * 서버 요청값 검증(400)이 돌려준 필드 경로(correction.rows[7].targets[3].deposit)를 공급행 이름으로 바꿔 알리고 그 행으로 옮긴다.
 * 요청의 rows 순서는 화면 순서와 달라 행 key로 찾는다. 찾지 못한 경로는 그대로 보인다.
 */
export function serverFieldsError(review: Review, input: Pick<JsonUpdateInput, 'correction'>, fields: Record<string, string>) {
  let reviewId: string | null = null
  const lines = Object.entries(fields).map(([field, reason]) => {
    const match = /(?:^|\.)correction\.rows\[(\d+)\]\.?(.*)$/.exec(field)
    // 기존 행을 고치면 요청 행 key는 저장된 행 key(저장 위치)이고, 새 행은 검토 행 key다.
    const key = match ? input.correction.rows[Number(match[1])]?.key : undefined
    const at = key === undefined ? -1 : review.rows.findIndex(row => row.key === key || row.proposal.destination === key)
    if (at < 0) return `${field}: ${reason}`
    reviewId ??= `row:${at}`
    return `${rowName(review, at)}${match![2] ? ` ${match![2]}` : ''}: ${reason}`
  })
  return { message: `서버가 요청값을 받지 않았습니다. ${lines.join(' / ')}`, reviewId }
}
/** 서버가 속성 경로로 돌려준 기본정보·접수처 검증 메시지("receptionPlace.name: 필수 값입니다.")를 화면 칸 이름과 위치로 바꾼다. */
export function serverFieldError(review: Pick<Review, 'reception' | 'metadata'>, message: string) {
  const match = /^([\w.]+): (.+?)(?: \/ |$)/.exec(message)
  if (!match) return null
  const path = match[1].startsWith('receptionPlace.') ? match[1] : `announcement.${match[1]}`
  const cell = [...review.reception, ...review.metadata].find(item => item.path === path)
  return cell ? { message: `${cell.label}: ${match[2]}`, reviewId: `cell:${cell.path}` } : null
}
/** 적용하기로 한 일정이 확인 필요인지. 대응 확인이 남았거나, 저장할 유형·이름·시작·종료 중 빈 값이 있으면 확인 필요다. */
export function pendingSchedule(schedule: ScheduleReview) {
  if (schedule.proposal.undated) return false
  return schedule.proposal.needsConfirmation && !schedule.reviewed || schedule.include && !scheduleStorable(schedule)
}
/** 저장(assembleReview)과 같이 반영을 고른 칸은 그 값, 아니면 기존 일정 값으로 유형·이름·시작·종료가 모두 있는지 */
export function scheduleStorable(schedule: ScheduleReview) {
  return schedule.cells.every(cell => validCellValue(cell.kind, savedValue(cell)))
}
/** 연결 주택형을 아직 정하지 못한 세부 타입 번호 */
export function pendingSubtypes(row: RowReview) {
  if (!row.include) return []
  const sameType = sameTypeSubtypes(row)
  // 단지를 정하기 전에 비어 있는 연결은 단지 정하기에서 함께 풀린다.
  return (row.subtypes ?? []).flatMap((subtype, at) => subtype.choice === null && row.proposal.complexId !== null || sameType.includes(at) ? [at] : [])
}
export const sameTypeNote = '앞 세부 타입과 같은 주택타입을 연결했습니다. 세부 타입마다 다른 주택타입을 연결하거나 하나를 \'연결 안 함\'으로 두세요.'
/**
 * 앞 세부 타입과 같은 주택타입(기존은 id, 새 주택타입은 이름)을 연결한 세부 타입 번호. 공고 신청형 한 행이 같은 면적의 여러 단지
 * 주택타입(59A·59A1·59C)을 함께 모집할 수 있으므로 면적은 보지 않는다(서버 linkedTypes와 같은 기준).
 */
export function sameTypeSubtypes(row: RowReview): number[] {
  const seen: string[] = []
  return (row.subtypes ?? []).flatMap((subtype, at) => {
    const key = typeof subtype.choice === 'number' ? `type:${subtype.choice}` : subtype.choice === 'new' ? `new:${applicationTypeKey(createdTypeName(subtype.name))}` : null
    if (key === null) return []
    if (seen.includes(key)) return [at]
    seen.push(key)
    return []
  })
}
/** PDF 단지 이름별 공급행 번호. 처음 나온 단지 순서를 따른다. 공급정보 화면은 이 묶음대로 보여 준다. */
export function complexGroups(review: Review) {
  const groups: { name: string; indexes: number[] }[] = []
  review.extraction.supplyRows.forEach((source, index) => {
    const name = source.complexName?.trim() || '단지 이름 없음'
    const group = groups.find(item => item.name === name)
    if (group) group.indexes.push(index); else groups.push({ name, indexes: [index] })
  })
  return groups
}
/** 이 PDF 행이 든 단지 묶음 순번 */
export function groupOf(review: Review, index: number) {
  return complexGroups(review).findIndex(group => group.indexes.includes(index))
}
/** 확인 항목으로 옮길 곳. 단지를 정해야 하는 행의 저장할 곳 확인(row:i)은 단지 정하기로 옮긴다. 상태 배지와 하단 미해결 이동이 같이 쓴다. */
export function pendingJumpTarget(review: Review, workspace: JsonWorkspace, id: string) {
  const index = rowOfReviewId(id)
  if (index === null || !id.startsWith('row:') || !review.rows[index]) return id
  return undecidedComplex(review.rows[index], workspace) ? 'complex' : id
}
/** 화면에 보이는 순서(공급정보 단지 묶음 → 일정 → 접수처·기본정보)로 남은 확인 항목을 돌려준다. */
/** savesHousingTypes가 false면(PDF로 공급정보 편집표만 채울 때) 주택형 값을 저장하지 않으므로 주택형 가격 확인을 넣지 않는다. */
export function pendingItems(review: Review, savesHousingTypes = true, workspace?: JsonWorkspace, release?: ReleaseChoice): PendingItem[] {
  const items: PendingItem[] = []
  complexGroups(review).flatMap(group => group.indexes).forEach(index => {
    const row = review.rows[index]
    if (!row?.include) return
    // 편집 영역 순서(저장할 곳 → 값 비교 → 세부 타입 → 임대조건 → 주택형 가격)를 따른다.
    if (pendingRow(row) || workspace !== undefined && undecidedComplex(row, workspace)) items.push({ id: `row:${index}`, section: 'supply' })
    row.cells.filter(pendingCell).forEach(cell => items.push({ id: `cell:${cell.path}`, section: 'supply' }))
    pendingSubtypes(row).forEach(at => items.push({ id: `subtype:${index}:${at}`, section: 'supply' }))
    row.targets.forEach((target, at) => { if (pendingTarget(target)) items.push({ id: `target:${index}:${at}`, section: 'supply' }) })
    if (savesHousingTypes && pendingPrice(row)) items.push({ id: `price:${index}`, section: 'supply' })
  })
  review.schedules.forEach((schedule, index) => {
    if (pendingSchedule(schedule)) items.push({ id: `schedule:${index}`, section: 'schedule' })
    if (schedule.include) schedule.cells.filter(pendingCell).forEach(cell => items.push({ id: `cell:${cell.path}`, section: 'schedule' }))
  })
  const needs = fieldNeeds(review, savesHousingTypes ? workspace : undefined, release)
  for (const path of needs.keys()) if (path.startsWith('release:')) items.push({ id: path, section: 'reception' })
  for (const cell of [...review.reception, ...review.metadata].filter(cell => pendingField(cell, needs))) items.push({ id: `cell:${cell.path}`, section: 'reception' })
  return items
}
/** 추출 응답의 한글 문장에 섞인 한자·가나·키릴 문자를 찾는다(生계 ↔ 생계). 보정 값으로 그대로 저장하지 않게 한다. */
export function foreignCharacters(value: unknown) {
  if (typeof value !== 'string') return ''
  // 공고문이 실제로 쓰는 한자(청년 소득無·有, 관외·관내)는 오타로 보지 않는다.
  return [...new Set(value.match(/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Cyrillic}]/gu) ?? [])]
    .filter(character => !'無有外內'.includes(character)).join('')
}
/** 추출 모델이 주택형명 대신 면적(26.37, 26.37㎡)을 넣은 경우다. 표기로 쓰면 공개 화면에 면적만 보인다. */
export function areaOnlyTypeName(value: unknown) {
  // 정수만 있는 표기(26)는 흔한 주택형 이름이라 소수점이나 면적 단위가 있을 때만 면적으로 본다.
  return typeof value === 'string' && /^\d+(\.\d+\s*(㎡|m2)?|(\.\d+)?\s*(㎡|m2))$/.test(value.trim())
}
export function allCells(review: Review) { return [...review.metadata, ...review.reception, ...review.rows.flatMap(row => [...row.cells, ...row.targets.flatMap(target => target.cells)]), ...review.schedules.flatMap(schedule => schedule.cells)] }
export type PendingChange = { section: '기본정보' | '접수처' | '공급정보' | '일정'; scope: string; cell: Cell }
/** 저장하면 실제로 바뀌는 값만 영역별로 모은다. 하단의 반영 개수와 영역별 요약에 쓴다. */
export function pendingChanges(review: Review): PendingChange[] {
  const changed = (cell: Cell) => cell.selected && !cell.locked && JSON.stringify(cell.before) !== JSON.stringify(cell.value)
  const list = (section: PendingChange['section'], scope: string, cells: Cell[]) =>
    cells.filter(changed).map(cell => ({ section, scope, cell }))
  return [
    ...list('기본정보', '공고', review.metadata),
    ...list('접수처', '접수처', review.reception),
    ...review.rows.flatMap((row, index) => {
      if (!row.include) return []
      const source = review.extraction.supplyRows[index], scope = `공급행 ${index + 1} · ${source.complexName ?? '단지 미확인'} · ${source.typeName ?? '주택형 미확인'}`
      return [...list('공급정보', scope, row.cells), ...row.targets.filter(target => target.destination !== 'exclude')
        .flatMap(target => list('공급정보', `${scope} / ${shown(target.cells.find(cell => cell.field === 'target')?.value)}`, target.cells))]
    }),
    ...review.schedules.flatMap((schedule, index) => schedule.include ? list('일정', `일정 ${index + 1} · ${shown(schedule.cells.find(cell => cell.field === 'name')?.value)}`, schedule.cells) : []),
  ]
}
// 저장 위치는 원천 표기만으로 고르기 어려워 연결 단지·표시 이름·모집수·전용면적을 함께 보여 준다.
export function applicableCells(review: Review) {
  return [...review.metadata, ...review.reception,
    ...review.rows.filter(row => row.include).flatMap(row => [...row.cells, ...row.targets.filter(target => target.destination !== 'exclude').flatMap(target => target.cells)]),
    ...review.schedules.filter(schedule => schedule.include).flatMap(schedule => schedule.cells)]
}
/** 운영자에게 보일 PDF 행 이름. 1부터 센 번호와 주택형 표기다. */
export function rowName(review: Review, index: number) { return `공급행 ${index + 1}(${review.extraction.supplyRows[index]?.typeName ?? '주택형 미확인'})` }
/** 공급행 목록·이동 막대에 보일 저장할 곳 요약 */
export function destinationShort(review: Review, index: number, workspace: JsonWorkspace) {
  const destination = review.rows[index].proposal.destination, existing = workspace.supply.rows.find(row => row.key === destination)
  return destination === 'new' || !existing ? '새로 추가' : `${existing.typeName}${existing.exclusiveArea != null ? ` ${existing.exclusiveArea}㎡` : ''} 고침 #${String(existing.id)}`
}
/** 행 하나가 저장될 곳을 한 문장으로 적는다. 기존 행은 번호가 아니라 단지·이름·면적·모집수·만든 곳으로, 새 행은 들어갈 단지로 적는다. */
export function destinationSentence(review: Review, index: number, workspace: JsonWorkspace) {
  const proposal = review.rows[index].proposal, rows = workspace.supply.rows
  const existing = rows.find(row => row.key === proposal.destination)
  if (existing) return `고칠 기존 공급정보: ${describeRow(existing, workspace.supply)}`
  const anchor = rows.find(row => row.key === proposal.anchorKey)
  const complex = anchor ? anchor.housingComplexName ?? anchor.complexName : proposal.newComplex?.name
  return complex ? `${complex}에 새 공급정보로 추가합니다.` : '새 공급정보로 추가합니다.'
}
/**
 * PDF 공급행이 하나도 가리키지 않아 그대로 남을 기존 공급행. PDF 공급행이 있는 공고 단지의 제외하지 않은 행 중 어느 PDF 행의
 * 저장할 곳도, 함께 제외할 옛 행도 아닌 행이다. 전용면적을 몰라 PDF 행과 맞추지 못한 LH 통합행이 남으면 두 번 셀 수 있어 알린다.
 */
export function leftoverRows(review: Review, workspace: JsonWorkspace): CorrectionRow[] {
  const included = review.rows.filter(row => row.include), taken = new Set(included.flatMap(row => [row.proposal.destination, ...row.proposal.supersedes ?? []]))
  const complexes = new Set(included.flatMap(row => {
    const anchor = workspace.supply.rows.find(item => item.key === row.proposal.anchorKey || item.key === row.proposal.destination)
    return anchor ? [anchor.complexName] : []
  }))
  return workspace.supply.rows.filter(row => row.id !== null && !row.excluded && row.groupId === null && complexes.has(row.complexName) && !taken.has(row.key))
}
/** 저장할 행과 합쳐지는(supersedes) 옛 공급행들의 모집수 합. 합쳐지는 행이 없거나 하나라도 모르면 null이다. */
export function mergedCount(destination: CorrectionRow, supersedes: string[] = [], rows: CorrectionRow[]): number | null {
  const merged = supersedes.flatMap(key => rows.filter(row => row.key === key && !row.excluded))
  if (!merged.length) return null
  const counts = [destination, ...merged].map(row => row.householdCount)
  return counts.every(value => typeof value === 'number') ? (counts as number[]).reduce((total, value) => total + value, 0) : null
}
/** 저장하면 이 행에 들어갈 모집수. 반영을 고른 칸은 그 값, 아니면 저장 위치의 지금 값(합쳐지는 옛 행이 있으면 그 합)이다. */
export function assembledCount(row: RowReview): number | null {
  const cell = row.cells.find(item => item.field === 'totalSupplyHouseholdCount')
  const value = cell?.selected && !cell.locked ? cell.value : cell?.before
  return typeof value === 'number' ? value : null
}
/** 미확정 공고의 공개 전 확인. 비어 있는 임대·모집 유형을 정하고, 단지를 확인했으면 이 보정과 함께 공개를 요청한다. */
export type ReleaseChoice = { rentalType: string; recruitmentType: string; publish: boolean }
export function assembleReview(review: Review, workspace: JsonWorkspace, evidence: { reason: string; sourceUrl: string; sourcePage: string | null }, release?: ReleaseChoice): JsonUpdateInput {
  const unfinished = review.rows.findIndex(row => row.include && row.proposal.createdComplex)
  if (unfinished >= 0) throw new ReviewError('단지는 등록됐지만 주택형·공급행 연결이 끝나지 않았습니다. 단지 정하기에서 이어서 연결하세요.', `row:${unfinished}`)

  const rows = structuredClone(workspace.supply.rows), taken = new Map<string, number>()
  const housingTypeValues: HousingTypeValue[] = [], deleted: string[] = []
  // 같은 신청형의 옛 공급행과 관리자가 고른 남는 행은 제외하고, 관리자가 추가해 지울 수 있는 행은 지운다.
  const supersede = (keys: string[] = []) => keys.forEach(key => {
    const row = rows.find(item => item.key === key)
    if (!row || row.excluded || deleted.includes(key) || taken.has(key)) return
    if (workspace.supply.deletableRowKeys?.includes(key)) deleted.push(key); else row.excluded = true
  })
  for (const [index, entry] of review.rows.entries()) {
    if (!entry.include) continue
    const anchor = rows.find(row => row.key === entry.proposal.anchorKey)
    const existing = entry.proposal.destination === 'new' ? undefined : rows.find(row => row.key === entry.proposal.destination)
    if ((!anchor && !entry.proposal.newComplex) || entry.proposal.destination !== 'new' && !existing) throw new ReviewError(`${rowName(review, index)}의 단지를 단지 정하기에서 정하세요.`, `row:${index}`)
    if (existing && taken.has(existing.key)) throw new ReviewError(`${rowName(review, taken.get(existing.key)!)}과 ${rowName(review, index)}이 같은 기존 공급정보 #${String(existing.id)}(${existing.typeName})을 고칩니다. 한쪽의 저장할 곳을 바꾸세요.`, `row:${index}`)
    if (existing) taken.set(existing.key, index)
    const complex = entry.proposal.newComplex
    const row: CorrectionRow = existing ?? { ...blankRow(nextDisplayOrder(rows)), key: entry.key,
      complexName: review.extraction.supplyRows[index].complexName || anchor?.complexName || complex!.name,
      pnu: anchor?.pnu ?? complex!.pnu, category: anchor?.category ?? complex!.category,
      housingComplexId: anchor ? anchor.housingComplexId : complex!.id, housingComplexName: anchor ? anchor.housingComplexName : complex!.name }
    if (existing && existing.housingComplexId === null && complex) {
      row.housingComplexId = complex.id; row.housingComplexName = complex.name
      if (!row.pnu.trim()) row.pnu = complex.pnu
    }
    // 옛 공통 모집 묶음이 이 공급행 하나로 합쳐지면 묶음에서 빠진다. 묶음의 다른 행은 supersedes로 제외한다.
    if (existing && leavesGroup(entry.proposal, workspace)) { row.groupId = null; row.sharedHouseholdCount = null }
    applyCells(row as unknown as JsonObject, entry.cells, rowFields)
    const relinked = existing ? relinkTarget(entry) : null
    if (typeof relinked === 'number') row.housingTypeId = relinked
    if (relinked === 'new') {
      // 새 주택타입 이름은 PDF 값(주택타입, 없으면 공고 표기)에서 정한다. 공고 표기 칸을 기존 값으로 둬도 옛 행 이름을 쓰지 않는다.
      const source = review.extraction.supplyRows[index], houseType = source.values.houseTypeName
      row.housingTypeId = null
      row.newHousingType = missingHousingType(row.housingComplexId, entry.proposal.candidates, createdTypeName(typeof houseType === 'string' ? houseType : source.typeName ?? row.typeName), row.exclusiveArea, row.supplyArea)
    } else if (relinked === null && !entry.subtypes && autoHousingType(entry) && row.housingComplexId !== null && row.groupId === null
      && !entry.cells.some(cell => cell.field === 'housingTypeId' && cell.selected && !cell.locked)) {
      // 연결 주택형을 정하지 않은 행은 공고문 주택타입 이름으로 정해 보낸다. 서버가 면적으로 고르면 같은 면적의 다른 주택타입(55C-1)에 이어진다.
      const expected = expectedHousingType(entry)
      if (expected) row.housingTypeId = expected.id
      else row.newHousingType = missingHousingType(row.housingComplexId, entry.proposal.candidates, housingTypeNameOf(entry), row.exclusiveArea, row.supplyArea)
    }
    if (existing && entry.proposal.supersedes?.length) {
      // PDF 모집수를 고르지 않았으면 합쳐지는 옛 공급행의 모집수까지 이 행이 갖는다(지금 값을 그 합으로 보인 칸과 같다).
      const count = entry.cells.find(cell => cell.field === 'totalSupplyHouseholdCount')
      if (count && !count.selected && !count.locked && typeof count.before === 'number') row.householdCount = count.before
    }
    const subtypes = entry.subtypes && subtypeLinks(entry, index, row)
    // 공고에 주택형 표기가 없으면 새 행은 연결한 단지 주택형 이름으로 표시한다.
    if (!row.typeName.trim()) row.typeName = entry.proposal.candidates.find(type => type.id === row.housingTypeId)?.name ?? ''
    if (subtypes) {
      if (!existing) row.areaOverride = false
    } else if (entry.cells.some(cell => cell.field === 'housingTypeId' && cell.selected && cell.value !== null) && !entry.proposal.candidates.some(type => type.id === row.housingTypeId)) throw new ReviewError('연결 주택형이 선택한 단지에 없습니다. 주택형을 다시 선택하세요.', `row:${index}`)
    // 세부 타입을 함께 연결하지 않는 행은 대표가 그대로면 저장된 연결을 유지하고 바뀌면 대표만 남기도록 목록을 보내지 않는다.
    if (!subtypes) row.linkedHousingTypes = undefined
    if (entry.cells.some(cell => ['exclusiveArea', 'supplyArea'].includes(cell.field) && cell.selected)) row.areaOverride = true
    const selectedTargets = new Set<string>()
    for (const [at, target] of entry.targets.entries()) {
      if (target.destination === 'exclude') continue
      if (!target.destination) throw new ReviewError(`${row.typeName}: 수정할 기존 임대조건을 선택하거나 새 임대조건으로 추가하세요.`, `row:${index}`)
      if (target.destination !== 'new' && selectedTargets.has(target.destination)) throw new ReviewError('같은 기존 공급대상을 두 번 갱신할 수 없습니다.', `row:${index}`)
      selectedTargets.add(target.destination)
      const stored = row.targets.find(item => (item.id ? String(item.id) : item.key) === target.destination)
      if (target.destination !== 'new' && !stored) throw new ReviewError('선택한 공급대상이 현재 행에 없습니다.', `row:${index}`)
      const next: CorrectionTarget = stored ?? blankTarget(row.targets.length)
      applyCells(next as unknown as JsonObject, target.cells, targetFields)
      // 소득 유무 표기는 공고마다 달라(소득無·소득(×)) 단지에 쌓이는 대상 이름을 하나로 맞춘다.
      if (next.target) next.target = canonicalTarget(next.target)
      if (!next.target?.trim()) throw new ReviewError(`${row.housingComplexName ?? row.complexName} ${rowName(review, index)} 조건 ${at + 1}: 공급 대상 이름을 입력하세요.`, `target:${index}:${at}`)
      if (!stored) row.targets.push(next)
    }
    // 대상별 모집수를 합해 볼 수 있는 행은 합계가 행 모집수를 넘으면 서버가 거절한다. 같은 대상을 새 임대조건으로 겹쳐 넣었을 때 생긴다.
    const targetSum = row.targets.filter(target => !target.excluded).reduce((sum, target) => sum + (target.householdCount ?? 0), 0)
    if (row.targetCountsComparable && row.householdCount !== null && targetSum > row.householdCount) {
      throw new ReviewError(`${rowName(review, index)}: 대상별 모집수 합계 ${targetSum}세대가 행 모집수 ${row.householdCount}세대를 넘습니다. 같은 대상을 새 임대조건으로 겹쳐 추가하지 않았는지 확인하세요.`, `row:${index}`)
    }
    if (!row.typeName) throw new ReviewError('새 공급행의 주택형명을 확인하세요.', `row:${index}`)
    if (!existing) rows.push(row)
    if (subtypes) housingTypeValues.push(...subtypes.flatMap(item => {
      const path = `supplyRows[${index}].subtypes[${item.at}]`, subtype = entry.subtypes![item.at]
      const value = housingTypeValue(entry, row.key, linkedHousingType(entry), subtypeTypeValues(entry, subtype, review.extraction.supplyRows[index].typeName, Boolean(noteFor(review.extraction.unresolved, `${path}.supplyArea`))), item.address)
      return !row.excluded && value ? [value] : []
    }))
    // 주택형을 비워 둔 행은 서버가 단지·전용면적으로 정한 주택형에 값을 반영한다.
    else if (!row.excluded && (row.housingTypeId !== null || row.newHousingType || autoHousingType(entry) && row.housingComplexId !== null && row.groupId === null)) {
      const value = housingTypeValue(entry, row.key, linkedHousingType(entry), rowTypeValues(entry, review.extraction.supplyRows[index]))
      if (value) housingTypeValues.push(value)
    }
  }
  // 이미 저장된 공통 모집 묶음은 PDF 보정에서 모집수를 바꾸지 않는다. 새 공통 모집 묶음은 만들지 않는다.
  for (const [index, group] of review.extraction.sharedCounts.entries()) {
    const current = review.rows.filter(row => row.include && row.proposal.sharedIndex === index).map(row => rows.find(item => item.key === row.key))
    if (!current.length || !current.every(row => row?.groupId != null && row.groupId === current[0]!.groupId)) continue
    if (current[0]!.sharedHouseholdCount !== group.count) throw new ReviewError('기존 공통 모집수가 PDF와 다릅니다. 공통 모집수는 PDF 보정에서 바꿀 수 없습니다.', `row:${review.rows.findIndex(row => row.proposal.sharedIndex === index)}`)
  }
  supersede([...review.rows.flatMap(row => row.include ? row.proposal.supersedes ?? [] : []), ...review.excludedRows ?? []])
  const ordered = applyPdfOrder(review, rows.filter(row => !deleted.includes(row.key)))
  const announcement: JsonObject = Object.fromEntries(['name', 'rentalType', 'recruitmentType', 'agencyCode', ...metadataFields.map(([field]) => field)].map(field => [field, workspace.detail.data[field] ?? null]))
  applyCells(announcement, review.metadata, metadataFields)
  const [need] = fieldNeeds(review, release ? workspace : undefined, release)
  if (need) throw new ReviewError(need[1], need[0].startsWith('release:') ? need[0] : `cell:${need[0]}`)
  const reception = { ...((workspace.detail.data.receptionPlace ?? {}) as JsonObject) }
  applyCells(reception, review.reception, receptionFields)
  const document: ExtractedAnnouncement = { schemaVersion: 'admin-announcement-import/v1', source: { originalUrl: workspace.detail.data.originalUrl, sourceDocumentId: workspace.detail.sourceIdentifier, extractedAt: new Date().toISOString() }, announcement, receptionPlace: reception, supplyRows: ordered.filter(row => review.rows.some(entry => entry.include && entry.key === row.key)).map(row => ({
    complexReference: { sourceComplexName: row.complexName, pnu: row.pnu.trim() || null }, sourceHousingTypeName: row.typeName,
    expectedMoveInMonth: row.moveInMonth, supplyCategory: row.category, totalSupplyHouseholdCount: row.householdCount,
    exclusiveArea: row.exclusiveArea, supplyArea: row.supplyArea, targets: row.targets.filter(target => !target.excluded).map(target => ({
      target: target.target, supplyRank: target.supplyRank, supplyHouseholdCount: target.householdCount, reserveCount: target.reserveCount,
      rentalDeposit: target.deposit, monthlyRent: target.rent, convertedDeposit: target.convertedDeposit, applicationCondition: target.condition,
    })),
  })), schedules: [], attachments: [], unresolvedFields: [] }
  const schedules: JsonUpdateInput['schedules'] = [], used = new Set<number>()
  for (const [index, entry] of review.schedules.entries()) {
    if (!entry.include) continue
    const destinations: (number | null)[] = entry.proposal.existingIds.length ? entry.proposal.existingIds : [null]
    for (const id of destinations) {
      if (id !== null && used.has(id)) throw new ReviewError('같은 기존 일정을 두 번 갱신할 수 없습니다.', `schedule:${index}`)
      if (id !== null) used.add(id)
      const before = workspace.events.find(event => event.id === id)
      const after = Object.fromEntries(scheduleFields.map(([field]) => [field, before?.data[field] ?? null]))
      applyCells(after, entry.cells, scheduleFields)
      if (entry.cells.some(cell => !validCellValue(cell.kind, after[cell.field]))) throw new ReviewError('일정 유형·이름·시작·종료를 확인하세요. 미확인 일정은 제외하세요.', `schedule:${index}`)
      if (String(after.endAt) < String(after.startAt)) throw new ReviewError('일정 종료가 시작보다 빠릅니다.', `schedule:${index}`)
      schedules.push({ inputIndex: document.schedules.length, existingId: id }); document.schedules.push(after)
    }
  }
  const fields = [...review.metadata.filter(cell => cell.selected && !cell.locked).map(cell => cell.field), ...review.reception.filter(cell => cell.selected).map(cell => `receptionPlace.${cell.field}`)]
  for (const field of ['rentalType', 'recruitmentType'] as const) {
    if (release?.[field]) { announcement[field] = release[field]; fields.push(field) }
  }
  return { snapshotToken: workspace.snapshotToken, document, fields, schedules, attachments: [], correction: { operationId: crypto.randomUUID(), snapshotToken: workspace.supply.snapshotToken, rows: ordered, ...deleted.length ? { deletedRowKeys: deleted } : {}, ...evidence }, housingTypeValues, ...release?.publish ? { publish: true } : {} }
}

type TypeValueInput = { name: unknown; supplyArea: unknown; householdCount: unknown }
type TypeAddress = Pick<HousingTypeValue, 'linkedHousingTypeId' | 'linkedNewHousingTypeArea' | 'linkedNewHousingTypeName'>
/** 세부 타입이 없는 행의 주택형 값. 이름·공급면적은 관리자가 고른 값(현재값과 같은 값 포함)만 쓰고, 고르지 않은 값은 보내지 않는다. */
function rowTypeValues(entry: RowReview, source: Extraction['supplyRows'][number]): TypeValueInput {
  const accepted = (field: string, extracted: unknown) => {
    const cell = entry.cells.find(item => item.field === field)
    return cell?.selected && !cell.locked ? cell.value : acceptedCell(cell) ? extracted : null
  }
  // 공고 표기는 주택형(신청형, 059.9000B)이고 단지 쪽 이름은 주택타입(59B)이다. 신청형으로 단지 주택타입 이름을 바꾸지 않고,
  // PDF 주택타입이 하나뿐인 행(houseTypeName, 세부 타입 하나)만 그 이름으로 바꾼다.
  const name = acceptedCell(entry.cells.find(item => item.field === 'sourceHousingTypeName')) ? renamedTypeName(source.values.houseTypeName, source.typeName) : null
  return { name, supplyArea: accepted('supplyArea', source.values.supplyArea), householdCount: source.values.constructedHouseholdCount }
}
/** 관리자가 이 칸의 값을 받아들였는지. 칸이 없거나, 반영을 골랐거나, 현재값과 같으면 받아들인 것이다. */
function acceptedCell(cell: Cell | undefined) { return !cell || cell.selected && !cell.locked || cell.state === 'same' }
/**
 * 세부 타입 묶음 행의 세부 타입 하나의 주택형 값. 이름은 관리자가 공급행의 주택형 표기를 받아들였을 때만 세부 타입 이름을 쓴다(rowTypeValues와 같은 기준).
 * 세부 타입 공급면적은 공급행 칸이 해당 없음이라, 공고문에서 읽지 못한 값만 뺀다. 세대수는 공고문의 건설호수다.
 */
function subtypeTypeValues(entry: RowReview, subtype: Subtype, applyType: unknown, supplyAreaUnresolved: boolean): TypeValueInput {
  // 세부 타입은 PDF 주택타입이다. 한 전용면적에 주택타입이 하나일 때만(59B) 그 이름으로 단지 주택타입 이름을 바꾸고, 여럿이면(59A·59A1·59C) 그대로 둔다.
  return { name: acceptedCell(entry.cells.find(item => item.field === 'sourceHousingTypeName')) ? renamedTypeName(subtype.name, applyType) : null,
    supplyArea: supplyAreaUnresolved ? null : subtype.supplyArea, householdCount: subtype.constructedHouseholdCount }
}
/**
 * 공급행이 고른 주택형에 반영할 공고 값. 면적만 적힌 표기는 주택형 이름이 아니라 보내지 않는다. 세대수는 공고문의 건설호수다.
 * 세부 타입 묶음 행은 세부 타입마다 보내고, 기본 임대조건은 공급행 하나의 값이라 연결한 주택형 모두에 같은 대상 금액을 쓴다.
 */
function housingTypeValue(entry: RowReview, rowKey: string, current: MatchingHousingType | null | undefined, input: TypeValueInput, address: TypeAddress = {}): HousingTypeValue | null {
  const { name, supplyArea: area, householdCount: count } = input
  // "39(창업인)"처럼 끝 괄호는 공급대상 표기라 주택형 이름에 넣지 않는다. 넣으면 공고마다 이름이 바뀐다.
  const typeName = typeof name === 'string' ? withoutTrailingParentheses(name) : ''
  const price = housingTypePrice(entry, current)
  const chosen = price.chosen === null ? null : price.options[price.chosen]
  const value: HousingTypeValue = { rowKey, name: typeName && !areaOnlyTypeName(typeName) ? typeName : null,
    supplyArea: validCellValue('area', area) ? area as number : null,
    householdCount: typeof count === 'number' && Number.isSafeInteger(count) && count >= 0 ? count : null,
    basicDeposit: chosen?.deposit ?? null, basicMonthlyRent: chosen?.monthlyRent ?? null, ...address }
  return value.name === null && value.supplyArea === null && value.householdCount === null && chosen === null ? null : value
}

/**
 * 세부 타입 묶음 행을 공급행 하나에 저장한다. 대표 세부 타입의 주택형이 그 행의 주택형이고, 나머지는 함께 모집하는 주택형(linkedHousingTypes)이다.
 * 연결 주택형을 정하지 못한 세부 타입이 있거나 같은 주택형을 두 세부 타입이 고르면 저장하지 않는다. 돌려준 목록은 주택형 값을 보낼 세부 타입과 그 주소다.
 */
function subtypeLinks(entry: RowReview, index: number, row: CorrectionRow): { at: number; address: TypeAddress }[] {
  const subtypes = entry.subtypes!, label = `공급행 ${index + 1}`
  if (row.groupId !== null) throw new ReviewError(`${label}: 공통 모집 묶음 행에는 세부 타입 주택형을 함께 연결할 수 없습니다. 저장할 위치를 다시 고르세요.`, `row:${index}`)
  const links = subtypes.map((subtype, at): LinkedHousingType | null => {
    if (subtype.choice === null) throw new ReviewError(`${label}: 세부 타입 ${subtype.name}의 연결 주택형을 고르세요.`, `subtype:${index}:${at}`)
    if (subtype.choice === 'none') return null
    if (subtype.choice === 'new') {
      const created = missingHousingType(row.housingComplexId, entry.proposal.candidates, createdTypeName(subtype.name), subtype.exclusiveArea, subtype.supplyArea)
      if (!created?.name) throw new ReviewError(`${label}: 세부 타입 ${subtype.name}은(는) 형명과 전용면적이 있고 단지에 같은 이름 주택형이 없어야 새 주택형으로 추가할 수 있습니다.`, `subtype:${index}:${at}`)
      return { newHousingType: created }
    }
    if (!entry.proposal.candidates.some(type => type.id === subtype.choice)) throw new ReviewError(`${label}: 세부 타입 ${subtype.name}의 연결 주택형이 선택한 단지에 없습니다.`, `subtype:${index}:${at}`)
    return { housingTypeId: subtype.choice }
  })
  const [sameType] = sameTypeSubtypes(entry)
  if (sameType !== undefined) throw new ReviewError(`${label}: 세부 타입 ${subtypes[sameType].name}은(는) ${sameTypeNote}`, `subtype:${index}:${sameType}`)
  const representative = representativeSubtype(entry), first = links[representative]
  if (!first) throw new ReviewError(`${label}: 세부 타입 하나 이상에 주택형을 연결하세요.`, `row:${index}`)
  row.housingTypeId = first.housingTypeId ?? null; row.newHousingType = first.newHousingType ?? null
  row.linkedHousingTypes = links.filter((link, at): link is LinkedHousingType => link !== null && at !== representative)
  return links.flatMap((link, at) => link === null ? [] : [{ at, address: at === representative ? {}
    : link.housingTypeId ? { linkedHousingTypeId: link.housingTypeId } : { linkedNewHousingTypeArea: link.newHousingType!.exclusiveArea, linkedNewHousingTypeName: link.newHousingType!.name } }])
}

/**
 * 저장하면 공급행이 연결할 주택형. 연결 주택형 칸을 반영하면 그 값, 아니면 저장 위치의 지금 주택형(칸의 현재값)이다.
 * 저장(applyCells)과 같은 기준이라 검토 화면이 보여 주는 주택형 가격과 저장하는 가격이 같은 주택형을 가리킨다.
 */
/**
 * 저장할 곳을 바꿔 다시 연결할 단지 주택타입(createRowReview의 relinkTo). 전용면적을 기존 값으로 두면 PDF 면적의 주택타입에 연결할 근거가 없고,
 * 관리자가 연결 주택타입 칸에서 직접 고르거나 기존 값 사용을 고르면 그 선택을 따르므로 null이다.
 */
export function relinkTarget(row: RowReview): number | 'new' | null {
  const cell = row.cells.find(item => item.field === 'housingTypeId'), area = row.cells.find(item => item.field === 'exclusiveArea')
  // 직접 입력한 면적은 PDF 면적이 아니므로 PDF 추출값과 비교한다.
  const pdfArea = Boolean(area && areaValue(area.extracted.value) && savedValue(area) === area.extracted.value)
  return row.relinkTo !== undefined && pdfArea && cell && !cell.selected && !(cell.reviewed && cell.mode === 'keep') ? row.relinkTo : null
}
export function linkedHousingTypeId(row: RowReview): number | null {
  const relinked = relinkTarget(row)
  if (relinked !== null) return relinked === 'new' ? null : relinked
  if (row.subtypes) {
    const choice = row.subtypes[representativeSubtype(row)]?.choice
    return typeof choice === 'number' ? choice : null
  }
  const cell = row.cells.find(item => item.field === 'housingTypeId')
  const id = cell?.selected && !cell.locked ? cell.value : cell?.before
  return typeof id === 'number' ? id : null
}
/**
 * 연결 주택형의 후보 정보. 연결 주택형을 비워 서버가 정하는 행은 서버와 같은 규칙으로 고를 주택형이다.
 * 새 주택형처럼 연결 주택형이 없으면 undefined, 연결했지만 후보에 없어 지금 값을 모르면 null이다.
 */
export function linkedHousingType(row: RowReview): MatchingHousingType | null | undefined {
  const id = linkedHousingTypeId(row)
  if (id === null) return expectedHousingType(row) ?? undefined
  return row.proposal.candidates.find(type => type.id === id) ?? null
}

export type PriceOption = { index: number; label: string; deposit: number; monthlyRent: number }
/**
 * 주택형 기본 임대조건(보증금·월 임대료)으로 쓸 공급대상. 반영하는 대상의 금액이 모두 있고 하나로 같으면 그 값을 쓰고,
 * 다르면 '일반' 대상을 미리 고른다. '일반'도 없으면 지금 값을 유지하고 관리자가 검토 화면에서 고른다.
 * 지금 값이 이전 PDF 보정에서 관리자가 고른 공고문 값(current)이면, 다른 금액으로 바꾸기 전에 확인 필요(confirm)로 두고 기본은 유지한다.
 * 연결 주택형이 후보에 없어 지금 값을 모르면(current가 null) 확인 없이 덮지 않도록 기본은 유지한다(unknown).
 */
export function housingTypePrice(row: RowReview, current?: MatchingHousingType | null): { options: PriceOption[]; chosen: number | null; same: boolean; confirm: boolean; unknown: boolean } {
  const amount = (cells: Cell[], field: string) => {
    const cell = cells.find(item => item.field === field)
    // 금액 칸은 숫자 문자열('41848000')로 들고 있다.
    const value = !cell ? null : cell.mode === 'keep' ? cell.before : cell.value
    const number = typeof value === 'string' && /^\d{1,15}$/.test(value) ? Number(value) : value
    return typeof number === 'number' && Number.isSafeInteger(number) && number >= 0 ? number : null
  }
  const options = row.targets.flatMap((target, index) => {
    const deposit = amount(target.cells, 'rentalDeposit'), monthlyRent = amount(target.cells, 'monthlyRent')
    const label = shown(target.cells.find(cell => cell.field === 'target')?.value)
    return target.destination === 'exclude' || deposit === null || monthlyRent === null ? [] : [{ index, label, deposit, monthlyRent }]
  })
  // 금액을 읽지 못한 대상이 있으면 그 대상의 금액을 모르므로 모두 같다고 보지 않는다.
  const applied = row.targets.filter(target => target.destination !== 'exclude').length
  const same = options.length > 0 && options.length === applied && options.every(option => option.deposit === options[0].deposit && option.monthlyRent === options[0].monthlyRent)
  const unknown = current === null
  if (!options.length) return { options, chosen: null, same, confirm: false, unknown }
  if (row.priceTarget !== undefined) {
    const picked = row.priceTarget === null ? -1 : options.findIndex(option => option.index === row.priceTarget)
    return { options, chosen: picked >= 0 ? picked : null, same, confirm: false, unknown }
  }
  const general = options.findIndex(option => option.label.replace(/\s/g, '') === '일반')
  const proposed = same ? 0 : general >= 0 ? general : null
  const option = proposed === null ? null : options[proposed]
  const confirm = option !== null && current?.rentalConditionSource === 'ANNOUNCEMENT'
    && (option.deposit !== current.basicDeposit || option.monthlyRent !== current.basicMonthlyRent)
  return { options, chosen: confirm || unknown ? null : proposed, same, confirm, unknown }
}

// 서버와 같은 기준(표시 순서 → 작업 키)으로 현재 화면 순서를 만든다.
function byDisplayOrder(left: CorrectionRow, right: CorrectionRow) {
  return left.displayOrder - right.displayOrder || (left.key < right.key ? -1 : left.key > right.key ? 1 : 0)
}
/**
 * PDF 공급 표 순서대로 적용하는 공급행을 앞에 두고, PDF에 없는 기존 행은 원래 순서대로 뒤에 둔다.
 * 순서가 이미 같고 겹치는 번호가 없으면 같은 배열을 돌려줘 불필요한 행 변경을 만들지 않는다.
 */
export function applyPdfOrder(review: Review, rows: CorrectionRow[]): CorrectionRow[] {
  const current = [...rows].sort(byDisplayOrder)
  const pdf = review.rows.filter(entry => entry.include).flatMap(entry => current.filter(row => row.key === entry.key))
  const desired = [...pdf, ...current.filter(row => !pdf.includes(row))]
  const unique = new Set(rows.map(row => row.displayOrder)).size === rows.length
  if (unique && desired.every((row, at) => row === current[at])) return rows
  return rows.map(row => ({ ...row, displayOrder: desired.indexOf(row) }))
}
/** 저장하면 공급행 순서가 바뀌는지 미리 알려준다. 새 행은 저장할 때처럼 기존 행 뒤에 둔다. */
export function pdfOrderChanges(review: Review, workspace: JsonWorkspace) {
  const rows = [...workspace.supply.rows]
  for (const entry of review.rows) if (entry.include && entry.proposal.destination === 'new') rows.push({ ...blankRow(nextDisplayOrder(rows)), key: entry.key })
  return applyPdfOrder(review, rows) !== rows
}

export function remapTarget(review: Review, rowIndex: number, targetIndex: number, destination: string, workspace: JsonWorkspace): TargetReview {
  const entry = review.rows[rowIndex]
  const current = workspace.supply.rows.find(row => row.key === entry.proposal.destination)?.targets.find(target => (target.id ? String(target.id) : target.key) === destination)
  const next = cells(review.extraction, `supplyRows[${rowIndex}].targets[${targetIndex}]`, targetFields, review.extraction.supplyRows[rowIndex].targets[targetIndex], current as unknown as JsonObject)
  return { destination, cells: next.map(cell => {
    const old = entry.targets[targetIndex].cells.find(item => item.path === cell.path)
    return preserveCellChoice(cell, old)
  }) }
}

export function remapSchedule(review: Review, index: number, ids: number[], workspace: JsonWorkspace): ScheduleReview {
  const old = review.schedules[index]
  const values = cells(review.extraction, `schedules[${index}]`, scheduleFields, review.extraction.schedules[index], workspace.events.find(event => event.id === ids[0])?.data)
  return { ...old, proposal: { ...old.proposal, existingIds: ids }, cells: values.map(cell => {
    const previous = old.cells.find(item => item.path === cell.path)
    return preserveCellChoice(cell, previous)
  }) }
}

export function rebaseReview(previous: Review, workspace: JsonWorkspace, housing: HousingCandidates): Review {
  const next = createReview(previous.extraction, workspace, housing, false)
  const oldCells = new Map(allCells(previous).map(cell => [cell.path, cell]))
  function retain(cell: Cell): Cell { return preserveCellChoice(cell, oldCells.get(cell.path)) }
  next.metadata = next.metadata.map(retain); next.reception = next.reception.map(retain)
  next.excludedRows = previous.excludedRows?.filter(key => workspace.supply.rows.some(row => row.key === key && !row.excluded))
  next.rows = next.rows.map((row, index) => {
    const old = previous.rows[index], anchor = workspace.supply.rows.find(item => item.key === old.proposal.anchorKey)
    const kept = Boolean(anchor || old.proposal.newComplex) && (old.proposal.destination === 'new' || workspace.supply.rows.some(item => item.key === old.proposal.destination && !item.excluded))
    if (kept) {
      const candidates = housing[anchor?.housingComplexId ?? old.proposal.newComplex?.id ?? 0] ?? old.proposal.candidates ?? []
      // 관리자가 바꾼 저장할 곳은 유지하고, '추천'은 지금 공급정보로 새로 만든 자동 제안으로 바꾼다.
      const { automatic: _, ...fresh } = row.proposal
      row = createRowReview(next.extraction, index, { ...old.proposal, candidates, ...old.proposal.automatic ? { automatic: fresh } : {} }, workspace)
    }
    row.proposal = { ...row.proposal, createdComplex: old.proposal.createdComplex }
    // 저장할 곳이 사라져 새 자동 제안으로 돌아가면, 앞서 마친 대응 확인은 그 제안의 확인이 아니다.
    row.include = old.include; row.reviewed = kept && old.reviewed; row.cells = row.cells.map(retain)
    // 직접 고른 세부 타입 연결은 그 주택형이 아직 후보에 있으면 유지한다. 새 주택형 추가는 같은 면적 주택형이 여전히 없을 때만 유지한다.
    row.subtypes?.forEach((subtype, at) => {
      const choice = old.subtypes?.[at]?.choice
      if (choice === 'none' || choice === 'new' && !subtype.options.length || typeof choice === 'number' && row.proposal.candidates.some(type => type.id === choice)) row = chooseSubtype(row, at, choice)
    })
    row.priceTarget = linkedHousingTypeId(row) === linkedHousingTypeId(old) ? old.priceTarget : undefined
    next.rows[index] = row
    row.targets = row.targets.map((target, at) => {
      const choice = old.targets[at].destination
      if (choice === 'new' || choice === 'exclude' || workspace.supply.rows.find(item => item.key === row.proposal.destination)?.targets.some(item => (item.id ? String(item.id) : item.key) === choice)) target = remapTarget(next, index, at, choice, workspace)
      return { ...target, cells: target.cells.map(retain) }
    })
    return row
  })
  next.schedules = next.schedules.map((schedule, index) => {
    const old = previous.schedules[index]
    schedule = remapSchedule(next, index, old.proposal.existingIds.filter(id => workspace.events.some(event => event.id === id)), workspace)
    return { ...schedule, include: old.include, reviewed: old.reviewed, cells: schedule.cells.map(retain) }
  })
  return next
}

type Status = { label: string; tone: 'error' | 'warning' | 'pending' | 'muted' | 'done' }
/** 행 상태. 적용 제외 → 확인 필요 → 주택타입 미연결 → 준비됨 순서로 하나만 보인다. */
export function rowStatus(row: RowReview, pendingCells: boolean): Status {
  if (!row.include) return { label: '적용 제외', tone: 'muted' }
  if (pendingRow(row) || pendingSubtypes(row).length || pendingCells) return { label: '확인 필요', tone: 'pending' }
  // 전용면적이 있으면 저장할 때 서버가 주택형을 정하므로 면적도 주택형도 없을 때만 알린다.
  if (linkedHousingTypeId(row) === null && !newRepresentative(row) && !autoHousingType(row)) return { label: '주택타입 미연결', tone: 'warning' }
  return { label: '준비됨', tone: 'done' }
}


/** 단지 DB에 연결되지 않은 공급행(새 행 또는 단지가 비어 있는 기존 행)인지. 단지를 정하면 newComplex가 생긴다. */
export function needsComplex(row: RowReview, workspace: JsonWorkspace) {
  if (!row.include) return false
  if (row.proposal.createdComplex) return true
  if (row.proposal.destination === 'new') return !row.proposal.anchorKey
  return workspace.supply.rows.find(item => item.key === row.proposal.destination)?.housingComplexId === null
}

/**
 * 단지가 필요한데 아직 정하지 않은 행. 대응을 확인해도 확인 필요로 남긴다: 새 공급행은 저장도 막히고(assembleReview),
 * 단지가 비어 있는 기존 공급행은 화면이 단지 정하기를 안내하므로 같은 기준으로 센다.
 */
export function undecidedComplex(row: RowReview, workspace: JsonWorkspace) { return needsComplex(row, workspace) && !row.proposal.newComplex }

/** 단지를 정해야 하는 PDF 단지 수 */
export function complexDecisionCount(review: Review, workspace: JsonWorkspace, only?: number[]) {
  return new Set(review.rows.flatMap((row, index) => needsComplex(row, workspace) && (!only || only.includes(index)) ? [review.extraction.supplyRows[index].complexName ?? ''] : [])).size
}
