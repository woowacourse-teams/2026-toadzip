import type { ExtractedAnnouncement, HousingTypeValue, JsonObject, JsonUpdateInput, JsonWorkspace } from '../announcement-json/api'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import type { AnnouncementComplex } from '../supply/supplyCorrectionApi'
import { cellsOf, housingTypeCount, leaseOf, ownCount, toSheetInput, type QuotaKind, type SheetCell, type SheetHousingType, type SheetTarget, type SupplySheet } from '../supply/supplySheet'
import { cellValues, setLeaseAmount, setMembersCount, wholeTypeQuota, type LeaseField } from '../supply/sheetEdits'
import { withoutApplicant } from '../supply/applicationType'
import { evidenceFor, normalizePath, noteFor, receptionPlacesOf, type Evidence, type Extraction } from './extraction'
import { duplicateOf, normalizedMethod, samePlace } from './receptionPlaces'
import { namedHousingType, sameTypeName } from './areas'
import { areaValue, canonicalTarget, missingHousingType, originalDate, proposeSchedules, sameComplex, sourceComplexNames, typeKey, type HousingCandidates, type ScheduleProposal } from './matching'
type Kind = 'text' | 'count' | 'area' | 'money' | 'date' | 'datetime' | 'month' | 'method' | 'scheduleType' | 'url' | 'housingType'
export type CellState = 'unresolved' | 'notApplicable' | 'invalid' | 'confirm' | 'same' | 'changed' | 'new'
export type ValueMode = 'keep' | 'extracted' | 'manual'
export type Cell = { path: string; field: string; label: string; kind: Kind; before: unknown; value: unknown; selected: boolean; edited: boolean; reviewed: boolean; mode: ValueMode; input?: string; manualInput?: string; extracted: { value: unknown; state: CellState; note: string }; locked: boolean; note: string; state: CellState; evidence: Evidence | null }
type FieldSpec = [field: string, label: string, kind: Kind, target?: string]
/** 주택형(신청형) 칸. 검토 경로는 housingTypes[i].필드다. 모집수는 이 주택형 칸끼리만 쓰는 모집 묶음의 합이다. */
export const typeFields: FieldSpec[] = [['name', '주택형', 'text'], ['exclusiveArea', '전용면적 (㎡)', 'area'], ['supplyArea', '공급면적 (㎡)', 'area'], ['expectedMoveInMonth', '입주 예정월', 'month'], ['count', '모집수', 'count'], ['housingTypeId', '연결 주택타입', 'housingType']]
/** 주택형 × 공급대상 칸 하나의 값. 모집·예비는 그 대상만의 수다. 검토 경로는 housingTypes[i].cells[n].필드다(n은 PDF 칸 순번). */
export const cellFields: FieldSpec[] = [['winner', '모집', 'count'], ['reserve', '예비', 'count'], ['rentalDeposit', '보증금 (원)', 'money'], ['monthlyRent', '월 임대료 (원)', 'money'], ['convertedDeposit', '전환보증금 (원)', 'money']]
/** 공급대상 이름·신청 조건. 공급대상은 여러 주택형의 칸이 함께 가리키므로 공고 전체에서 한 번 고친다. 검토 경로는 targets[t].필드다. */
export const targetFields: FieldSpec[] = [['label', '공급대상', 'text'], ['applicationCondition', '신청 조건', 'text']]
export const scheduleFields: FieldSpec[] = [['scheduleType', '일정 유형', 'scheduleType'], ['name', '일정명', 'text'], ['startAt', '시작 일시', 'datetime'], ['endAt', '종료 일시', 'datetime']]
export const metadataFields: FieldSpec[] = [['postedDate', '공고일', 'date'], ['applicationStartDate', '접수 시작일', 'date'], ['applicationEndDate', '접수 종료일', 'date'], ['winnerAnnouncementDate', '당첨자 발표일', 'date']]
export const receptionFields: FieldSpec[] = [['name', '접수처명', 'text'], ['method', '접수 방식', 'method'], ['address', '접수처 주소', 'text'], ['contact', '연락처', 'text'], ['url', '접수처 URL', 'url'], ['guidance', '안내 문구', 'text']]
/**
 * 접수처 하나의 검토. 공고문에는 인터넷 접수와 현장 접수처 여러 곳이 함께 나온다. extracted는 PDF 접수처 순번(null이면 PDF에 없는 지금 접수처),
 * existing은 짝지은 지금 접수처 순번(null이면 PDF에서 새로 더하는 접수처)이다. include가 false면 저장할 때 이 접수처를 뺀다.
 */
/** duplicateOf는 PDF에 없는 지금 접수처가 다른 지금 접수처와 같은 곳일 때 그 접수처 순번이다(같은 곳 중복, 기본으로 뺀다). */
export type ReceptionReview = { extracted: number | null; existing: number | null; include: boolean; cells: Cell[]; duplicateOf?: number | null }
/** PDF 칸 하나(주택형 × 공급대상)의 검토. cell은 PDF 칸 순번, existing은 처음 짝지은 지금 공급표 칸 키다(없으면 새 칸). */
export type TargetReview = { cell: number; existing: string | null; cells: Cell[] }
/** PDF 공급대상 하나의 이름·신청 조건 검토. target은 PDF 공급대상 순번이다. */
export type TargetLabelReview = { target: number; cells: Cell[] }
/** 칸 여럿이 함께 쓰는 PDF 수량 묶음(주택형 모집수 제외)의 검토. quota는 PDF 묶음 순번이다. */
export type SharedReview = { quota: number; cell: Cell }
/**
 * PDF 단지 이름 하나가 저장될 단지. 지금 공급표의 같은 단지 주택형이나 공고 단지로 정하고, 정하지 못하면 단지 정하기에서 고른다.
 * chosen은 관리자가 단지 정하기에서 고른 단지다.
 */
export type ComplexReview = { announcementComplexId: number | null; complexName: string; housingComplexId: number | null; housingComplexName: string | null
  supplyPnu: string | null; category: SheetHousingType['category']; chosen?: boolean }
/**
 * PDF 주택형 하나의 검토. source는 PDF 주택형 순번이고 화면 순번과 같다. key는 저장할 주택형 키로, 지금 공급표의 같은 주택형이면
 * 그 키(row:{id}), 아니면 새 키(new:…)다. candidates는 저장할 단지의 주택타입이다.
 */
export type RowReview = { source: number; key: string; existing: SheetHousingType | null; candidates: MatchingHousingType[]; cells: Cell[]; targets: TargetReview[]
  /** 주택형 기본 임대조건으로 쓸 대상 순번(targets의 순번). null이면 지금 값 유지, 없으면 housingTypePrice가 정한 기본값 */
  priceTarget?: number | null }
export type ScheduleReview = { proposal: ScheduleProposal; include: boolean; reviewed: boolean; cells: Cell[] }
export type Review = { extraction: Extraction; rows: RowReview[]; labels: TargetLabelReview[]; shared: SharedReview[]; complexes: Record<string, ComplexReview>
  schedules: ScheduleReview[]; metadata: Cell[]; reception: ReceptionReview[] }
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
/**
 * 칸 검토를 만든다. lookup은 칸의 미확인·해당 없음·근거를 찾을 공급표 경로(앞이 우선)다. 공급표 경로(quotas[2].count, cells[3].reserve)는
 * 칸 하나를 여러 곳에서 보이는 검토 경로와 달라 따로 받는다. 없으면 검토 경로로 찾는다.
 */
function cells(extraction: Extraction, prefix: string, specs: FieldSpec[], source: JsonObject, before: JsonObject = {}, lookup?: (field: string, path: string) => string[]): Cell[] {
  return specs.map(([field, label, kind, target = field]) => {
    const path = `${prefix}.${field}`, value = normalized(kind, source[field]), previous = normalized(kind, before[target])
    const paths = lookup ? lookup(field, path) : [path]
    const first = <T,>(find: (at: string) => T | null) => paths.reduce<T | null>((found, at) => found ?? find(at), null)
    const unused = first(at => noteFor(extraction.notApplicable, at)), evidence = first(at => evidenceFor(extraction.evidence, at))
    // 미확인으로 적혔어도 근거와 올바른 값이 있으면(목록 모집수와 다름 같은 참고) 막지 않고 확인 후 고르게 한다. 안내는 칸 옆에 남긴다.
    const unresolvedNote = first(at => noteFor(extraction.unresolved, at))
    const informational = unresolvedNote && evidence !== null && value !== null && validCellValue(kind, value) ? unresolvedNote : null
    const missing = informational ? null : unresolvedNote
    const typeName = field === 'name' && prefix.startsWith('housingTypes[')
    const areaOnly = typeName && areaOnlyTypeName(value)
    // 주택형 표기는 숫자 앞뒤의 0만 다르면(074.9900A ↔ 74.9900A) 같은 표기로 보고 기존 표기를 유지한다.
    const sameValue = JSON.stringify(value) === JSON.stringify(previous) || typeName && numberKey(value) === numberKey(previous)
    let state: Cell['state'] = unused || areaOnly || kind === 'housingType' && value === null ? 'notApplicable' : missing || value === null ? 'unresolved' : !validCellValue(kind, value) ? 'invalid' : sameValue ? 'same' : previous === null ? 'new' : 'changed'
    const moreDetailed = typeof previous === 'string' && typeof value === 'string' && previous.length > value.length && previous.includes(value) && prefix.startsWith('receptionPlaces[')
    const foreign = kind === 'text' ? foreignCharacters(value) : ''
    // 모델이 PDF 공급 표 대신 LH 주택형 이름(24 → 24A)을 쓰기도 한다. 주택형 자기 근거 인용에 없는 표기는 사람이 대조한다.
    // 여러 주택형을 함께 적은 표기(26A·26B)는 표기마다 대조한다.
    const quotes = extraction.evidence.filter(item => item.path === prefix || item.path.startsWith(`${prefix}.`)).map(item => compact(item.quote))
    const unquoted = typeName && typeof value === 'string' && evidence !== null && [prefix, path].includes(evidence.path)
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
/** 연결 주택타입 칸이 비어 있을 때. */
const keepTypeNote = '지금 연결한 주택타입을 유지합니다. 다른 주택타입에 연결할 때만 직접 고르세요.'
const autoTypeNote = '저장하면 같은 단지에서 이름이 같은 주택타입에 연결하고, 없으면 PDF 주택타입·면적으로 새로 만듭니다. 다른 주택타입에 연결할 때만 직접 고르세요.'
const protectedNote = '관리자가 직접 고친 값입니다. 기본은 지금 값을 유지합니다. 공고문 값이 맞으면 PDF 값을 고르세요.'
const noConditionNote = '공고문에 이 대상의 신청 조건이 없습니다.'
const acrossTypesNote = '다른 주택형과 함께 모집하는 수라 주택형 모집수는 따로 두지 않습니다. 함께 쓰는 수에서 확인하세요.'
/** 공고문에 없는 값. 저장하면 공고문을 따라 그 값을 두지 않는다(직접 입력하면 그 값). */
function notApplicable(cell: Cell, note: string): Cell {
  return { ...cell, state: 'notApplicable', selected: false, mode: 'keep', note, extracted: { ...cell.extracted, state: 'notApplicable', note } }
}

/** PDF 공급표의 키. 화면 안에서만 쓰고 저장할 때 지금 공급표 키나 새 키로 바꾼다. */
const pdfKey = { type: (at: number) => `pdf:t${at}`, target: (at: number) => `pdf:g${at}`, cell: (at: number) => `pdf:c${at}`, quota: (at: number) => `pdf:q${at}`, lease: (at: number) => `pdf:l${at}` }
const pdfIndex = (key: string) => Number(key.slice(5))
/** 추출 공급표를 공급표 모양으로 바꿔 공급표 계산(주택형 모집수·함께 쓰는 수·임대조건)을 그대로 쓴다. */
export function pdfSheet(extraction: Pick<Extraction, 'sheet'>): SupplySheet {
  const { housingTypes, targets, cells, quotas, leaseTerms } = extraction.sheet
  return {
    housingTypes: housingTypes.map((type, at) => ({ key: pdfKey.type(at), id: null, announcementComplexId: null, complexName: type.complexName ?? '', housingComplexId: null, housingComplexName: null,
      housingTypeId: null, newHousingType: null, name: type.name ?? '', exclusiveArea: type.exclusiveArea, supplyArea: type.supplyArea, expectedMoveInMonth: type.expectedMoveInMonth,
      supplyPnu: null, category: null, displayOrder: at, adminModified: false })),
    targets: targets.map((target, at) => ({ key: pdfKey.target(at), id: null, label: target.label, applicationCondition: target.applicationCondition, displayOrder: at })),
    cells: cells.map((cell, at) => ({ key: pdfKey.cell(at), id: null, housingType: pdfKey.type(cell.housingType), target: pdfKey.target(cell.target), displayOrder: at })),
    quotas: quotas.map((quota, at) => ({ key: pdfKey.quota(at), id: null, kind: quota.kind, count: quota.count, members: quota.members.map(pdfKey.cell), adminModified: false })),
    leaseTerms: leaseTerms.map((lease, at) => ({ key: pdfKey.lease(at), id: null, rentalDeposit: lease.rentalDeposit, monthlyRent: lease.monthlyRent, convertedDeposit: lease.convertedDeposit,
      members: lease.members.map(pdfKey.cell), adminModified: false })),
  }
}

const sameMembers = (left: string[], right: string[]) => left.length === right.length && left.every(key => right.includes(key))
const targetKeyOf = (label: string, condition: string) => `${canonicalTarget(label).normalize('NFKC').replace(/\s+/g, '').toLowerCase()}|${condition.normalize('NFKC').replace(/\s+/g, '')}`
/** 같은 공급대상인지. 소득 유무 표기(소득無·소득(×))와 띄어쓰기만 다르면 같다. */
function sameTarget(left: Pick<SheetTarget, 'label' | 'applicationCondition'>, right: Pick<SheetTarget, 'label' | 'applicationCondition'>) {
  return Boolean(left.label.trim()) && targetKeyOf(left.label, left.applicationCondition) === targetKeyOf(right.label, right.applicationCondition)
}
/** 지금 공급표의 이 주택형 칸 중 공급대상이 같은 칸 */
function sameTargetCell(sheet: SupplySheet, housingTypeKey: string, target: Pick<SheetTarget, 'label' | 'applicationCondition'>) {
  return cellsOf(sheet, housingTypeKey).find(cell => { const item = sheet.targets.find(entry => entry.key === cell.target); return item && sameTarget(item, target) }) ?? null
}
/** 단지 묶음 키. 공급 표에 단지 이름이 없는 주택형은 이름 없는 묶음('')이다. */
export const complexKey = (name: string | null | undefined) => name?.trim() ?? ''
const complexLabel = (key: string) => key || '단지 이름 없음'

/** PDF 단지 이름이 가리키는 공고 단지. 이름·블록 번호·주소로 하나만 맞을 때, 또는 단지 이름이 없고 공고 단지가 하나일 때다. */
function announcementComplexOf(extraction: Extraction, workspace: JsonWorkspace, name: string | null): AnnouncementComplex | undefined {
  const complexes = workspace.supply.announcementComplexes
  if (!name) return complexes.length === 1 ? complexes[0] : undefined
  const { names } = sourceComplexNames(extraction, name, complexes.map(complex => complex.name), complexes)
  return names.length === 1 ? complexes.find(complex => complex.name === names[0]) : undefined
}
/**
 * PDF 주택형마다 지금 공급표의 같은 주택형. 같은 단지(공고 단지 또는 단지 이름)이고 이름이 같은 주택형이며, 한 주택형은 한 번만 짝짓는다.
 * 짝이 없는 PDF 주택형은 새로 더하고, 짝이 없는 지금 주택형은 저장하면 지운다(removedHousingTypes).
 */
function matchExisting(extraction: Extraction, workspace: JsonWorkspace): (SheetHousingType | null)[] {
  const used = new Set<string>(), existing = [...workspace.supply.sheet.housingTypes].sort((left, right) => left.displayOrder - right.displayOrder)
  return extraction.sheet.housingTypes.map(type => {
    if (!type.name) return null
    const complex = announcementComplexOf(extraction, workspace, type.complexName)
    const inComplex = (item: SheetHousingType) => !type.complexName || (complex !== undefined && item.announcementComplexId === complex.id) || sameComplex(item.complexName, type.complexName)
    const named = (item: SheetHousingType) => typeKey(item.name) === typeKey(type.name) || sameTypeName(item.name, type.name, item.exclusiveArea, type.exclusiveArea)
    const match = existing.find(item => !used.has(item.key) && inComplex(item) && named(item))
    if (match) used.add(match.key)
    return match ?? null
  })
}
const complexOfType = (type: SheetHousingType): ComplexReview => ({ announcementComplexId: type.announcementComplexId, complexName: type.complexName, housingComplexId: type.housingComplexId,
  housingComplexName: type.housingComplexName, supplyPnu: type.supplyPnu, category: type.category })
/** PDF 단지 이름마다 저장할 단지. 단지에 연결된 같은 주택형이 있으면 그 단지, 없으면 맞는 공고 단지, 그것도 없으면 정하지 않은 단지다. */
function complexReviews(extraction: Extraction, workspace: JsonWorkspace, existing: (SheetHousingType | null)[]): Record<string, ComplexReview> {
  const result: Record<string, ComplexReview> = {}
  for (const key of new Set(extraction.sheet.housingTypes.map(type => complexKey(type.complexName)))) {
    const indexes = extraction.sheet.housingTypes.flatMap((type, at) => complexKey(type.complexName) === key ? [at] : [])
    const linked = indexes.map(at => existing[at]).find(type => type?.housingComplexId != null)
    const complex = announcementComplexOf(extraction, workspace, key || null)
    result[key] = linked ? complexOfType(linked) : complex
      ? { announcementComplexId: complex.id, complexName: complex.name, housingComplexId: complex.housingComplexId, housingComplexName: complex.housingComplexName, supplyPnu: null, category: null }
      : { announcementComplexId: null, complexName: key, housingComplexId: null, housingComplexName: null, supplyPnu: null, category: null }
  }
  return result
}
/**
 * 이 주택형이 저장될 단지. 지금 공급표의 같은 주택형이 단지에 연결돼 있으면 그 단지이고, 아니면 PDF 단지 이름으로 정한 단지다.
 * 단지 없는 지금 주택형은 정한 단지를 연결하되 공고 단지·소재지는 지금 값을 지킨다.
 */
export function complexFor(review: Pick<Review, 'extraction' | 'complexes' | 'rows'>, index: number): ComplexReview {
  const existing = review.rows[index]?.existing
  if (existing?.housingComplexId != null) return complexOfType(existing)
  const decided = review.complexes[complexKey(review.extraction.sheet.housingTypes[index]?.complexName)]
  if (!existing) return decided
  return { ...decided, announcementComplexId: existing.announcementComplexId ?? decided.announcementComplexId, complexName: existing.complexName || decided.complexName,
    supplyPnu: existing.supplyPnu ?? decided.supplyPnu, category: existing.category ?? decided.category }
}
/** 단지를 아직 정하지 않은 주택형인지. 단지를 정해야 저장할 수 있다. */
export function needsComplex(review: Pick<Review, 'extraction' | 'complexes' | 'rows'>, index: number) { return complexFor(review, index).housingComplexId === null }

/** 관리자가 고친 값(보호 값)과 PDF 값이 다르면 확인 필요로 두고 기본은 지금 값을 유지한다. */
function protect(cell: Cell, modified: boolean): Cell {
  if (!modified || cell.kind === 'housingType' || cell.before === null || !['changed', 'confirm'].includes(cell.state)) return cell
  return { ...cell, state: 'confirm', selected: false, mode: 'keep', note: protectedNote, extracted: { ...cell.extracted, state: 'confirm', note: protectedNote } }
}
/** 칸 하나만 멤버인 묶음(그 대상만의 수) */
const ownQuota = (sheet: SupplySheet, cellKey: string, kind: QuotaKind) => sheet.quotas.find(quota => quota.kind === kind && quota.members.length === 1 && quota.members[0] === cellKey)
const countKind = (field: string): QuotaKind | null => field === 'winner' ? 'WINNER' : field === 'reserve' ? 'RESERVE' : null

function rowReview(extraction: Extraction, index: number, workspace: JsonWorkspace, existing: SheetHousingType | null, candidates: MatchingHousingType[]): RowReview {
  const sheet = workspace.supply.sheet, pdf = pdfSheet(extraction), source = extraction.sheet.housingTypes[index], typeKey = pdfKey.type(index), prefix = `housingTypes[${index}]`
  const whole = wholeTypeQuota(pdf, typeKey), existingWhole = existing ? wholeTypeQuota(sheet, existing.key) : null
  const typeCells = cellsOf(pdf, typeKey).map(cell => cell.key)
  const acrossTypes = pdf.quotas.some(quota => quota.kind === 'WINNER' && quota.members.some(member => typeCells.includes(member)) && quota.members.some(member => !typeCells.includes(member)))
  const values: JsonObject = { name: source.name, exclusiveArea: source.exclusiveArea, supplyArea: source.supplyArea, expectedMoveInMonth: source.expectedMoveInMonth, count: housingTypeCount(pdf, typeKey), housingTypeId: null }
  const before: JsonObject = existing ? { name: existing.name, exclusiveArea: existing.exclusiveArea, supplyArea: existing.supplyArea, expectedMoveInMonth: existing.expectedMoveInMonth,
    count: housingTypeCount(sheet, existing.key), housingTypeId: existing.housingTypeId } : {}
  const typeValues = cells(extraction, prefix, typeFields, values, before, (field, path) => field === 'count' && whole ? [`quotas[${pdfIndex(whole.key)}].count`, path] : [path])
    .map(cell => cell.field === 'count' && cell.value === null && acrossTypes ? notApplicable(cell, acrossTypesNote)
      : protect(cell, cell.field === 'count' ? Boolean(existingWhole?.adminModified) : Boolean(existing?.adminModified)))
  const targets = cellsOf(pdf, typeKey).map((pdfCell): TargetReview => {
    const n = pdfIndex(pdfCell.key), target = extraction.sheet.targets[extraction.sheet.cells[n].target]
    const match = existing ? sameTargetCell(sheet, existing.key, target) : null
    const lease = leaseOf(pdf, pdfCell.key), savedLease = match ? leaseOf(sheet, match.key) : null
    const own: JsonObject = { winner: ownCount(pdf, pdfCell.key, 'WINNER'), reserve: ownCount(pdf, pdfCell.key, 'RESERVE'),
      rentalDeposit: lease?.rentalDeposit ?? null, monthlyRent: lease?.monthlyRent ?? null, convertedDeposit: lease?.convertedDeposit ?? null }
    const current: JsonObject = match ? cellValues(sheet, match) : {}
    // 대상만의 수·임대조건의 미확인·근거는 공급표 경로(묶음·임대조건, 없으면 칸)에 있다. 없으면 이 주택형의 근거로 대조한다.
    const lookup = (field: string, path: string) => {
      const kind = countKind(field), quota = kind ? ownQuota(pdf, pdfCell.key, kind) : undefined
      const sheetPath = kind ? quota ? [`quotas[${pdfIndex(quota.key)}].count`] : [] : lease ? [`leaseTerms[${pdfIndex(lease.key)}].${field}`] : []
      return [...sheetPath, `cells[${n}].${field}`, path]
    }
    const modified = (field: string) => { const kind = countKind(field); return Boolean(match && (kind ? ownQuota(sheet, match.key, kind)?.adminModified : savedLease?.adminModified)) }
    // 공고문이 이 대상의 수를 다른 칸과 함께 적었으면 대상만의 수는 없다. 지금 대상별 수를 남기면 함께 쓰는 수와 두 번 센다.
    const grouped = (field: string) => { const kind = countKind(field); return kind ? pdf.quotas.find(quota => quota.kind === kind && quota.members.length > 1 && quota.members.includes(pdfCell.key)) : undefined }
    return { cell: n, existing: match?.key ?? null, cells: cells(extraction, `${prefix}.cells[${n}]`, cellFields, own, current, lookup).map(cell => {
      const group = cell.value === null ? grouped(cell.field) : undefined
      return group ? notApplicable(cell, `${sharedLabel(pdf, group)} ${group.count}에 들어 있어 대상만의 수는 따로 두지 않습니다.`) : protect(cell, modified(cell.field))
    }) }
  })
  return { source: index, key: existing?.key ?? `new:t${index}`, existing, candidates, cells: typeValues, targets }
}
/** 공급대상 이름·신청 조건. 지금 공급표에 같은 대상이 있으면 그 값과 비교한다. 이름을 읽지 못한 대상은 이름을 넣어야 저장된다. */
function labelReviews(extraction: Extraction, workspace: JsonWorkspace): TargetLabelReview[] {
  return extraction.sheet.targets.map((target, at) => {
    const label = target.label ? canonicalTarget(target.label) : ''
    const existing = workspace.supply.sheet.targets.find(item => sameTarget(item, { label, applicationCondition: target.applicationCondition }))
    const values = cells(extraction, `targets[${at}]`, targetFields, { label, applicationCondition: target.applicationCondition },
      existing ? { label: existing.label, applicationCondition: existing.applicationCondition } : {})
    return { target: at, cells: values.map(cell => cell.field === 'applicationCondition' && !target.applicationCondition ? notApplicable(cell, noConditionNote) : cell) }
  })
}
/** 함께 쓰는 수의 이름. 다른 주택형과 함께 쓰면 주택형 이름을("49A·49B·49C 일반 함께 모집"), 한 주택형 안이면 대상 이름을 잇는다("49A 일반·신혼부부 함께 예비"). */
export function sharedLabel(sheet: SupplySheet, quota: { kind: QuotaKind; members: string[] }) {
  const members = quota.members.map(member => sheet.cells.find(cell => cell.key === member)).filter((cell): cell is SheetCell => cell !== undefined)
  const types = [...new Set(members.map(cell => sheet.housingTypes.find(type => type.key === cell.housingType)?.name || '이름 없는 주택형'))]
  const targets = [...new Set(members.map(cell => sheet.targets.find(target => target.key === cell.target)?.label || '이름 없는 대상'))]
  const names = types.length > 1 ? `${types.join('·')}${targets.length === 1 ? ` ${targets[0]}` : ''}` : `${types[0] ?? ''} ${targets.join('·')}`
  return `${names} 함께 ${quota.kind === 'WINNER' ? '모집' : '예비'}`
}
/** 이 PDF 묶음이 주택형 모집수(한 주택형의 모든 칸이 함께 쓰는 모집 수)인지. 주택형 모집수는 주택형 칸에서 검토한다. */
function typeCountQuota(pdf: SupplySheet, at: number) {
  const quota = pdf.quotas[at], types = new Set(quota.members.map(member => pdf.cells.find(cell => cell.key === member)?.housingType))
  return quota.kind === 'WINNER' && types.size === 1 && wholeTypeQuota(pdf, [...types][0]!) === quota
}
/** 칸 여럿이 함께 쓰는 수. 지금 공급표에 같은 칸들이 함께 쓰는 같은 종류의 수가 있으면 그 값과 비교한다. */
function sharedReviews(extraction: Extraction, workspace: JsonWorkspace, rows: RowReview[]): SharedReview[] {
  const pdf = pdfSheet(extraction), sheet = workspace.supply.sheet
  const existingCell = new Map(rows.flatMap(row => row.targets.map(target => [pdfKey.cell(target.cell), target.existing] as const)))
  return pdf.quotas.flatMap((quota, at) => {
    if (quota.members.length < 2 || typeCountQuota(pdf, at)) return []
    const members = quota.members.map(member => existingCell.get(member) ?? null)
    const current = members.every(member => member !== null) ? sheet.quotas.find(item => item.kind === quota.kind && sameMembers(item.members, members as string[])) : undefined
    const [cell] = cells(extraction, `quotas[${at}]`, [['count', sharedLabel(pdf, quota), 'count']], { count: quota.count }, current ? { count: current.count } : {})
    return [{ quota: at, cell: protect(cell, Boolean(current?.adminModified)) }]
  })
}
/** 이 주택형 칸이 든 함께 쓰는 수 */
export function sharedOf(review: Pick<Review, 'extraction' | 'shared'>, index: number) {
  return review.shared.filter(entry => review.extraction.sheet.quotas[entry.quota].members.some(member => review.extraction.sheet.cells[member].housingType === index))
}
/** 함께 쓰는 수를 확인할 주택형 순번. 멤버 칸이 든 첫 주택형이다. */
export function sharedRow(review: Pick<Review, 'extraction'>, entry: SharedReview) {
  return Math.min(...review.extraction.sheet.quotas[entry.quota].members.map(member => review.extraction.sheet.cells[member].housingType))
}
/** PDF 칸이 가리키는 공급대상 검토 */
export function labelOf(review: Pick<Review, 'extraction' | 'labels'>, cell: number) { return review.labels[review.extraction.sheet.cells[cell].target] }
/** 저장할 공급대상 이름. 고르지 않았으면 지금 값이다. */
export function targetName(review: Pick<Review, 'extraction' | 'labels'>, cell: number) {
  const label = labelOf(review, cell)?.cells.find(item => item.field === 'label')
  const value = label ? savedValue(label) : null
  return typeof value === 'string' ? value.trim() : ''
}

export function createReview(extraction: Extraction, workspace: JsonWorkspace, housing: HousingCandidates): Review {
  const existing = matchExisting(extraction, workspace), complexes = complexReviews(extraction, workspace, existing)
  const rows: RowReview[] = []
  extraction.sheet.housingTypes.forEach((_, index) => {
    const complex = existing[index]?.housingComplexId != null ? existing[index]!.housingComplexId : complexes[complexKey(extraction.sheet.housingTypes[index].complexName)].housingComplexId
    rows.push(rowReview(extraction, index, workspace, existing[index], complex === null ? [] : housing[complex] ?? []))
  })
  const metadata = cells(extraction, 'announcement', metadataFields, extraction.announcement, workspace.detail.data)
  // 정정공고 PDF에는 원공고 공고일이 그대로 적혀 있는 경우가 많아, 다른 공고일은 기본으로 반영하지 않는다.
  const posted = metadata.find(cell => cell.field === 'postedDate')
  if (posted?.state === 'changed' && typeof posted.value === 'string' && originalDate(posted.value, workspace.detail.data)) Object.assign(posted, { selected: false, mode: 'keep', note: '정정공고는 PDF에 원공고 공고일이 적혀 있을 수 있어 기존 공고일을 유지합니다. 정정공고 공고일이 맞는지 확인하세요.' })
  if (workspace.detail.scheduleReviewed) metadata.filter(cell => ['applicationStartDate', 'applicationEndDate'].includes(cell.field)).forEach(cell => { cell.locked = true; cell.selected = false; cell.mode = 'keep'; cell.note = '확인된 접수 기간은 접수 일정에서 수정하세요.' })
  return { extraction, metadata, reception: receptionReviews(extraction, workspace), rows, complexes, labels: labelReviews(extraction, workspace), shared: sharedReviews(extraction, workspace, rows),
    schedules: proposeSchedules(extraction, workspace.events).map((proposal, index) => {
      const values = cells(extraction, `schedules[${index}]`, scheduleFields, extraction.schedules[index], workspace.events.find(event => event.id === proposal.existingIds[0])?.data)
      return { proposal, cells: values, reviewed: false, include: !proposal.needsConfirmation && values.every(cell => validCellValue(cell.kind, cell.value) && !['notApplicable', 'unresolved'].includes(cell.state)) }
    }) }
}
/**
 * 단지를 정한 뒤 그 단지의 주택형 검토에 주택타입 후보를 넣는다. 연결 주택타입 칸은 단지가 바뀌면 지금 선택을 버린다.
 */
export function decideComplex(review: Review, key: string, complex: ComplexReview, candidates: MatchingHousingType[]): Review {
  const next = { ...review, complexes: { ...review.complexes, [key]: complex } }
  return { ...next, rows: review.rows.map((row, index) => {
    if (complexKey(review.extraction.sheet.housingTypes[index].complexName) !== key || row.existing?.housingComplexId != null) return row
    return { ...row, candidates, priceTarget: undefined, cells: row.cells.map(cell => cell.field === 'housingTypeId' ? { ...cell, ...cell.extracted, selected: false, mode: 'keep', edited: false, reviewed: false, input: undefined, manualInput: undefined } : cell) }
  }) }
}
/** 이 공고에 지금 저장된 접수처(공고문 순서) */
export function currentReceptionPlaces(workspace: JsonWorkspace): JsonObject[] {
  const places = workspace.detail.data.receptionPlaces
  return Array.isArray(places) ? places.filter(place => place !== null && typeof place === 'object' && !Array.isArray(place)) as JsonObject[] : []
}
const placeName = (value: unknown) => typeof value === 'string' ? value.replace(/\s+/g, '').toLowerCase() : ''
const methodNote = '주소만 있고 인터넷 주소가 없어 현장 접수로, 또는 인터넷 주소만 있어 인터넷 접수로 읽었습니다. 공고문과 다르면 고치세요.'
/**
 * PDF 접수처를 지금 접수처와 짝짓는다. 방식·이름이 같은 접수처를 먼저, 없으면 같은 곳(도로명+건물번호·URL 호스트, receptionPlaces.samePlace)인
 * 접수처를 고른다. LH 원천은 같은 곳을 다른 이름으로 주므로(관리사무소 ↔ 평택송화단지 아파트 관리사무소) 이름만으로 짝지으면 같은 곳이 두 번 저장된다.
 * 주소가 다른 접수처끼리는 짝짓지 않는다(다른 접수처의 주소·연락처가 섞인다). 짝이 없으면 새 접수처로 더한다.
 * PDF에 없는 지금 접수처는 그대로 두되, 남길 접수처와 같은 곳이면 중복으로 기본에서 뺀다. 이름·방식을 읽지 못한 새 접수처도 기본으로 빼 둔다.
 * 순서는 지금 접수처 순서를 지키고 새 접수처를 PDF 순서로 뒤에 붙인다.
 */
function receptionReviews(extraction: Extraction, workspace: JsonWorkspace): ReceptionReview[] {
  const current = currentReceptionPlaces(workspace), used = new Set<number>()
  const unused = (match: (item: JsonObject) => boolean) => current.findIndex((item, at) => !used.has(at) && match(item))
  const reviews: ReceptionReview[] = extraction.receptionPlaces.map((source, index) => {
    const place: JsonObject = { ...source, method: normalizedMethod(source) as JsonObject[string] }
    let existing = unused(item => item.method === place.method && placeName(item.name) !== '' && placeName(item.name) === placeName(place.name))
    if (existing < 0) existing = unused(item => samePlace(item, place))
    if (existing >= 0) used.add(existing)
    const values = cells(extraction, `receptionPlaces[${index}]`, receptionFields, place, existing >= 0 ? current[existing] : {})
    if (place.method !== source.method) Object.assign(values.find(cell => cell.field === 'method')!, { note: methodNote })
    const readable = values.filter(cell => ['name', 'method'].includes(cell.field)).every(cell => validCellValue(cell.kind, cell.value) && !['notApplicable', 'unresolved', 'invalid'].includes(cell.state))
    return { extracted: index, existing: existing >= 0 ? existing : null, include: existing >= 0 || readable, cells: values }
  })
  current.forEach((place, index) => {
    if (used.has(index)) return
    const twin = duplicateOf(current, index, at => used.has(at))
    reviews.push({ extracted: null, existing: index, include: twin === null, duplicateOf: twin, cells: cells(extraction, `currentReceptionPlaces[${index}]`, receptionFields, {}, place) })
  })
  return reviews.sort((left, right) => (left.existing ?? current.length + (left.extracted ?? 0)) - (right.existing ?? current.length + (right.extracted ?? 0)))
}
/** 저장할 접수처(뺀 접수처 제외). 저장 목록과 서버 오류의 접수처 순번이 이 순서를 함께 쓴다. */
export function savedReceptions(reception: ReceptionReview[]) { return reception.filter(entry => entry.include) }
/** 저장할 접수처 칸. 뺀 접수처의 칸은 확인하지 않는다. */
export function receptionCells(reception: ReceptionReview[]) { return savedReceptions(reception).flatMap(entry => entry.cells) }
/** 저장할 접수처 목록. 지금 접수처에 고른 PDF 값을 덮는다. */
function assembledReceptionPlaces(reception: ReceptionReview[], workspace: JsonWorkspace): JsonObject[] {
  const current = currentReceptionPlaces(workspace)
  return savedReceptions(reception).map(entry => {
    const place: JsonObject = { ...receptionPlaceShape(), ...entry.existing === null ? {} : current[entry.existing] }
    applyCells(place, entry.cells, receptionFields)
    return place
  })
}
const receptionPlaceShape = (place: JsonObject = {}): JsonObject => Object.fromEntries(receptionFields.map(([field]) => [field, place[field] ?? null]))
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
  if (reviewId.startsWith('release:') || reviewId.startsWith('reception:') || reviewId.startsWith('cell:announcement') || reviewId.startsWith('cell:receptionPlaces[') || reviewId.startsWith('cell:currentReceptionPlaces[')) return 'reception'
  return 'supply'
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
  for (const entry of savedReceptions(reception)) {
    for (const cell of entry.cells.filter(item => ['name', 'method'].includes(item.field) && !validCellValue(item.kind, saved(item)))) {
      needs.set(cell.path, `접수처를 저장하려면 ${cell.label}이 필요합니다. 직접 입력하거나 이 접수처를 빼세요.`)
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
 * 서버가 속성 경로로 돌려준 기본정보·접수처 검증 메시지("receptionPlaces[1].name: 필수 값입니다.")를 화면 칸 이름과 위치로 바꾼다.
 * 접수처 순번은 저장한 접수처(뺀 접수처 제외)의 순번이다.
 */
export function serverFieldError(review: Pick<Review, 'reception' | 'metadata'>, message: string) {
  const match = /^([\w.[\]]+): (.+?)(?: \/ |$)/.exec(message)
  if (!match) return null
  const place = /^receptionPlaces\[(\d+)\]\.(\w+)$/.exec(match[1])
  const cell = place ? savedReceptions(review.reception)[Number(place[1])]?.cells.find(item => item.field === place[2])
    : review.metadata.find(item => item.path === `announcement.${match[1]}`)
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
/** 운영자에게 보일 접수처 이름. 1부터 센 번호와 저장할 접수 방식이다. */
export function receptionName(review: Pick<Review, 'reception'>, index: number) {
  const method = review.reception[index]?.cells.find(cell => cell.field === 'method')
  const label = choices.method.find(([key]) => key === (method ? savedValue(method) : null))?.[1]
  return `접수처 ${index + 1}${label ? ` · ${label}` : ''}`
}
/**
 * 접수처를 한 곳만 다루던 때 임시저장한 검토(reception이 칸 목록, extraction.receptionPlace 객체)를 지금 모양으로 읽는다.
 * 그때 고른 칸 선택은 첫 접수처의 같은 칸에 옮긴다. 지금 모양이면 그대로 돌려준다.
 */
export function upgradeDraftReview(review: Review, workspace: JsonWorkspace): Review {
  const legacy = review as unknown as { reception: unknown[]; extraction: Extraction & { receptionPlace?: unknown } }
  if (Array.isArray(legacy.extraction.receptionPlaces) && legacy.reception.every(entry => entry !== null && typeof entry === 'object' && Array.isArray((entry as ReceptionReview).cells))) return review
  const place = legacy.extraction.receptionPlace
  // 예전 결과를 읽을 때와 같은 규칙(값이 하나라도 있으면 접수처 한 곳)으로 읽는다.
  const renamed = <T extends { path: string }>(items: T[]) => items.map(item => ({ ...item, path: normalizePath(item.path) }))
  const extraction: Extraction = { ...review.extraction, evidence: renamed(review.extraction.evidence), unresolved: renamed(review.extraction.unresolved), notApplicable: renamed(review.extraction.notApplicable),
    receptionPlaces: receptionPlacesOf({ receptionPlace: place }) }
  delete (extraction as { receptionPlace?: unknown }).receptionPlace
  const old = new Map((legacy.reception as Cell[]).filter(cell => typeof cell?.path === 'string').map(cell => [normalizePath(cell.path), cell]))
  const reception = receptionReviews(extraction, workspace).map(entry => ({ ...entry, cells: entry.cells.map(cell => preserveCellChoice(cell, old.get(cell.path))) }))
  return { ...review, extraction, reception }
}
/** 확인 항목 ID(row:2, cell:housingTypes[2].…, target:2:0, shared:2:4, price:2)가 가리키는 주택형 번호. 주택형 밖이면 null */
export function rowOfReviewId(reviewId: string): number | null {
  const match = /^(?:(?:row|target|price|shared):(\d+)|cell:housingTypes\[(\d+)\])/.exec(reviewId)
  return match ? Number(match[1] ?? match[2]) : null
}
export type ReviewFilter = 'all' | 'pending' | 'changed' | 'new'
const pendingKinds: [prefix: string, label: string, counted: boolean][] = [['row:', '단지 정하기', false], ['cell:', '값', true], ['target:', '공급대상', true], ['shared:', '함께 쓰는 수', true], ['price:', '주택형 가격', false]]
/** 주택형 하나에 남은 확인 항목. pendingItems 순서(편집 영역 순서)를 그대로 따른다. */
export function rowPendingItems(items: PendingItem[], index: number) { return items.filter(item => rowOfReviewId(item.id) === index) }
/** 남은 확인을 종류별로 줄인 설명. 예: '단지 정하기 · 값 2 · 공급대상 1' */
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
/** 공급대상 칸 하나가 확인 필요인지. 그 칸의 값, 공급대상 이름·신청 조건이 확인 필요이거나 저장할 이름이 비었으면 확인 필요다. */
export function pendingTarget(review: Pick<Review, 'extraction' | 'labels'>, target: TargetReview) {
  return target.cells.some(pendingCell) || (labelOf(review, target.cell)?.cells.some(pendingCell) ?? false) || !targetName(review, target.cell)
}
export function pendingPrice(review: Review, index: number) { return housingTypePrice(review, index, linkedHousingType(review, index)).confirm }
/** PDF 단지 이름별 주택형 번호. 처음 나온 단지 순서를 따른다. 공급정보 화면은 이 묶음대로 한 단지씩 보여 준다. */
export function complexGroups(review: Pick<Review, 'extraction'>) {
  const groups: { key: string; name: string; indexes: number[] }[] = []
  review.extraction.sheet.housingTypes.forEach((type, index) => {
    const key = complexKey(type.complexName), group = groups.find(item => item.key === key)
    if (group) group.indexes.push(index); else groups.push({ key, name: complexLabel(key), indexes: [index] })
  })
  return groups
}
/** 이 PDF 주택형이 든 단지 묶음 순번 */
export function groupOf(review: Pick<Review, 'extraction'>, index: number) {
  return complexGroups(review).findIndex(group => group.indexes.includes(index))
}
/** 확인 항목으로 옮길 곳. 단지 정하기 확인(row:i)은 단지 정하기로 옮긴다. 상태 배지와 하단 미해결 이동이 같이 쓴다. */
export function pendingJumpTarget(id: string) { return id.startsWith('row:') ? 'complex' : id }
/** 화면에 보이는 순서(공급정보 단지 묶음 → 일정 → 접수처·기본정보)로 남은 확인 항목을 돌려준다. */
/** savesHousingTypes가 false면(PDF로 공급정보 편집표만 채울 때) 주택형 값을 저장하지 않으므로 주택형 가격 확인을 넣지 않는다. */
export function pendingItems(review: Review, savesHousingTypes = true, workspace?: JsonWorkspace, release?: ReleaseChoice): PendingItem[] {
  const items: PendingItem[] = []
  complexGroups(review).flatMap(group => group.indexes).forEach(index => {
    const row = review.rows[index]
    if (!row) return
    // 편집 영역 순서(단지 → 값 비교 → 공급대상 칸 → 함께 쓰는 수 → 주택형 가격)를 따른다.
    if (needsComplex(review, index)) items.push({ id: `row:${index}`, section: 'supply' })
    row.cells.filter(pendingCell).forEach(cell => items.push({ id: `cell:${cell.path}`, section: 'supply' }))
    row.targets.forEach((target, at) => { if (pendingTarget(review, target)) items.push({ id: `target:${index}:${at}`, section: 'supply' }) })
    review.shared.forEach(entry => { if (sharedRow(review, entry) === index && pendingCell(entry.cell)) items.push({ id: `shared:${index}:${entry.quota}`, section: 'supply' }) })
    if (savesHousingTypes && pendingPrice(review, index)) items.push({ id: `price:${index}`, section: 'supply' })
  })
  review.schedules.forEach((schedule, index) => {
    if (pendingSchedule(schedule)) items.push({ id: `schedule:${index}`, section: 'schedule' })
    if (schedule.include) schedule.cells.filter(pendingCell).forEach(cell => items.push({ id: `cell:${cell.path}`, section: 'schedule' }))
  })
  const needs = fieldNeeds(review, savesHousingTypes ? workspace : undefined, release)
  for (const path of needs.keys()) if (path.startsWith('release:')) items.push({ id: path, section: 'reception' })
  for (const cell of [...receptionCells(review.reception), ...review.metadata].filter(cell => pendingField(cell, needs))) items.push({ id: `cell:${cell.path}`, section: 'reception' })
  return items
}
/** 단지를 정해야 하는 PDF 단지 묶음. only가 있으면 그 주택형들(지금 보이는 단지)의 묶음만이다. */
export function undecidedGroups(review: Review, only?: number[]) {
  return complexGroups(review).filter(group => group.indexes.some(index => (!only || only.includes(index)) && needsComplex(review, index)))
}
export function allCells(review: Review) {
  return [...review.metadata, ...review.reception.flatMap(entry => entry.cells), ...review.rows.flatMap(row => [...row.cells, ...row.targets.flatMap(target => target.cells)]),
    ...review.labels.flatMap(entry => entry.cells), ...review.shared.map(entry => entry.cell), ...review.schedules.flatMap(schedule => schedule.cells)]
}
export type PendingChange = { section: '기본정보' | '접수처' | '공급정보' | '일정'; scope: string; cell: Cell }
/** 저장하면 PDF에 없어 지워지는 지금 공급표 주택형 */
export function removedHousingTypes(review: Pick<Review, 'rows'>, workspace: JsonWorkspace) {
  const kept = new Set(review.rows.flatMap(row => row.existing ? [row.existing.key] : []))
  return workspace.supply.sheet.housingTypes.filter(type => !kept.has(type.key))
}
/** 저장하면 실제로 바뀌는 값만 영역별로 모은다. 하단의 반영 개수와 영역별 요약에 쓴다. workspace가 있으면 지워질 주택형도 센다. */
export function pendingChanges(review: Review, workspace?: JsonWorkspace): PendingChange[] {
  const changed = (cell: Cell) => cell.selected && !cell.locked && JSON.stringify(cell.before) !== JSON.stringify(cell.value)
  const list = (section: PendingChange['section'], scope: string, cells: Cell[]) =>
    cells.filter(changed).map(cell => ({ section, scope, cell }))
  const removal = (scope: string, cell: Cell): PendingChange => ({ section: '공급정보', scope, cell: { ...cell, value: null, selected: true, locked: false } })
  return [
    ...list('기본정보', '공고', review.metadata),
    ...review.reception.flatMap((entry, index) => {
      if (entry.include) return list('접수처', receptionName(review, index), entry.cells)
      // 뺀 기존 접수처는 저장하면 지워지므로 바뀌는 값으로 센다.
      const name = entry.cells.find(cell => cell.field === 'name')
      return entry.existing !== null && name ? [{ section: '접수처' as const, scope: receptionName(review, index), cell: { ...name, label: '접수처 삭제', value: null, selected: true, locked: false } }] : []
    }),
    ...review.rows.flatMap((row, index) => {
      const scope = rowName(review, index)
      return [...list('공급정보', scope, row.cells), ...row.targets.flatMap(target => list('공급정보', `${scope} / ${targetName(review, target.cell) || '이름 없는 대상'}`, target.cells))]
    }),
    ...review.labels.flatMap(entry => list('공급정보', `공급대상 ${entry.target + 1}`, entry.cells)),
    ...review.shared.flatMap(entry => list('공급정보', entry.cell.label, [entry.cell])),
    ...workspace ? removedHousingTypes(review, workspace).map(type => removal(`${type.complexName} · ${type.name}`, removedCell(type))) : [],
    ...review.schedules.flatMap((schedule, index) => schedule.include ? list('일정', `일정 ${index + 1} · ${shown(schedule.cells.find(cell => cell.field === 'name')?.value)}`, schedule.cells) : []),
  ]
}
/** 지워질 주택형을 반영 목록에 보이는 칸 */
function removedCell(type: SheetHousingType): Cell {
  const extracted = { value: null, state: 'changed' as const, note: 'PDF 공급표에 없어 저장하면 지웁니다.' }
  return { path: `removed:${type.key}`, field: 'name', label: '주택형 삭제', kind: 'text', before: type.name, value: null, selected: true, edited: false, reviewed: true, mode: 'extracted',
    extracted, locked: false, note: extracted.note, state: 'changed', evidence: null }
}
/** 저장할 칸. 변경·추가 개수를 센다. */
export function applicableCells(review: Review) {
  return [...review.metadata, ...receptionCells(review.reception), ...review.rows.flatMap(row => [...row.cells, ...row.targets.flatMap(target => target.cells)]),
    ...review.labels.flatMap(entry => entry.cells), ...review.shared.map(entry => entry.cell), ...review.schedules.filter(schedule => schedule.include).flatMap(schedule => schedule.cells)]
}
/** 운영자에게 보일 PDF 주택형 이름. 1부터 센 번호와 공고문 주택형 표기다. */
export function rowName(review: Pick<Review, 'extraction'>, index: number) { return `주택형 ${index + 1}(${review.extraction.sheet.housingTypes[index]?.name ?? '표기 미확인'})` }
/** 저장하면 이 주택형에 들어갈 모집수. 반영을 고른 칸은 그 값, 아니면 지금 공급표의 값이다. */
export function assembledCount(row: RowReview): number | null {
  const cell = row.cells.find(item => item.field === 'count')
  const value = cell ? savedValue(cell) : null
  return typeof value === 'number' ? value : null
}

/** 새로 만들 단지 주택타입 이름. 한 면적에 주택타입이 여럿이면 첫 표기를 쓰고 끝의 신청자 표기( )·[ ]는 뗀다(서버 newTypeName과 같은 규칙). */
const createdTypeName = (name: string) => withoutApplicant(name.split('·')[0])
/** 이 주택형이 연결할 단지 주택타입의 공고문 이름. 공고문 주택타입이 하나면 그 이름, 아니면 저장할 주택형 표기다. */
export function linkName(review: Pick<Review, 'extraction' | 'rows'>, index: number) {
  const source = review.extraction.sheet.housingTypes[index], house = source.houseTypes.length === 1 ? source.houseTypes[0].name : null
  const cell = review.rows[index].cells.find(item => item.field === 'name'), saved = cell ? savedValue(cell) : null
  return createdTypeName(house ?? (typeof saved === 'string' ? saved : ''))
}
const savedOf = (row: RowReview, field: string) => { const cell = row.cells.find(item => item.field === field); return cell ? savedValue(cell) : null }
/** 연결 주택타입 칸에서 고른 주택타입, 아니면 지금 공급표 주택형에 연결된 주택타입. 없으면 null이다. */
export function linkedHousingTypeId(row: RowReview): number | null {
  const id = savedOf(row, 'housingTypeId')
  return typeof id === 'number' ? id : null
}
/**
 * 연결 주택타입을 정하지 않은 주택형이 저장할 때 연결할 단지 주택타입. 같은 단지의 이름이 같은 주택타입이고(namedHousingType),
 * 없으면 null이라 저장할 때 그 이름으로 새로 만든다.
 */
export function expectedHousingType(review: Pick<Review, 'extraction' | 'rows'>, index: number) {
  const row = review.rows[index]
  if (linkedHousingTypeId(row) !== null) return null
  return namedHousingType(row.candidates, linkName(review, index), savedOf(row, 'exclusiveArea'))
}
/**
 * 연결 주택타입의 후보 정보. 연결을 비워 둔 주택형은 저장할 때 연결할 주택타입이다.
 * 새로 만들 주택타입처럼 연결할 주택타입이 없으면 undefined, 연결했지만 후보에 없어 지금 값을 모르면 null이다.
 */
export function linkedHousingType(review: Pick<Review, 'extraction' | 'rows'>, index: number): MatchingHousingType | null | undefined {
  const row = review.rows[index], id = linkedHousingTypeId(row)
  if (id === null) return expectedHousingType(review, index) ?? undefined
  return row.candidates.find(type => type.id === id) ?? null
}

export type PriceOption = { index: number; label: string; deposit: number; monthlyRent: number }
/**
 * 주택형 기본 임대조건(보증금·월 임대료)으로 쓸 공급대상. 대상의 금액이 모두 있고 하나로 같으면 그 값을 쓰고,
 * 다르면 '일반' 대상을 미리 고른다. '일반'도 없으면 지금 값을 유지하고 관리자가 검토 화면에서 고른다.
 * 지금 값이 이전 PDF 보정에서 관리자가 고른 공고문 값(current)이면, 다른 금액으로 바꾸기 전에 확인 필요(confirm)로 두고 기본은 유지한다.
 * 연결 주택형이 후보에 없어 지금 값을 모르면(current가 null) 확인 없이 덮지 않도록 기본은 유지한다(unknown).
 */
export function housingTypePrice(review: Pick<Review, 'extraction' | 'labels' | 'rows'>, index: number, current?: MatchingHousingType | null): { options: PriceOption[]; chosen: number | null; same: boolean; confirm: boolean; unknown: boolean } {
  const row = review.rows[index]
  const amount = (cells: Cell[], field: string) => {
    const cell = cells.find(item => item.field === field)
    // 금액 칸은 숫자 문자열('41848000')로 들고 있다.
    const value = cell ? savedValue(cell) : null
    const number = typeof value === 'string' && /^\d{1,15}$/.test(value) ? Number(value) : value
    return typeof number === 'number' && Number.isSafeInteger(number) && number >= 0 ? number : null
  }
  const options = row.targets.flatMap((target, at) => {
    const deposit = amount(target.cells, 'rentalDeposit'), monthlyRent = amount(target.cells, 'monthlyRent')
    return deposit === null || monthlyRent === null ? [] : [{ index: at, label: targetName(review, target.cell) || `대상 ${at + 1}`, deposit, monthlyRent }]
  })
  // 금액을 읽지 못한 대상이 있으면 그 대상의 금액을 모르므로 모두 같다고 보지 않는다.
  const same = options.length > 0 && options.length === row.targets.length && options.every(option => option.deposit === options[0].deposit && option.monthlyRent === options[0].monthlyRent)
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

/** 관리자가 이 칸의 값을 받아들였는지. 칸이 없거나, 반영을 골랐거나, 현재값과 같으면 받아들인 것이다. */
function acceptedCell(cell: Cell | undefined) { return !cell || cell.selected && !cell.locked || cell.state === 'same' }
const nameKey = (value: unknown) => typeof value === 'string' ? withoutTrailingParentheses(value).normalize('NFKC').replace(/\s+/g, '').toLowerCase() : ''
/** "39(창업인)"처럼 끝 괄호는 공급대상 표기라 주택타입 이름에 넣지 않는다. 넣으면 공고마다 이름이 바뀐다. */
function withoutTrailingParentheses(value: string) {
  let result = value.trim()
  while (result.endsWith(')')) {
    let depth = 0, start = -1
    for (let at = result.length - 1; at >= 0; at--) {
      if (result[at] === ')') depth++
      if (result[at] === '(' && --depth === 0) { start = at; break }
    }
    if (start <= 0) break
    result = result.slice(0, start).trim()
  }
  return result
}
/**
 * 연결한 단지 주택타입에 반영할 공고 값. 이름은 공고문 주택타입이 하나이고(84 = 84A·84B처럼 여럿이면 바꾸지 않는다) 주택형 표기를 받아들였을 때만,
 * 신청형 표기와 다를 때 그 이름으로 바꾼다. 공급면적은 받아들인 값만, 세대수는 공고문의 건설호수다.
 */
function housingTypeValue(review: Review, index: number, rowKey: string): HousingTypeValue | null {
  const row = review.rows[index], source = review.extraction.sheet.housingTypes[index]
  const house = source.houseTypes.length === 1 ? source.houseTypes[0].name : null
  const nameCell = row.cells.find(item => item.field === 'name'), areaCell = row.cells.find(item => item.field === 'supplyArea')
  const renamed = acceptedCell(nameCell) && typeof house === 'string' && house.trim() && !house.includes('·') && nameKey(house) !== nameKey(source.name) ? withoutTrailingParentheses(house) : ''
  const area = areaCell?.selected && !areaCell.locked ? areaCell.value : acceptedCell(areaCell) ? source.supplyArea : null
  const count = source.constructedHouseholdCount
  const price = housingTypePrice(review, index, linkedHousingType(review, index))
  const chosen = price.chosen === null ? null : price.options[price.chosen]
  const value: HousingTypeValue = { rowKey, name: renamed && !areaOnlyTypeName(renamed) ? renamed : null,
    supplyArea: validCellValue('area', area) ? area as number : null,
    householdCount: typeof count === 'number' && Number.isSafeInteger(count) && count >= 0 ? count : null,
    basicDeposit: chosen?.deposit ?? null, basicMonthlyRent: chosen?.monthlyRent ?? null }
  return value.name === null && value.supplyArea === null && value.householdCount === null && chosen === null ? null : value
}

/** 저장할 값. 직접 입력이 형식에 맞지 않거나, 고른 PDF 값이 미확인이면 막는다. */
function savedCell(cell: Cell, reviewId: string) {
  if (!cell.locked && cell.mode === 'manual' && !validCellValue(cell.kind, cell.value)) throw new ReviewError(`${cell.label}의 직접 입력값을 확인하거나 기존 값 사용을 선택하세요.`, reviewId)
  if (cell.selected && !cell.locked && (!validCellValue(cell.kind, cell.value) || ['notApplicable', 'unresolved'].includes(cell.state))) throw new ReviewError(`${cell.label}의 값과 근거를 확인하세요.`, reviewId)
  return savedValue(cell)
}
const differs = (cell: Cell, value: unknown) => JSON.stringify(value ?? null) !== JSON.stringify(cell.extracted.value ?? null)
/** 공급표에 쓸 값. 공고문에 없는 값(해당 없음)은 직접 입력하지 않았으면 공고문을 따라 비우고, 그 밖에는 저장값(savedValue)이다. */
const written = (cell: Cell, reviewId: string) => cell.extracted.state === 'notApplicable' && cell.mode !== 'manual' ? cell.extracted.value : savedCell(cell, reviewId)
/**
 * 검토로 저장할 공고 공급표. PDF 공급표가 공급표 전체를 대신한다: 지금 공급표의 같은 주택형·공급대상·칸은 그 키를 쓰고, 없는 것은 새 키로 더하며,
 * PDF에 없는 지금 주택형은 넣지 않아 저장하면 지워진다. 수량 묶음·임대조건은 PDF 묶음을 그대로 옮긴 뒤, 관리자가 지금 값을 유지하거나
 * 직접 고친 칸만 그 값으로 바꾼다.
 */
export function assembledSheet(review: Review, workspace: JsonWorkspace): SupplySheet {
  const pdf = pdfSheet(review.extraction), current = workspace.supply.sheet, extracted = review.extraction.sheet
  const firstUse = (target: number) => {
    for (const [index, row] of review.rows.entries()) { const at = row.targets.findIndex(item => extracted.cells[item.cell].target === target); if (at >= 0) return `target:${index}:${at}` }
    return undefined
  }
  // 1. 공급대상. 이름·신청 조건이 같아지면 하나로 합치고, 지금 공급표에 같은 대상이 있으면 그 키를 쓴다.
  const targets: SheetTarget[] = [], targetOf = new Map<number, string>()
  for (const entry of review.labels) {
    const reviewId = firstUse(entry.target)
    if (!reviewId) continue
    const labelCell = entry.cells.find(cell => cell.field === 'label')!, conditionCell = entry.cells.find(cell => cell.field === 'applicationCondition')!
    const rawLabel = savedCell(labelCell, reviewId), rawCondition = savedCell(conditionCell, reviewId)
    const label = typeof rawLabel === 'string' ? canonicalTarget(rawLabel) : '', condition = typeof rawCondition === 'string' ? rawCondition.trim() : ''
    if (!label) throw new ReviewError(`공급대상 ${entry.target + 1}: 공급대상 이름을 입력하세요.`, reviewId)
    const value = { label, applicationCondition: condition }
    let target = targets.find(item => sameTarget(item, value))
    if (!target) {
      const saved = current.targets.find(item => sameTarget(item, value) && !targets.some(used => used.key === item.key))
      target = { key: saved?.key ?? `new:g${entry.target}`, id: saved?.id ?? null, ...value, displayOrder: targets.length }
      targets.push(target)
    }
    targetOf.set(entry.target, target.key)
  }
  // 2. 주택형. 단지를 정해야 저장한다.
  const housingTypes = review.rows.map((row, index): SheetHousingType => {
    const complex = complexFor(review, index), base = `cell:housingTypes[${index}]`
    if (complex.housingComplexId === null) throw new ReviewError(`${rowName(review, index)}의 단지를 단지 정하기에서 정하세요.`, `row:${index}`)
    const value = (field: string) => savedCell(row.cells.find(cell => cell.field === field)!, `${base}.${field}`)
    const name = value('name')
    if (typeof name !== 'string' || !name.trim()) throw new ReviewError(`${rowName(review, index)}: 주택형 표기를 입력하세요.`, `${base}.name`)
    const exclusiveArea = value('exclusiveArea') as number | null, supplyArea = value('supplyArea') as number | null
    const selected = row.cells.find(cell => cell.field === 'housingTypeId')!
    if (selected.selected && !selected.locked && selected.value !== null && !row.candidates.some(type => type.id === selected.value)) throw new ReviewError('연결 주택타입이 정한 단지에 없습니다. 주택타입을 다시 고르세요.', `${base}.housingTypeId`)
    const linked = linkedHousingTypeId(row) ?? expectedHousingType(review, index)?.id ?? null
    return { ...row.existing ?? { id: null, adminModified: false }, key: row.key, announcementComplexId: complex.announcementComplexId,
      complexName: complex.complexName || complex.housingComplexName || '', housingComplexId: complex.housingComplexId, housingComplexName: complex.housingComplexName,
      housingTypeId: linked, newHousingType: linked === null ? missingHousingType(complex.housingComplexId, row.candidates, linkName(review, index), exclusiveArea, supplyArea) : null,
      name: name.trim(), exclusiveArea, supplyArea, expectedMoveInMonth: value('expectedMoveInMonth') as string | null,
      supplyPnu: complex.supplyPnu, category: complex.category, displayOrder: index }
  })
  // 3. 칸. 지금 주택형에 같은 공급대상 칸이 있으면 그 키를 쓴다.
  const cells: SheetCell[] = [], cellOf = new Map<string, string>()
  for (const [index, row] of review.rows.entries()) {
    for (const [at, entry] of row.targets.entries()) {
      const target = targetOf.get(extracted.cells[entry.cell].target)!
      if (cells.some(cell => cell.housingType === row.key && cell.target === target)) throw new ReviewError(`${rowName(review, index)}: 같은 공급대상이 두 번 있습니다. 공급대상 이름을 고치세요.`, `target:${index}:${at}`)
      const saved = row.existing ? current.cells.find(cell => cell.housingType === row.existing!.key && cell.target === target) : undefined
      const cell = { key: saved?.key ?? `new:c${entry.cell}`, id: saved?.id ?? null, housingType: row.key, target, displayOrder: at }
      cells.push(cell); cellOf.set(pdfKey.cell(entry.cell), cell.key)
    }
  }
  const members = (keys: string[]) => keys.map(key => cellOf.get(key)!)
  // 4. PDF 수량 묶음·임대조건을 옮긴다.
  let sheet: SupplySheet = { housingTypes, targets, cells,
    quotas: pdf.quotas.map((quota, at) => ({ ...quota, key: `new:q${at}`, members: members(quota.members) })),
    leaseTerms: pdf.leaseTerms.map((lease, at) => ({ ...lease, key: `new:l${at}`, members: members(lease.members) })) }
  // 5. 공급대상 칸마다 PDF 값과 다른 저장값(지금 값 유지·직접 입력)을 반영한다.
  for (const [index, row] of review.rows.entries()) {
    for (const [at, entry] of row.targets.entries()) {
      const key = cellOf.get(pdfKey.cell(entry.cell))!
      for (const cell of entry.cells) {
        const value = written(cell, `target:${index}:${at}`)
        if (!differs(cell, value)) continue
        const kind = countKind(cell.field)
        sheet = kind ? setMembersCount(sheet, [key], kind, value as number | null) : setLeaseAmount(sheet, key, cell.field as LeaseField, value as string | null)
      }
    }
  }
  // 6. 함께 쓰는 수
  for (const entry of review.shared) {
    const value = written(entry.cell, `shared:${sharedRow(review, entry)}:${entry.quota}`), quota = pdf.quotas[entry.quota]
    if (differs(entry.cell, value)) sheet = setMembersCount(sheet, members(quota.members), quota.kind, value as number | null)
  }
  // 7. 주택형 모집수. 다른 묶음은 그대로 두고 모든 칸이 함께 쓰는 모집 수로 맞춘다.
  for (const [index, row] of review.rows.entries()) {
    const cell = row.cells.find(item => item.field === 'count')!, value = written(cell, `cell:housingTypes[${index}].count`)
    if (!differs(cell, value)) continue
    const typeCells = cellsOf(sheet, row.key).map(item => item.key)
    if (!typeCells.length) throw new ReviewError(`${rowName(review, index)}: 공급대상이 없어 모집수를 저장할 수 없습니다.`, `cell:housingTypes[${index}].count`)
    const whole = wholeTypeQuota(sheet, row.key)
    if (value === null) { if (whole) sheet = setMembersCount(sheet, typeCells, 'WINNER', null); continue }
    const rest = (value as number) - ((housingTypeCount(sheet, row.key) ?? 0) - (whole?.count ?? 0))
    if (rest < 0) throw new ReviewError(`${rowName(review, index)}: 모집수 ${String(value)}세대가 대상별 모집 합보다 작습니다. 대상별 모집이나 모집수를 고치세요.`, `cell:housingTypes[${index}].count`)
    if (whole || rest > 0) sheet = setMembersCount(sheet, typeCells, 'WINNER', rest)
  }
  // 8. 어느 칸도 쓰지 않는 공급대상은 뺀다.
  const used = new Set(sheet.cells.map(cell => cell.target))
  return { ...sheet, targets: sheet.targets.filter(target => used.has(target.key)) }
}
/** 미확정 공고를 보정과 함께 공개할 때 정하는 공급·모집 유형. 미확정 공고는 보정을 적용하면 곧바로 공개한다. */
export type ReleaseChoice = { rentalType: string; recruitmentType: string }
export function assembleReview(review: Review, workspace: JsonWorkspace, evidence: { reason: string; sourceUrl: string; sourcePage: string | null }, release?: ReleaseChoice): JsonUpdateInput {
  const sheet = assembledSheet(review, workspace)
  const housingTypeValues = sheet.housingTypes.flatMap((type, index) => {
    if (type.housingTypeId === null && !type.newHousingType) return []
    const value = housingTypeValue(review, index, type.key)
    return value ? [value] : []
  })
  const announcement: JsonObject = Object.fromEntries(['name', 'rentalType', 'recruitmentType', 'agencyCode', ...metadataFields.map(([field]) => field)].map(field => [field, workspace.detail.data[field] ?? null]))
  applyCells(announcement, review.metadata, metadataFields)
  const [need] = fieldNeeds(review, release ? workspace : undefined, release)
  if (need) throw new ReviewError(need[1], need[0].startsWith('release:') ? need[0] : `cell:${need[0]}`)
  const receptionPlaces = assembledReceptionPlaces(review.reception, workspace)
  const document: ExtractedAnnouncement = { schemaVersion: 'admin-announcement-import/v1', source: { originalUrl: workspace.detail.data.originalUrl, sourceDocumentId: workspace.detail.sourceIdentifier, extractedAt: new Date().toISOString() },
    announcement, receptionPlaces, schedules: [], attachments: [], unresolvedFields: [] }
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
  const fields = [...review.metadata.filter(cell => cell.selected && !cell.locked).map(cell => cell.field),
    ...JSON.stringify(receptionPlaces) === JSON.stringify(currentReceptionPlaces(workspace).map(place => receptionPlaceShape(place))) ? [] : ['receptionPlaces']]
  for (const field of ['rentalType', 'recruitmentType'] as const) {
    if (release?.[field]) { announcement[field] = release[field]; fields.push(field) }
  }
  return { snapshotToken: workspace.snapshotToken, document, fields, schedules, attachments: [],
    correction: { operationId: crypto.randomUUID(), snapshotToken: workspace.supply.snapshotToken, sheet: toSheetInput(sheet), ...evidence }, housingTypeValues, ...release ? { publish: true } : {} }
}

/**
 * 서버 요청값 검증(400)이 돌려준 공급표 경로(correction.sheet.housingTypes[3].name, correction.sheet.cells[5].target)를 주택형 이름으로 바꿔 알리고
 * 그 주택형으로 옮긴다. 요청의 순서는 화면 순서와 다를 수 있어 키로 찾는다. 찾지 못한 경로는 그대로 보인다.
 */
export function serverFieldsError(review: Review, input: Pick<JsonUpdateInput, 'correction'>, fields: Record<string, string>) {
  let reviewId: string | null = null
  const sheet = input.correction.sheet
  const lines = Object.entries(fields).map(([field, reason]) => {
    const match = /(?:^|\.)correction\.sheet\.(housingTypes|cells|targets|quotas|leaseTerms)\[(\d+)\]\.?(.*)$/.exec(field)
    if (!match) return `${field}: ${reason}`
    const [, list, position, rest] = match, at = Number(position)
    if (list === 'targets') return `공급대상 '${sheet.targets[at]?.label || '이름 없음'}': ${reason}`
    const cellKey = list === 'cells' ? sheet.cells[at]?.key : list === 'quotas' ? sheet.quotas[at]?.members[0] : list === 'leaseTerms' ? sheet.leaseTerms[at]?.members[0] : undefined
    const typeKey = list === 'housingTypes' ? sheet.housingTypes[at]?.key : sheet.cells.find(cell => cell.key === cellKey)?.housingType
    const index = review.rows.findIndex(row => row.key === typeKey)
    if (index < 0) return `${field}: ${reason}`
    reviewId ??= `row:${index}`
    const what = list === 'housingTypes' ? rest ? ` ${typeFields.find(([name]) => name === rest)?.[1] ?? rest}` : '' : list === 'quotas' ? ' 모집·예비 수' : list === 'leaseTerms' ? ' 임대조건' : ' 공급대상 칸'
    return `${rowName(review, index)}${what}: ${reason}`
  })
  return { message: `서버가 요청값을 받지 않았습니다. ${lines.join(' / ')}`, reviewId }
}

export function remapSchedule(review: Review, index: number, ids: number[], workspace: JsonWorkspace): ScheduleReview {
  const old = review.schedules[index]
  const values = cells(review.extraction, `schedules[${index}]`, scheduleFields, review.extraction.schedules[index], workspace.events.find(event => event.id === ids[0])?.data)
  return { ...old, proposal: { ...old.proposal, existingIds: ids }, cells: values.map(cell => {
    const previous = old.cells.find(item => item.path === cell.path)
    return preserveCellChoice(cell, previous)
  }) }
}

/**
 * 최신 공고 값으로 검토를 다시 만들고 관리자가 고른 것을 이어받는다. 칸 선택은 같은 경로의 칸에, 단지 정하기에서 고른 단지는
 * 그사이 단지가 정해지지 않은 묶음에 다시 넣는다. 주택형 가격 선택은 연결 주택타입이 그대로일 때만 이어받는다.
 */
export function rebaseReview(previous: Review, workspace: JsonWorkspace, housing: HousingCandidates): Review {
  let next = createReview(previous.extraction, workspace, housing)
  for (const [key, complex] of Object.entries(previous.complexes)) {
    if (complex.chosen && complex.housingComplexId !== null && next.complexes[key]?.housingComplexId === null) next = decideComplex(next, key, complex, housing[complex.housingComplexId] ?? previous.rows.find((_, index) => complexKey(previous.extraction.sheet.housingTypes[index].complexName) === key)?.candidates ?? [])
  }
  const oldCells = new Map(allCells(previous).map(cell => [cell.path, cell]))
  const retain = (cell: Cell): Cell => preserveCellChoice(cell, oldCells.get(cell.path))
  next.metadata = next.metadata.map(retain)
  // 빼기 선택은 같은 접수처에만 이어받는다. PDF 접수처는 PDF 순번, PDF에 없는 지금 접수처는 그때 본 이름·방식이 같아야 한다(순번은 그사이 바뀔 수 있다).
  const identity = (entry: ReceptionReview) => entry.extracted !== null ? `pdf:${entry.extracted}`
    : `current:${['name', 'method'].map(field => String(entry.cells.find(cell => cell.field === field)?.before ?? '')).join('|')}`
  next.reception = next.reception.map(entry => ({ ...entry, cells: entry.cells.map(retain),
    include: previous.reception.find(old => identity(old) === identity(entry))?.include ?? entry.include }))
  next.rows = next.rows.map((row, index) => {
    const old = previous.rows[index]
    const kept: RowReview = { ...row, cells: row.cells.map(retain), targets: row.targets.map(target => ({ ...target, cells: target.cells.map(retain) })) }
    return old && linkedHousingTypeId(kept) === linkedHousingTypeId(old) ? { ...kept, priceTarget: old.priceTarget } : kept
  })
  next.labels = next.labels.map(entry => ({ ...entry, cells: entry.cells.map(retain) }))
  next.shared = next.shared.map(entry => ({ ...entry, cell: retain(entry.cell) }))
  next.schedules = next.schedules.map((schedule, index) => {
    const old = previous.schedules[index]
    schedule = remapSchedule(next, index, old.proposal.existingIds.filter(id => workspace.events.some(event => event.id === id)), workspace)
    return { ...schedule, include: old.include, reviewed: old.reviewed, cells: schedule.cells.map(retain) }
  })
  return next
}

type Status = { label: string; tone: 'error' | 'warning' | 'pending' | 'muted' | 'done' }
/** 주택형 상태. 확인 필요 → 주택타입 미연결 → 준비됨 순서로 하나만 보인다. */
export function rowStatus(review: Review, index: number, pendingCells: boolean): Status {
  if (needsComplex(review, index) || pendingCells) return { label: '확인 필요', tone: 'pending' }
  // 전용면적이 있으면 저장할 때 같은 이름 주택타입에 잇거나 새로 만들므로, 연결할 주택타입도 면적도 없을 때만 알린다.
  const area = savedOf(review.rows[index], 'exclusiveArea')
  if (linkedHousingTypeId(review.rows[index]) === null && !expectedHousingType(review, index) && !(typeof area === 'number' && area > 0)) return { label: '주택타입 미연결', tone: 'warning' }
  return { label: '준비됨', tone: 'done' }
}
