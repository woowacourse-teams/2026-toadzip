import type { ExtractedAnnouncement, JsonObject, JsonUpdateInput, JsonWorkspace } from '../announcement-json/api'
import { blankRow, blankTarget, nextDisplayOrder, type CorrectionRow, type CorrectionTarget, type NewSupplyGroup } from '../supply/supplyCorrectionApi'
import { evidenceFor, noteFor, type Evidence, type Extraction } from './extraction'
import { defaultTargetDestinations, normalizedName, proposeRows, proposeSchedules, type HousingCandidates, type RowProposal, type ScheduleProposal } from './matching'
import { housingTypeLabel } from '../ingest/housingTypeLabel'

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
export type RowReview = { proposal: RowProposal; include: boolean; reviewed: boolean; key: string; cells: Cell[]; targets: TargetReview[] }
export type ScheduleReview = { proposal: ScheduleProposal; include: boolean; reviewed: boolean; cells: Cell[] }
export type Review = { extraction: Extraction; rows: RowReview[]; schedules: ScheduleReview[]; metadata: Cell[]; reception: Cell[] }
export const cellStates: Record<Cell['state'], string> = { confirm: '확인 필요', unresolved: '미확인 · 현재값 유지', notApplicable: '해당 없음', invalid: '형식 확인 필요', same: '같음', changed: '변경', new: '추가' }
/** 접수 방식·일정 유형의 화면 표기 */
export const choices = {
  method: [['ONLINE', '온라인'], ['VISIT', '방문'], ['MAIL', '우편'], ['ETC', '기타']],
  scheduleType: [['APPLICATION', '접수'], ['DOCUMENT_SUBMISSION', '서류 제출'], ['WINNER_ANNOUNCEMENT', '당첨자 발표'], ['CONTRACT', '계약'], ['MOVE_IN', '입주'], ['ETC', '기타']],
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
function cells(extraction: Extraction, prefix: string, specs: FieldSpec[], source: JsonObject, before: JsonObject = {}): Cell[] {
  return specs.map(([field, label, kind, target = field]) => {
    const path = `${prefix}.${field}`, value = normalized(kind, source[field]), previous = normalized(kind, before[target])
    const missing = noteFor(extraction.unresolved, path), unused = noteFor(extraction.notApplicable, path)
    const areaOnly = field === 'sourceHousingTypeName' && areaOnlyTypeName(value)
    let state: Cell['state'] = unused || areaOnly ? 'notApplicable' : missing || value === null ? 'unresolved' : !validCellValue(kind, value) ? 'invalid' : JSON.stringify(value) === JSON.stringify(previous) ? 'same' : previous === null ? 'new' : 'changed'
    const evidence = evidenceFor(extraction.evidence, path)
    const moreDetailed = typeof previous === 'string' && typeof value === 'string' && previous.length > value.length && previous.includes(value) && prefix === 'receptionPlace'
    if (['changed', 'new'].includes(state) && (!evidence || moreDetailed)) state = 'confirm'
    const note = unused?.reason ?? (areaOnly ? '면적만 적힌 값이라 주택형 표기로 쓰지 않습니다.' : undefined) ?? missing?.reason ?? (moreDetailed ? '현재값이 더 자세합니다.' : !evidence ? '이 항목의 PDF 근거를 직접 확인하세요.' : '')
    const selected = state === 'changed' || state === 'new'
    return { path, field, label, kind, before: previous, value, selected, edited: false, reviewed: false, mode: selected ? 'extracted' : 'keep', extracted: { value, state, note }, locked: false, note, state, evidence }
  })
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
export function createRowReview(extraction: Extraction, index: number, proposal: RowProposal, workspace: JsonWorkspace): RowReview {
  const source = extraction.supplyRows[index], current = workspace.supply.rows.find(row => row.key === proposal.destination)
  const targets = defaultTargetDestinations({ targets: source.targets }, current)
  const rowCells = cells(extraction, `supplyRows[${index}]`, rowFields, { ...source.values, housingTypeId: proposal.housingTypeId }, current as unknown as JsonObject)
  if (proposal.sharedIndex !== null || current?.groupId) {
    const count = rowCells.find(cell => cell.field === 'totalSupplyHouseholdCount')!
    count.locked = true; count.selected = false; count.mode = 'keep'; count.note = '공통 모집수는 묶음에 한 번만 기록합니다.'
  }
  return { proposal, include: true, reviewed: false, key: current?.key ?? crypto.randomUUID(), cells: rowCells,
    targets: source.targets.map((target, at) => ({ destination: targets[at], cells: cells(extraction, `supplyRows[${index}].targets[${at}]`, targetFields, target,
      current?.targets.find(item => (item.id ? String(item.id) : item.key) === targets[at]) as unknown as JsonObject) })) }
}
export function createReview(extraction: Extraction, workspace: JsonWorkspace, housing: HousingCandidates): Review {
  const metadata = cells(extraction, 'announcement', metadataFields, extraction.announcement, workspace.detail.data)
  if (workspace.detail.scheduleReviewed) metadata.filter(cell => ['applicationStartDate', 'applicationEndDate'].includes(cell.field)).forEach(cell => { cell.locked = true; cell.selected = false; cell.mode = 'keep'; cell.note = '확인된 접수 기간은 접수 일정에서 수정하세요.' })
  return { extraction, metadata, reception: cells(extraction, 'receptionPlace', receptionFields, extraction.receptionPlace, (workspace.detail.data.receptionPlace ?? {}) as JsonObject),
    rows: proposeRows(extraction, workspace.supply.rows, housing).map((proposal, index) => createRowReview(extraction, index, proposal, workspace)),
    schedules: proposeSchedules(extraction, workspace.events).map((proposal, index) => {
      const values = cells(extraction, `schedules[${index}]`, scheduleFields, extraction.schedules[index], workspace.events.find(event => event.id === proposal.existingIds[0])?.data)
      return { proposal, cells: values, reviewed: false, include: !proposal.needsConfirmation && values.every(cell => validCellValue(cell.kind, cell.value) && !['notApplicable', 'unresolved'].includes(cell.state)) }
    }) }
}
function applyCells(target: JsonObject, values: Cell[], specs: FieldSpec[]) {
  const invalid = values.find(cell => !cell.locked && cell.mode === 'manual' && !validCellValue(cell.kind, cell.value))
  if (invalid) throw new Error(`${invalid.label}의 직접 입력값을 확인하거나 기존 값 사용을 선택하세요.`)
  for (const cell of values.filter(item => item.selected && !item.locked)) {
    if (!validCellValue(cell.kind, cell.value) || ['notApplicable', 'unresolved'].includes(cell.state)) throw new Error(`${cell.label}의 값과 근거를 확인하세요.`)
    const spec = specs.find(([field]) => field === cell.field)!
    target[spec[3] ?? spec[0]] = cell.value
  }
}
export type ReviewSection = 'supply' | 'schedule' | 'reception'
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
export function pendingTarget(target: TargetReview) { return !target.destination || target.destination !== 'exclude' && target.cells.some(pendingCell) }
export function pendingSchedule(schedule: ScheduleReview) { return schedule.proposal.needsConfirmation && !schedule.reviewed }
/** 화면에 보이는 순서(공급정보 → 일정 → 접수처·기본정보)로 남은 확인 항목을 돌려준다. */
export function pendingItems(review: Review): PendingItem[] {
  const items: PendingItem[] = []
  review.rows.forEach((row, index) => {
    if (!row.include) return
    if (pendingRow(row)) items.push({ id: `row:${index}`, section: 'supply' })
    row.cells.filter(pendingCell).forEach(cell => items.push({ id: `cell:${cell.path}`, section: 'supply' }))
    row.targets.forEach((target, at) => { if (pendingTarget(target)) items.push({ id: `target:${index}:${at}`, section: 'supply' }) })
  })
  review.schedules.forEach((schedule, index) => {
    if (pendingSchedule(schedule)) items.push({ id: `schedule:${index}`, section: 'schedule' })
    if (schedule.include) schedule.cells.filter(pendingCell).forEach(cell => items.push({ id: `cell:${cell.path}`, section: 'schedule' }))
  })
  for (const cell of [...review.reception, ...review.metadata].filter(pendingCell)) items.push({ id: `cell:${cell.path}`, section: 'reception' })
  return items
}
/** ChatGPT가 주택형명 대신 면적(26.37, 26.37㎡)을 넣은 경우다. 표기로 쓰면 공개 화면에 면적만 보인다. */
export function areaOnlyTypeName(value: unknown) {
  return typeof value === 'string' && /^\d+(\.\d+)?\s*(㎡|m2)?$/.test(value.trim())
}
export type HousingNameCandidate = { id: number; current: string; pdf: string }
/** 연결한 단지 주택형의 이름이 공고 표기와 다르면 단지 보정 후보로 돌려준다. 저장하지 않고 결과 화면에 표시만 한다. */
export function housingNameCandidates(review: Review): HousingNameCandidate[] {
  const candidates: HousingNameCandidate[] = []
  review.rows.forEach((row, index) => {
    const cell = row.cells.find(item => item.field === 'housingTypeId'), pdf = review.extraction.supplyRows[index].typeName
    const type = row.proposal.candidates.find(item => item.id === (cell?.selected ? cell.value : cell?.before))
    if (row.include && type && pdf && !areaOnlyTypeName(pdf) && normalizedName(type.name) !== normalizedName(pdf) && !candidates.some(item => item.id === type.id)) candidates.push({ id: type.id, current: type.name, pdf })
  })
  return candidates
}
export function allCells(review: Review) { return [...review.metadata, ...review.reception, ...review.rows.flatMap(row => [...row.cells, ...row.targets.flatMap(target => target.cells)]), ...review.schedules.flatMap(schedule => schedule.cells)] }
export type PendingChange = { section: '기본정보' | '접수처' | '공급정보' | '일정'; scope: string; cell: Cell; candidates: RowProposal['candidates'] }
/** 저장하면 실제로 바뀌는 값만 영역별로 모은다. 다른 탭에 있는 변경도 저장 전에 보이게 하려는 목록이다. */
export function pendingChanges(review: Review): PendingChange[] {
  const changed = (cell: Cell) => cell.selected && !cell.locked && JSON.stringify(cell.before) !== JSON.stringify(cell.value)
  const list = (section: PendingChange['section'], scope: string, cells: Cell[], candidates: RowProposal['candidates'] = []) =>
    cells.filter(changed).map(cell => ({ section, scope, cell, candidates }))
  return [
    ...list('기본정보', '공고', review.metadata),
    ...list('접수처', '접수처', review.reception),
    ...review.rows.flatMap((row, index) => {
      if (!row.include) return []
      const source = review.extraction.supplyRows[index], scope = `공급행 ${index + 1} · ${source.complexName ?? '단지 미확인'} · ${source.typeName ?? '주택형 미확인'}`
      return [...list('공급정보', scope, row.cells, row.proposal.candidates), ...row.targets.filter(target => target.destination !== 'exclude')
        .flatMap(target => list('공급정보', `${scope} / ${shown(target.cells.find(cell => cell.field === 'target')?.value)}`, target.cells))]
    }),
    ...review.schedules.flatMap((schedule, index) => schedule.include ? list('일정', `일정 ${index + 1} · ${shown(schedule.cells.find(cell => cell.field === 'name')?.value)}`, schedule.cells) : []),
  ]
}
/** 변경 목록에 쓰는 값 표기. 주택형은 후보의 이름과 면적으로 보여 준다. */
export function changeValue(change: PendingChange, value: unknown) {
  if (change.cell.kind === 'housingType') {
    if (value == null) return '미연결'
    const type = change.candidates.find(item => item.id === value)
    return type ? housingTypeLabel(type, change.candidates) : `#${String(value)}`
  }
  if (change.cell.kind === 'method' || change.cell.kind === 'scheduleType') return choices[change.cell.kind].find(([key]) => key === value)?.[1] ?? shown(value)
  return shown(value)
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
export function assembleReview(review: Review, workspace: JsonWorkspace, evidence: { reason: string; sourceUrl: string; sourcePage: string | null }): JsonUpdateInput {
  const rows = structuredClone(workspace.supply.rows), taken = new Set<string>(), newGroups: NewSupplyGroup[] = []
  for (const [index, entry] of review.rows.entries()) {
    if (!entry.include) continue
    if (!entry.proposal.destination) throw new Error(`공급행 ${index + 1}에서 수정할 기존 공급정보를 선택하거나 새 공급정보로 추가하세요.`)
    const anchor = rows.find(row => row.key === entry.proposal.anchorKey)
    const existing = entry.proposal.destination === 'new' ? undefined : rows.find(row => row.key === entry.proposal.destination)
    if ((!anchor && !entry.proposal.newComplex) || entry.proposal.destination !== 'new' && !existing) throw new Error(`공급행 ${index + 1}의 기존 단지·원천행을 선택하세요.`)
    if (existing && taken.has(existing.key)) throw new Error('같은 기존 공급행을 두 번 갱신할 수 없습니다.')
    if (existing) taken.add(existing.key)
    const complex = entry.proposal.newComplex
    const row: CorrectionRow = existing ?? { ...blankRow(nextDisplayOrder(rows)), key: entry.key,
      complexName: review.extraction.supplyRows[index].complexName || anchor?.complexName || complex!.name,
      pnu: anchor?.pnu ?? complex!.pnu, category: anchor?.category ?? complex!.category,
      housingComplexId: anchor ? anchor.housingComplexId : complex!.id, housingComplexName: anchor ? anchor.housingComplexName : complex!.name }
    if (!row.pnu.trim()) throw new Error(`공급행 ${index + 1}의 소재지 PNU를 확인하세요.`)
    applyCells(row as unknown as JsonObject, entry.cells, rowFields)
    // 공고에 주택형 표기가 없으면 새 행은 연결한 단지 주택형 이름으로 표시한다.
    if (!row.typeName.trim()) row.typeName = entry.proposal.candidates.find(type => type.id === row.housingTypeId)?.name ?? ''
    if (entry.cells.some(cell => cell.field === 'housingTypeId' && cell.selected && cell.value !== null) && !entry.proposal.candidates.some(type => type.id === row.housingTypeId)) throw new Error('연결 주택형이 선택한 단지에 없습니다. 주택형을 다시 선택하세요.')
    if (entry.cells.some(cell => ['exclusiveArea', 'supplyArea'].includes(cell.field) && cell.selected)) row.areaOverride = true
    if (entry.proposal.sharedIndex !== null) row.householdCount = null
    const selectedTargets = new Set<string>()
    for (const target of entry.targets) {
      if (target.destination === 'exclude') continue
      if (!target.destination) throw new Error(`${row.typeName}: 수정할 기존 임대조건을 선택하거나 새 임대조건으로 추가하세요.`)
      if (target.destination !== 'new' && selectedTargets.has(target.destination)) throw new Error('같은 기존 공급대상을 두 번 갱신할 수 없습니다.')
      selectedTargets.add(target.destination)
      const stored = row.targets.find(item => (item.id ? String(item.id) : item.key) === target.destination)
      if (target.destination !== 'new' && !stored) throw new Error('선택한 공급대상이 현재 행에 없습니다.')
      const next: CorrectionTarget = stored ?? blankTarget(row.targets.length)
      applyCells(next as unknown as JsonObject, target.cells, targetFields)
      if (!next.target) throw new Error(`${row.typeName}: 새 공급대상의 이름을 확인하세요.`)
      if (!stored) row.targets.push(next)
    }
    if (!row.typeName) throw new Error('새 공급행의 주택형명을 확인하세요.')
    if (!existing) rows.push(row)
  }
  for (const [index, group] of review.extraction.sharedCounts.entries()) {
    const members = review.rows.filter(row => row.proposal.sharedIndex === index)
    if (members.every(row => !row.include)) continue
    if (members.some(row => !row.include)) throw new Error('공통 모집 묶음은 모든 행을 함께 적용하거나 함께 제외하세요.')
    const current = members.map(row => rows.find(item => item.key === row.key)!)
    if (current.some(row => !row)) throw new Error('공통 모집 묶음의 행 대응을 확인하세요.')
    if (current.every(row => row.groupId !== null && row.groupId === current[0].groupId)) {
      if (current[0].sharedHouseholdCount !== group.count) throw new Error('기존 공통 모집수가 PDF와 다릅니다. 현재 묶음의 원천별 공급행 보정에서 확인하세요.')
      continue
    }
    const original = current.filter(row => row.id !== null)
    if (original.length !== 1 || group.count === null || current.some(row => row.housingTypeId === null)) throw new Error('새 공통 모집 묶음은 기존 한 행과 서로 다른 주택형에 연결한 새 행으로 구성하세요.')
    newGroups.push({ sourceRowId: original[0].id!, rowKeys: current.map(row => row.key), sharedHouseholdCount: group.count })
  }
  const ordered = applyPdfOrder(review, rows)
  const announcement: JsonObject = Object.fromEntries(['name', 'rentalType', 'recruitmentType', 'agencyCode', ...metadataFields.map(([field]) => field)].map(field => [field, workspace.detail.data[field] ?? null]))
  applyCells(announcement, review.metadata, metadataFields)
  const reception = { ...((workspace.detail.data.receptionPlace ?? {}) as JsonObject) }
  applyCells(reception, review.reception, receptionFields)
  const document: ExtractedAnnouncement = { schemaVersion: 'admin-announcement-import/v1', source: { originalUrl: workspace.detail.data.originalUrl, sourceDocumentId: workspace.detail.sourceIdentifier, extractedAt: new Date().toISOString() }, announcement, receptionPlace: reception, supplyRows: ordered.filter(row => review.rows.some(entry => entry.include && entry.key === row.key)).map(row => ({
    complexReference: { sourceComplexName: row.complexName, pnu: row.pnu }, sourceHousingTypeName: row.typeName,
    expectedMoveInMonth: row.moveInMonth, supplyCategory: row.category, totalSupplyHouseholdCount: row.householdCount,
    exclusiveArea: row.exclusiveArea, supplyArea: row.supplyArea, targets: row.targets.filter(target => !target.excluded).map(target => ({
      target: target.target, supplyRank: target.supplyRank, supplyHouseholdCount: target.householdCount, reserveCount: target.reserveCount,
      rentalDeposit: target.deposit, monthlyRent: target.rent, convertedDeposit: target.convertedDeposit, applicationCondition: target.condition,
    })),
  })), schedules: [], attachments: [], unresolvedFields: [] }
  const schedules: JsonUpdateInput['schedules'] = [], used = new Set<number>()
  for (const entry of review.schedules.filter(item => item.include)) {
    const destinations: (number | null)[] = entry.proposal.existingIds.length ? entry.proposal.existingIds : [null]
    for (const id of destinations) {
      if (id !== null && used.has(id)) throw new Error('같은 기존 일정을 두 번 갱신할 수 없습니다.')
      if (id !== null) used.add(id)
      const before = workspace.events.find(event => event.id === id)
      const after = Object.fromEntries(scheduleFields.map(([field]) => [field, before?.data[field] ?? null]))
      applyCells(after, entry.cells, scheduleFields)
      if (entry.cells.some(cell => !validCellValue(cell.kind, after[cell.field]))) throw new Error('일정 유형·이름·시작·종료를 확인하세요. 미확인 일정은 제외하세요.')
      if (String(after.endAt) < String(after.startAt)) throw new Error('일정 종료가 시작보다 빠릅니다.')
      schedules.push({ inputIndex: document.schedules.length, existingId: id }); document.schedules.push(after)
    }
  }
  const fields = [...review.metadata.filter(cell => cell.selected && !cell.locked).map(cell => cell.field), ...review.reception.filter(cell => cell.selected).map(cell => `receptionPlace.${cell.field}`)]
  return { snapshotToken: workspace.snapshotToken, document, fields, schedules, attachments: [], correction: { operationId: crypto.randomUUID(), snapshotToken: workspace.supply.snapshotToken, rows: ordered, newGroups, ...evidence } }
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
  const next = createReview(previous.extraction, workspace, housing)
  const oldCells = new Map(allCells(previous).map(cell => [cell.path, cell]))
  function retain(cell: Cell): Cell { return preserveCellChoice(cell, oldCells.get(cell.path)) }
  next.metadata = next.metadata.map(retain); next.reception = next.reception.map(retain)
  next.rows = next.rows.map((row, index) => {
    const old = previous.rows[index], anchor = workspace.supply.rows.find(item => item.key === old.proposal.anchorKey)
    if ((anchor || old.proposal.newComplex) && (old.proposal.destination === 'new' || workspace.supply.rows.some(item => item.key === old.proposal.destination))) {
      const candidates = housing[anchor?.housingComplexId ?? old.proposal.newComplex?.id ?? 0] ?? old.proposal.candidates ?? []
      row = createRowReview(next.extraction, index, { ...old.proposal, candidates, housingTypeId: candidates.some(type => type.id === old.proposal.housingTypeId) ? old.proposal.housingTypeId : null }, workspace)
    }
    row.include = old.include; row.reviewed = old.reviewed; row.cells = row.cells.map(retain)
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
