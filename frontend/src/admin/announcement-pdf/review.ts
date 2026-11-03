import type { ExtractedAnnouncement, HousingTypeValue, JsonObject, JsonUpdateInput, JsonWorkspace } from '../announcement-json/api'
import { blankRow, blankTarget, nextDisplayOrder, type CorrectionRow, type CorrectionTarget, type LinkedHousingType, type NewSupplyGroup } from '../supply/supplyCorrectionApi'
import { evidenceFor, noteFor, type Evidence, type Extraction, type Subtype } from './extraction'
import { foldRows } from './subtypes'
import { attachableLhRows, defaultTargetDestinations, foldSameSourceRows, lhRowLabel, lhSourceLabels, missingHousingType, originalDate, pickSubtypes, proposePlacements, proposeRows, proposeSchedules, sharedFoldGroups, splitSameSourceRows, splittableLhRows, type HousingCandidates, type RowProposal, type ScheduleProposal } from './matching'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'

type Kind = 'text' | 'count' | 'area' | 'money' | 'date' | 'datetime' | 'month' | 'method' | 'scheduleType' | 'url' | 'housingType'
export type CellState = 'unresolved' | 'notApplicable' | 'invalid' | 'confirm' | 'same' | 'changed' | 'new'
export type ValueMode = 'keep' | 'extracted' | 'manual'
export type Cell = { path: string; field: string; label: string; kind: Kind; before: unknown; value: unknown; selected: boolean; edited: boolean; reviewed: boolean; mode: ValueMode; input?: string; manualInput?: string; extracted: { value: unknown; state: CellState; note: string }; locked: boolean; note: string; state: CellState; evidence: Evidence | null }
type FieldSpec = [field: string, label: string, kind: Kind, target?: string]
export const rowFields: FieldSpec[] = [['sourceHousingTypeName', '이 공고의 주택형 표기', 'text', 'typeName'], ['exclusiveArea', '전용면적 (㎡)', 'area'], ['supplyArea', '공급면적 (㎡)', 'area'], ['totalSupplyHouseholdCount', '모집수', 'count', 'householdCount'], ['expectedMoveInMonth', '입주 예정월', 'month', 'moveInMonth'], ['housingTypeId', '연결 주택형', 'housingType']]
export const targetFields: FieldSpec[] = [['target', '공급 대상', 'text'], ['supplyRank', '공급 순위', 'text'], ['supplyHouseholdCount', '대상별 모집수', 'count', 'householdCount'], ['reserveCount', '예비자 수', 'count'], ['rentalDeposit', '보증금 (원)', 'money', 'deposit'], ['monthlyRent', '월 임대료 (원)', 'money', 'rent'], ['convertedDeposit', '전환보증금 (원)', 'money'], ['applicationCondition', '신청 조건', 'text', 'condition']]
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
  /** 단지에 맞는 주택형이 없어 PDF 형명·면적으로 새 주택형을 만들기로 고른 경우 */
  newHousingType?: boolean }
export type ScheduleReview = { proposal: ScheduleProposal; include: boolean; reviewed: boolean; cells: Cell[] }
export type Review = { extraction: Extraction; rows: RowReview[]; schedules: ScheduleReview[]; metadata: Cell[]; reception: Cell[]
  /** LH 원천행에 맞추기 전 검토. 이미 맞춘 단지를 다시 고르면 여기서 다시 맞춘다. */
  unaligned?: Review
  /** 지금까지 LH 원천행에 맞춘 단지명과 고른 단지. 다시 맞출 때 다른 단지명의 맞춤을 이 순서로 다시 적용한다. */
  alignments?: { complexName: string; complex: { id: number; name: string }; candidates: MatchingHousingType[] }[] }
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

function normalized(kind: Kind, value: unknown): unknown {
  if (value === null || value === undefined || value === '') return null
  if (kind === 'money' && typeof value === 'number' && Number.isSafeInteger(value)) return String(value)
  return typeof value === 'string' ? value.trim() : value
}
export function validCellValue(kind: Kind, value: unknown): boolean {
  if (kind === 'housingType' && value === null) return true
  if (value === null || value === undefined || value === '') return false
  if (kind === 'count') return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 2147483647
  if (kind === 'housingType') return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 && value <= 2147483647
  if (kind === 'area') return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 999999.9999 && Math.abs(value * 10000 - Math.round(value * 10000)) < .00001
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
  if (kind === 'url') { try { return ['http:', 'https:'].includes(new URL(value).protocol) && !/\s/.test(value) } catch { return false } }
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
    let state: Cell['state'] = unused || areaOnly ? 'notApplicable' : missing || value === null ? 'unresolved' : !validCellValue(kind, value) ? 'invalid' : sameValue ? 'same' : previous === null ? 'new' : 'changed'
    const moreDetailed = typeof previous === 'string' && typeof value === 'string' && previous.length > value.length && previous.includes(value) && prefix === 'receptionPlace'
    const foreign = kind === 'text' ? foreignCharacters(value) : ''
    // 모델이 PDF 공급 표 대신 함께 보낸 LH 주택형 이름(24 → 24A)을 쓰기도 한다. 근거 인용에 없는 표기는 사람이 대조한다.
    // 하위 항목(임대조건 행)에서 빌려 온 근거에는 주택형이 원래 없으므로, 공급행 자기 근거나 이 칸의 근거일 때만 본다.
    const unquoted = field === 'sourceHousingTypeName' && typeof value === 'string' && evidence !== null && [prefix, path].includes(evidence.path) && !compact(evidence.quote).includes(typeCore(value))
    if (['changed', 'new'].includes(state) && (!evidence || moreDetailed || foreign || unquoted || informational)) state = 'confirm'
    const note = (informational ? `참고: ${informational.reason}` : undefined) ?? unused?.reason ?? (areaOnly ? '면적만 적힌 값이라 주택형 표기로 쓰지 않습니다.' : undefined)
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
  const value = input === '' ? null : cell.kind === 'area' || cell.kind === 'count' || cell.kind === 'housingType' ? Number(input) : input
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
    const created = proposal.complexId !== null && !pick.options.length && subtype.exclusiveArea !== null
    return { ...subtype, choice: pick.housingTypeId ?? (created ? 'new' : null), options: pick.options.map(type => type.id) }
  })
}
/** 대표 주택형이 될 세부 타입 번호. 저장 위치의 지금 주택형을 고른 세부 타입이 있으면 그 타입, 없으면 연결하는 첫 세부 타입이다. 없으면 -1. */
function representativeOf(subtypes: SubtypeReview[], current: unknown) {
  const kept = typeof current === 'number' ? subtypes.findIndex(subtype => subtype.choice === current) : -1
  return kept >= 0 ? kept : subtypes.findIndex(subtype => subtype.choice !== 'none')
}
export function representativeSubtype(row: RowReview) {
  return row.subtypes ? representativeOf(row.subtypes, row.cells.find(cell => cell.field === 'housingTypeId')?.before) : -1
}
/** 대표 주택형을 PDF 형명·면적으로 새로 만드는지. 세부 타입 묶음 행은 대표 세부 타입의 선택을 따른다. */
export function newRepresentative(row: RowReview) {
  return row.subtypes ? row.subtypes[representativeSubtype(row)]?.choice === 'new' : Boolean(row.newHousingType)
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
const sharedSubtypeNote = '공고문이 이 행의 모집수를 다른 공급행과 합쳐 적었습니다. 세부 타입 묶음 행은 공통 모집수로 묶을 수 없으니 이 공급행의 모집수를 직접 입력하세요.'
export function createRowReview(extraction: Extraction, index: number, proposal: RowProposal, workspace: JsonWorkspace): RowReview {
  const source = extraction.supplyRows[index], current = workspace.supply.rows.find(row => row.key === proposal.destination)
  const targets = defaultTargetDestinations({ targets: source.targets }, current)
  const subtypes = source.subtypes.length ? subtypeReviews(extraction, index, proposal) : undefined
  const choice = subtypes?.[representativeOf(subtypes, current?.housingTypeId)]?.choice
  const housingTypeId = subtypes ? typeof choice === 'number' ? choice : null : proposal.housingTypeId
  const rowCells = cells(extraction, `supplyRows[${index}]`, rowFields, { ...source.values, housingTypeId }, current as unknown as JsonObject)
  const count = rowCells.find(cell => cell.field === 'totalSupplyHouseholdCount')!
  // 세부 타입 묶음 행은 공통 모집 묶음에 넣을 수 없어, 공고문이 다른 LH 행과 합쳐 적은 모집수 대신 이 행의 모집수를 직접 받는다.
  if (subtypes && proposal.sharedIndex !== null) Object.assign(count, { state: 'confirm', extracted: { ...count.extracted, state: 'confirm' }, note: sharedSubtypeNote })
  else if (proposal.sharedIndex !== null || current?.groupId) {
    count.locked = true; count.selected = false; count.mode = 'keep'; count.note = '공통 모집수는 묶음에 한 번만 기록합니다.'
  }
  return syncRepresentative({ proposal, include: true, reviewed: false, key: current?.key ?? crypto.randomUUID(), cells: rowCells, subtypes,
    targets: source.targets.map((target, at) => ({ destination: targets[at], cells: cells(extraction, `supplyRows[${index}].targets[${at}]`, targetFields, target,
      current?.targets.find(item => (item.id ? String(item.id) : item.key) === targets[at]) as unknown as JsonObject) })) })
}
/**
 * fold가 true면 같은 기존 LH 행을 가리키는 공고문 행들을 세부 타입 묶음 행 하나로 접는다. 이미 접은 검토를 다시 만들 때는 접지 않는다.
 * 같은 주택형의 LH 행이 공급대상 묶음마다 있으면 공고문 행의 대상을 묶음별로 나눈다(splitSameSourceRows).
 * LH 표기로 고를 수 없는 행은 저장할 위치를 한 번 제안한 뒤, 기존 행 하나와 새 행만 묶은 공통 모집수(sharedFoldGroups)를 접고 다시 제안한다.
 */
export function createReview(source: Extraction, workspace: JsonWorkspace, housing: HousingCandidates, fold = true): Review {
  const labels = lhSourceLabels(workspace.supply)
  const propose = (value: Extraction) => proposeRows(value, workspace.supply.rows, housing, workspace.supply.announcementComplexes, labels)
  const split = fold ? splitSameSourceRows(foldSameSourceRows(source, workspace.supply.rows, labels), workspace.supply.rows, labels) : { extraction: source, notes: new Map<number, string>() }
  let extraction = split.extraction, proposals = propose(extraction), notes = split.notes
  const groups = fold ? sharedFoldGroups(extraction, proposals, workspace.supply.rows) : []
  if (groups.length) {
    const folded = foldRows(extraction, groups)
    extraction = folded.extraction; proposals = propose(extraction)
    notes = new Map([...notes].map(([index, note]) => [folded.position[index], note]))
    for (const group of groups) {
      const proposal = proposals[folded.position[group.indexes[0]]]
      // 공통 모집수만 보고 LH 한 행으로 판단해 접었으므로 저장할 위치를 확인하게 한다.
      Object.assign(proposal, { needsConfirmation: true, reason: `공고문 ${group.typeName.split('/').join('·')}가 공통 모집수 ${group.count ?? '미확인'}세대를 함께 적어 공급행 하나의 세부 타입으로 접었습니다. · ${proposal.reason}` })
    }
  }
  // 공급대상 묶음으로 나눈 행은 대상 몫과 모집수를 확인하게 한다.
  notes.forEach((note, index) => Object.assign(proposals[index], { needsConfirmation: true, reason: `${note} · ${proposals[index].reason}` }))
  if (fold) proposals = proposePlacements(extraction, proposals, workspace.supply, housing)
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
  if (reviewId.startsWith('cell:announcement') || reviewId.startsWith('cell:receptionPlace')) return 'reception'
  return 'supply'
}
/** 확인 항목 ID(row:2, cell:supplyRows[2].…, target:2:0, subtype:2:1, price:2, placement:2)가 가리키는 공급행 번호. 공급행 밖이면 null */
export function rowOfReviewId(reviewId: string): number | null {
  const match = /^(?:(?:row|target|subtype|price|placement):(\d+)|cell:supplyRows\[(\d+)\])/.exec(reviewId)
  return match ? Number(match[1] ?? match[2]) : null
}
export type ReviewFilter = 'all' | 'pending' | 'changed' | 'new'
export type PendingItem = { id: string; section: ReviewSection }
/** 사람이 아직 판단하지 않은 확인 필요·형식 오류 값. 직접 입력값은 형식이 맞을 때까지 남는다. */
export function pendingCell(cell: Cell) {
  return !cell.locked && (cell.mode === 'manual' ? cell.state === 'invalid' : !cell.reviewed && ['confirm', 'invalid'].includes(cell.extracted.state))
}
/** 필터는 추출 당시 상태로 고정해 값을 고르는 동안 항목이 사라지지 않게 한다. */
export function cellMatches(cell: Cell, filter: ReviewFilter) {
  return filter === 'all' || (filter === 'pending' ? pendingCell(cell) : cell.extracted.state === filter)
}
export function pendingRow(row: RowReview) { return row.include && (!row.proposal.destination || row.proposal.needsConfirmation && !row.reviewed) }
export function pendingPrice(row: RowReview) { return row.include && housingTypePrice(row, linkedHousingType(row)).confirm }
export function pendingTarget(target: TargetReview) { return !target.destination || target.destination !== 'exclude' && target.cells.some(pendingCell) }
export function pendingSchedule(schedule: ScheduleReview) { return schedule.proposal.needsConfirmation && !schedule.reviewed }
/** 연결 주택형을 아직 정하지 못한 세부 타입 번호 */
export function pendingSubtypes(row: RowReview) { return row.include ? (row.subtypes ?? []).flatMap((subtype, at) => subtype.choice === null ? [at] : []) : [] }
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
/** 화면에 보이는 순서(공급정보 단지 묶음 → 일정 → 접수처·기본정보)로 남은 확인 항목을 돌려준다. */
/** savesHousingTypes가 false면(PDF로 공급정보 편집표만 채울 때) 주택형 값을 저장하지 않으므로 주택형 가격 확인을 넣지 않는다. */
export function pendingItems(review: Review, savesHousingTypes = true, workspace?: JsonWorkspace): PendingItem[] {
  const items: PendingItem[] = [], problems = workspace ? placementProblems(review, workspace) : new Map<number, PlacementProblem>()
  complexGroups(review).flatMap(group => group.indexes).forEach(index => {
    const row = review.rows[index]
    if (!row?.include) return
    if (pendingRow(row) || problems.get(index)?.level === 'error') items.push({ id: `row:${index}`, section: 'supply' })
    pendingSubtypes(row).forEach(at => items.push({ id: `subtype:${index}:${at}`, section: 'supply' }))
    row.cells.filter(pendingCell).forEach(cell => items.push({ id: `cell:${cell.path}`, section: 'supply' }))
    row.targets.forEach((target, at) => { if (pendingTarget(target)) items.push({ id: `target:${index}:${at}`, section: 'supply' }) })
    if (savesHousingTypes && pendingPrice(row)) items.push({ id: `price:${index}`, section: 'supply' })
  })
  review.schedules.forEach((schedule, index) => {
    if (pendingSchedule(schedule)) items.push({ id: `schedule:${index}`, section: 'schedule' })
    if (schedule.include) schedule.cells.filter(pendingCell).forEach(cell => items.push({ id: `cell:${cell.path}`, section: 'schedule' }))
  })
  for (const cell of [...review.reception, ...review.metadata].filter(pendingCell)) items.push({ id: `cell:${cell.path}`, section: 'reception' })
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
export function destinationLabel(row: CorrectionRow) {
  const count = row.householdCount != null ? `모집 ${row.householdCount}세대` : row.groupId !== null ? '공통 모집' : '모집수 미확인'
  const area = row.exclusiveArea != null ? `전용 ${row.exclusiveArea}㎡` : '면적 미확인'
  return `${row.housingComplexName ?? row.complexName} · ${row.typeName} · ${count} · ${area} (#${String(row.id)}) 수정`
}

export function applicableCells(review: Review) {
  return [...review.metadata, ...review.reception,
    ...review.rows.filter(row => row.include).flatMap(row => [...row.cells, ...row.targets.filter(target => target.destination !== 'exclude').flatMap(target => target.cells)]),
    ...review.schedules.filter(schedule => schedule.include).flatMap(schedule => schedule.cells)]
}
/**
 * 공통 모집 묶음을 저장할 수 있는지. 기존 공급행이 하나 있고 새 주택형을 만드는 행이 없어야 한다.
 * 묶음 행은 함께 모집하는 주택형을 가질 수 없으므로 세부 타입 묶음 행이 들면 저장하지 않는다.
 */
export function sharedGroupStorable(review: Review, groupIndex: number) {
  const members = review.rows.filter(row => row.include && row.proposal.sharedIndex === groupIndex)
  return members.filter(row => row.proposal.destination !== 'new').length === 1 && !members.some(row => row.newHousingType || row.subtypes)
}
/** 추출 행의 저장 방식. 저장 위치가 비어 있으면 null이다. */
export type PlacementMode = 'UPDATE' | 'SPLIT' | 'ATTACH' | 'ADD'
export function placementMode(proposal: RowProposal): PlacementMode | null {
  if (proposal.saveMode) return proposal.saveMode
  return !proposal.destination ? null : proposal.destination === 'new' ? 'ADD' : 'UPDATE'
}
export const placementLabels: Record<PlacementMode, string> = { UPDATE: '기존 행 수정', SPLIT: '통합행 분리', ATTACH: 'LH 행에 연결', ADD: '별도 추가' }
/** focus는 문제를 고칠 항목의 화면 위치(data-review-id)다. 있으면 화면이 그 항목으로 이동하는 해결 동작을 보인다. */
export type PlacementProblem = { level: 'error' | 'warning'; message: string; kind: 'duplicate' | 'split' | 'attach' | 'unattached'; focus?: string }
/**
 * 저장 방식 검사. 같은 기존 행을 둘 이상 수정하면 중복 연결 오류, 분리 묶음 행이 하나뿐이거나 나눌 수 없는 LH 행이면 오류,
 * 첫 분리행(통합행)이 통합 모집수를 그대로 둔 채 다른 분리행에 모집수가 있으면 두 번 집계되므로 오류(서버도 거절한다),
 * 그 밖에 분리 합계가 LH 이번 공급 수와 다르거나 남는 LH 행이 있는데 별도 추가하면 경고다. 저장(assembleReview)과 화면이 같은 결과를 쓴다.
 */
export function placementProblems(review: Review, workspace: JsonWorkspace): Map<number, PlacementProblem> {
  const problems = new Map<number, PlacementProblem>(), lhRows = workspace.supply.lhSupplyRows ?? []
  const splittable = new Set(splittableLhRows(workspace.supply).map(lh => lh.rowIdentifier))
  const included = review.rows.flatMap((row, index) => row.include ? [index] : [])
  const updates = new Map<string, number[]>()
  for (const index of included) {
    const proposal = review.rows[index].proposal, mode = placementMode(proposal)
    if (mode === 'UPDATE' || mode === 'SPLIT' && proposal.destination !== 'new') updates.set(proposal.destination, [...updates.get(proposal.destination) ?? [], index])
  }
  for (const [key, indexes] of updates) {
    if (indexes.length < 2) continue
    const row = workspace.supply.rows.find(item => item.key === key)
    const canSplit = lhRows.some(lh => lh.attachedRowKey === key && splittable.has(lh.rowIdentifier))
    for (const index of indexes) problems.set(index, { level: 'error', kind: 'duplicate', message: `중복 연결: 공급행 ${indexes.filter(other => other !== index).map(other => other + 1).join('·')}도 같은 기존 행(${row ? `${row.typeName} #${String(row.id)}` : key})을 수정합니다.${canSplit ? ' 이 행은 LH 통합행이라 통합행 분리로 나눌 수 있습니다.' : ' 한쪽의 저장 방식을 바꾸세요.'}` })
  }
  const groups = new Map<string, number[]>()
  for (const index of included) {
    const proposal = review.rows[index].proposal
    if (proposal.saveMode === 'SPLIT' && proposal.lhRow) groups.set(proposal.lhRow, [...groups.get(proposal.lhRow) ?? [], index])
  }
  for (const [identifier, indexes] of groups) {
    const lh = lhRows.find(item => item.rowIdentifier === identifier)
    const set = (problem: PlacementProblem) => indexes.forEach(index => { if (!problems.has(index)) problems.set(index, problem) })
    if (!lh || !splittable.has(identifier)) { set({ level: 'error', kind: 'split', message: '이 LH 행은 지금 나눌 수 없습니다(통합행이 없거나 이미 나눴습니다). 저장 방식을 다시 고르세요.' }); continue }
    if (indexes.length < 2) { set({ level: 'error', kind: 'split', message: `통합행 분리에는 같은 LH 행(${lhRowLabel(lh)})으로 나눌 PDF 행이 둘 이상 필요합니다.` }); continue }
    const merged = workspace.supply.rows.find(row => row.key === lh.attachedRowKey)
    const head = indexes.find(index => review.rows[index].proposal.destination === lh.attachedRowKey)
    if (merged?.householdCount != null && head !== undefined && assembledCount(review.rows[head]) === merged.householdCount
      && indexes.some(index => index !== head && assembledCount(review.rows[index]) !== null)) {
      const count = review.rows[head].cells.find(cell => cell.field === 'totalSupplyHouseholdCount')
      set({ level: 'error', kind: 'split', focus: count ? `cell:${count.path}` : `row:${head}`,
        message: `첫 분리행(공급행 ${head + 1}, 통합행 #${String(merged.id)})의 모집수가 통합 모집수 ${merged.householdCount}세대 그대로라 다른 분리행 모집수와 두 번 집계됩니다. 첫 분리행의 모집수를 공고문의 그 주택형 모집수로 바꾸세요.` })
      continue
    }
    const counts = indexes.map(index => assembledCount(review.rows[index]))
    const sum = counts.every(count => count !== null) ? (counts as number[]).reduce((total, count) => total + count, 0) : null
    if (sum !== lh.householdCount) set({ level: 'warning', kind: 'split', message: `분리 합계 ${sum ?? '미확인'}세대가 LH 이번 공급 ${lh.householdCount ?? '미확인'}세대와 다릅니다. 공고문과 대조하세요.` })
  }
  const attaches = new Map<string, number[]>()
  for (const index of included) {
    const proposal = review.rows[index].proposal
    if (proposal.saveMode === 'ATTACH' && proposal.lhRow) attaches.set(proposal.lhRow, [...attaches.get(proposal.lhRow) ?? [], index])
  }
  const attachable = new Set(attachableLhRows(workspace.supply).map(lh => lh.rowIdentifier))
  for (const [identifier, indexes] of attaches) {
    const message = !attachable.has(identifier) ? '이 LH 행에는 이미 공급행이 있어 연결할 수 없습니다.' : indexes.length > 1 ? `LH 행 하나에는 한 행만 연결합니다. 공급행 ${indexes.map(index => index + 1).join('·')}이 같은 LH 행을 골랐습니다.` : ''
    if (message) indexes.forEach(index => { if (!problems.has(index)) problems.set(index, { level: 'error', kind: 'attach', message }) })
  }
  // 남은 LH 행 경고는 서버 미리보기 경고와 같이 LH 원천 소유 공고의 unattachedLhSupplyRows만 본다. 공고문 소유면 정제가 공급행을 만들지 않는다.
  const remaining = (workspace.supply.unattachedLhSupplyRows ?? []).map(lh => lh.rowIdentifier).filter(identifier => !attaches.has(identifier))
  if (remaining.length) for (const index of included) {
    if (placementMode(review.rows[index].proposal) === 'ADD' && !problems.has(index)) problems.set(index, { level: 'warning', kind: 'unattached', message: `공급행이 없는 LH 행 ${remaining.length}개가 남아 있습니다. 이 행이 그 LH 행이면 LH 행에 연결을 고르세요. 별도 추가로 저장하면 정제가 그 LH 행을 공급행으로 만들지 않습니다.` })
  }
  return problems
}
/** 저장하면 이 행에 들어갈 모집수. 반영을 고른 칸은 그 값, 아니면 저장 위치의 지금 값이다. */
export function assembledCount(row: RowReview): number | null {
  const cell = row.cells.find(item => item.field === 'totalSupplyHouseholdCount')
  const value = cell?.selected && !cell.locked ? cell.value : cell?.before
  return typeof value === 'number' ? value : null
}
export function assembleReview(review: Review, workspace: JsonWorkspace, evidence: { reason: string; sourceUrl: string; sourcePage: string | null }): JsonUpdateInput {
  const rows = structuredClone(workspace.supply.rows), taken = new Map<string, number>(), newGroups: NewSupplyGroup[] = []
  const housingTypeValues: HousingTypeValue[] = []
  for (const [index, entry] of review.rows.entries()) {
    if (!entry.include) continue
    if (!entry.proposal.destination) throw new ReviewError(`공급행 ${index + 1}의 저장 방식을 고르세요. 기존 행 수정·통합행 분리·LH 행에 연결·별도 추가 중 하나입니다.`, `row:${index}`)
    const problem = placementProblems(review, workspace).get(index)
    if (problem?.level === 'error') throw new ReviewError(`공급행 ${index + 1}: ${problem.message}`, problem.focus ?? `row:${index}`)
    const anchor = rows.find(row => row.key === entry.proposal.anchorKey)
    const existing = entry.proposal.destination === 'new' ? undefined : rows.find(row => row.key === entry.proposal.destination)
    if ((!anchor && !entry.proposal.newComplex) || entry.proposal.destination !== 'new' && !existing) throw new ReviewError(`공급행 ${index + 1}의 기존 단지·원천행을 선택하세요.`, `row:${index}`)
    if (existing && taken.has(existing.key)) throw new ReviewError(`공급행 ${taken.get(existing.key)! + 1}과 공급행 ${index + 1}이 같은 기존 공급정보(${existing.complexName} · ${existing.typeName})를 수정합니다(중복 연결). LH 통합행이면 통합행 분리로, 아니면 한쪽의 저장 방식을 바꾸세요.`, `row:${index}`)
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
    applyCells(row as unknown as JsonObject, entry.cells, rowFields)
    if (entry.proposal.saveMode) { row.saveMode = entry.proposal.saveMode; row.lhSourceRowIdentifier = entry.proposal.lhRow }
    const subtypes = entry.subtypes && subtypeLinks(entry, index, row)
    // 공고에 주택형 표기가 없으면 새 행은 연결한 단지 주택형 이름으로 표시한다.
    if (!row.typeName.trim()) row.typeName = entry.proposal.candidates.find(type => type.id === row.housingTypeId)?.name ?? ''
    if (subtypes) {
      if (!existing) row.areaOverride = false
    } else if (entry.newHousingType) {
      const source = review.extraction.supplyRows[index]
      const created = missingHousingType(row.housingComplexId, entry.proposal.candidates, row.typeName || source.typeName || '', source.values.exclusiveArea, source.values.supplyArea)
      if (!created || !created.name) throw new ReviewError(`공급행 ${index + 1}: PDF 형명과 전용면적이 있어야 새 주택형을 추가할 수 있습니다.`, `row:${index}`)
      row.housingTypeId = null; row.newHousingType = created
    } else if (entry.cells.some(cell => cell.field === 'housingTypeId' && cell.selected && cell.value !== null) && !entry.proposal.candidates.some(type => type.id === row.housingTypeId)) throw new ReviewError('연결 주택형이 선택한 단지에 없습니다. 주택형을 다시 선택하세요.', `row:${index}`)
    // 세부 타입을 함께 연결하지 않는 행은 대표가 그대로면 저장된 연결을 유지하고 바뀌면 대표만 남기도록 목록을 보내지 않는다.
    if (!subtypes) row.linkedHousingTypes = undefined
    if (entry.cells.some(cell => ['exclusiveArea', 'supplyArea'].includes(cell.field) && cell.selected)) row.areaOverride = true
    if (entry.proposal.sharedIndex !== null && subtypes) {
      const count = entry.cells.find(cell => cell.field === 'totalSupplyHouseholdCount')!
      if (count.mode !== 'manual' || typeof count.value !== 'number') throw new ReviewError(`공급행 ${index + 1}: ${sharedSubtypeNote}`, `cell:${count.path}`)
    } else if (entry.proposal.sharedIndex !== null) row.householdCount = null
    const selectedTargets = new Set<string>()
    for (const target of entry.targets) {
      if (target.destination === 'exclude') continue
      if (!target.destination) throw new ReviewError(`${row.typeName}: 수정할 기존 임대조건을 선택하거나 새 임대조건으로 추가하세요.`, `row:${index}`)
      if (target.destination !== 'new' && selectedTargets.has(target.destination)) throw new ReviewError('같은 기존 공급대상을 두 번 갱신할 수 없습니다.', `row:${index}`)
      selectedTargets.add(target.destination)
      const stored = row.targets.find(item => (item.id ? String(item.id) : item.key) === target.destination)
      if (target.destination !== 'new' && !stored) throw new ReviewError('선택한 공급대상이 현재 행에 없습니다.', `row:${index}`)
      const next: CorrectionTarget = stored ?? blankTarget(row.targets.length)
      applyCells(next as unknown as JsonObject, target.cells, targetFields)
      if (!next.target) throw new ReviewError(`${row.typeName}: 새 공급대상의 이름을 확인하세요.`, `row:${index}`)
      if (!stored) row.targets.push(next)
    }
    if (!row.typeName) throw new ReviewError('새 공급행의 주택형명을 확인하세요.', `row:${index}`)
    if (!existing) rows.push(row)
    if (subtypes) housingTypeValues.push(...subtypes.flatMap(item => {
      const path = `supplyRows[${index}].subtypes[${item.at}]`, subtype = entry.subtypes![item.at]
      const value = housingTypeValue(entry, row.key, linkedHousingType(entry), subtypeTypeValues(entry, subtype, Boolean(noteFor(review.extraction.unresolved, `${path}.supplyArea`))), item.address)
      return !row.excluded && value ? [value] : []
    }))
    else if (!row.excluded && (row.housingTypeId !== null || row.newHousingType)) {
      const value = housingTypeValue(entry, row.key, linkedHousingType(entry), rowTypeValues(entry, review.extraction.supplyRows[index]))
      if (value) housingTypeValues.push(value)
    }
  }
  for (const [index, group] of review.extraction.sharedCounts.entries()) {
    const members = review.rows.filter(row => row.proposal.sharedIndex === index)
    if (members.every(row => !row.include)) continue
    if (members.some(row => !row.include)) throw new Error('공통 모집 묶음은 모든 행을 함께 적용하거나 함께 제외하세요.')
    const current = members.map(row => rows.find(item => item.key === row.key)!)
    if (current.some(row => !row)) throw new Error('공통 모집 묶음의 행 대응을 확인하세요.')
    if (current.every(row => row.groupId !== null && row.groupId === current[0].groupId)) {
      if (current[0].sharedHouseholdCount !== group.count) throw new Error('기존 공통 모집수가 PDF와 다릅니다. 공통 모집수는 PDF 보정에서 바꿀 수 없습니다.')
      continue
    }
    const original = current.filter(row => row.id !== null)
    // 공통 모집 묶음은 기존 원천행 하나를 기준으로만 만들 수 있다. 새 단지·새 주택형만으로 된 묶음은 저장할 곳이 없어 각 행의 모집수를 비워 둔다.
    if (!sharedGroupStorable(review, index)) continue
    if (original.length !== 1 || group.count === null || current.some(row => row.housingTypeId === null)) throw new Error('새 공통 모집 묶음은 기존 한 행과 서로 다른 주택형에 연결한 새 행으로 구성하세요.')
    newGroups.push({ sourceRowId: original[0].id!, rowKeys: current.map(row => row.key), sharedHouseholdCount: group.count })
  }
  const ordered = applyPdfOrder(review, rows)
  const announcement: JsonObject = Object.fromEntries(['name', 'rentalType', 'recruitmentType', 'agencyCode', ...metadataFields.map(([field]) => field)].map(field => [field, workspace.detail.data[field] ?? null]))
  applyCells(announcement, review.metadata, metadataFields)
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
  return { snapshotToken: workspace.snapshotToken, document, fields, schedules, attachments: [], correction: { operationId: crypto.randomUUID(), snapshotToken: workspace.supply.snapshotToken, rows: ordered, newGroups, ...evidence }, housingTypeValues }
}

/** 끝에 붙은 괄호 묶음(중첩 포함)만 뗀다. "26(고령(주거약자용))" → "26", "(구)36(청년)" → "(구)36" */
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

type TypeValueInput = { name: unknown; supplyArea: unknown; householdCount: unknown }
type TypeAddress = Pick<HousingTypeValue, 'linkedHousingTypeId' | 'linkedNewHousingTypeArea'>
/** 세부 타입이 없는 행의 주택형 값. 이름·공급면적은 관리자가 고른 값(현재값과 같은 값 포함)만 쓰고, 고르지 않은 값은 보내지 않는다. */
function rowTypeValues(entry: RowReview, source: Extraction['supplyRows'][number]): TypeValueInput {
  const accepted = (field: string, extracted: unknown) => {
    const cell = entry.cells.find(item => item.field === field)
    return cell?.selected && !cell.locked ? cell.value : acceptedCell(cell) ? extracted : null
  }
  return { name: accepted('sourceHousingTypeName', source.typeName), supplyArea: accepted('supplyArea', source.values.supplyArea), householdCount: source.values.constructedHouseholdCount }
}
/** 관리자가 이 칸의 값을 받아들였는지. 칸이 없거나, 반영을 골랐거나, 현재값과 같으면 받아들인 것이다. */
function acceptedCell(cell: Cell | undefined) { return !cell || cell.selected && !cell.locked || cell.state === 'same' }
/**
 * 세부 타입 묶음 행의 세부 타입 하나의 주택형 값. 이름은 관리자가 공급행의 주택형 표기를 받아들였을 때만 세부 타입 이름을 쓴다(rowTypeValues와 같은 기준).
 * 세부 타입 공급면적은 공급행 칸이 해당 없음이라, 공고문에서 읽지 못한 값만 뺀다. 세대수는 공고문의 건설호수다.
 */
function subtypeTypeValues(entry: RowReview, subtype: Subtype, supplyAreaUnresolved: boolean): TypeValueInput {
  return { name: acceptedCell(entry.cells.find(item => item.field === 'sourceHousingTypeName')) ? subtype.name : null,
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
    supplyArea: typeof area === 'number' && area >= 0 ? area : null,
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
      const created = missingHousingType(row.housingComplexId, entry.proposal.candidates, subtype.name, subtype.exclusiveArea, subtype.supplyArea)
      if (!created?.name) throw new ReviewError(`${label}: 세부 타입 ${subtype.name}은(는) 형명과 전용면적이 있고 단지에 같은 면적 주택형이 없어야 새 주택형으로 추가할 수 있습니다.`, `subtype:${index}:${at}`)
      return { newHousingType: created }
    }
    if (!entry.proposal.candidates.some(type => type.id === subtype.choice)) throw new ReviewError(`${label}: 세부 타입 ${subtype.name}의 연결 주택형이 선택한 단지에 없습니다.`, `subtype:${index}:${at}`)
    return { housingTypeId: subtype.choice }
  })
  const chosen = links.flatMap(link => link?.housingTypeId ? [link.housingTypeId] : [])
  if (new Set(chosen).size !== chosen.length) throw new ReviewError(`${label}: 세부 타입마다 다른 주택형을 연결하세요.`, `row:${index}`)
  const representative = representativeSubtype(entry), first = links[representative]
  if (!first) throw new ReviewError(`${label}: 세부 타입 하나 이상에 주택형을 연결하세요.`, `row:${index}`)
  row.housingTypeId = first.housingTypeId ?? null; row.newHousingType = first.newHousingType ?? null
  row.linkedHousingTypes = links.filter((link, at): link is LinkedHousingType => link !== null && at !== representative)
  return links.flatMap((link, at) => link === null ? [] : [{ at, address: at === representative ? {}
    : link.housingTypeId ? { linkedHousingTypeId: link.housingTypeId } : { linkedNewHousingTypeArea: link.newHousingType!.exclusiveArea } }])
}

/**
 * 저장하면 공급행이 연결할 주택형. 연결 주택형 칸을 반영하면 그 값, 아니면 저장 위치의 지금 주택형(칸의 현재값)이다.
 * 저장(applyCells)과 같은 기준이라 검토 화면이 보여 주는 주택형 가격과 저장하는 가격이 같은 주택형을 가리킨다.
 */
export function linkedHousingTypeId(row: RowReview): number | null {
  if (row.subtypes) {
    const choice = row.subtypes[representativeSubtype(row)]?.choice
    return typeof choice === 'number' ? choice : null
  }
  const cell = row.cells.find(item => item.field === 'housingTypeId')
  const id = cell?.selected && !cell.locked ? cell.value : cell?.before
  return typeof id === 'number' ? id : null
}
/** 연결 주택형의 후보 정보. 새 주택형처럼 연결 주택형이 없으면 undefined, 연결했지만 후보에 없어 지금 값을 모르면 null이다. */
export function linkedHousingType(row: RowReview): MatchingHousingType | null | undefined {
  const id = linkedHousingTypeId(row)
  if (id === null) return undefined
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
  next.rows = next.rows.map((row, index) => {
    const old = previous.rows[index], anchor = workspace.supply.rows.find(item => item.key === old.proposal.anchorKey)
    if ((anchor || old.proposal.newComplex) && (old.proposal.destination === 'new' || workspace.supply.rows.some(item => item.key === old.proposal.destination))) {
      const candidates = housing[anchor?.housingComplexId ?? old.proposal.newComplex?.id ?? 0] ?? old.proposal.candidates ?? []
      row = createRowReview(next.extraction, index, { ...old.proposal, candidates, housingTypeId: candidates.some(type => type.id === old.proposal.housingTypeId) ? old.proposal.housingTypeId : null }, workspace)
    }
    row.include = old.include; row.reviewed = old.reviewed; row.newHousingType = old.newHousingType; row.cells = row.cells.map(retain)
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
/** 행 상태. 적용 제외 → 오류 → 확인 필요 → 경고 → 주택형 미연결 → 준비됨 순서로 하나만 보인다. */
export function rowStatus(row: RowReview, problem: PlacementProblem | undefined, pendingCells: boolean): Status {
  if (!row.include) return { label: '적용 제외', tone: 'muted' }
  if (problem?.level === 'error') return { label: '오류', tone: 'error' }
  if (pendingRow(row) || pendingSubtypes(row).length || pendingCells) return { label: '확인 필요', tone: 'pending' }
  if (problem) return { label: '경고', tone: 'warning' }
  if (linkedHousingTypeId(row) === null && !newRepresentative(row)) return { label: '주택형 미연결', tone: 'warning' }
  return { label: '준비됨', tone: 'done' }
}

