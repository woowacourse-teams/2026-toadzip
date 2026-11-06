import type { JsonPreview, JsonUpdateInput, JsonWorkspace, TypeValues } from '../announcement-json/api'
import type { CorrectionRow, CorrectionTarget, LhSupplyRow, SupplyWorkspace } from '../supply/supplyCorrectionApi'
import { labels } from '../management/fields'
import { lhRowLabel, type HousingCandidates } from './matching'
import { choices, metadataFields, receptionFields, shownDateTime } from './review'

export type SummaryRow = { key: string; id: number | null; typeName: string; householdCount: number | null; housingType: string; linked: string[]
  change: 'same' | 'changed' | 'new' | 'excluded'; excluded: boolean }
export type ComplexSummary = { name: string; before: SummaryRow[]; after: SummaryRow[]; beforeTotal: number | null; afterTotal: number | null; duplicates: string[] }
export type SplitSummary = { label: string; merged: string; result: string; householdCount: number | null; lhHouseholdCount: number | null }
export type TargetChange = { row: string; kind: 'new' | 'changed'; detail: string }
/** 기본정보·접수처·일정·주택형 한 항목의 변경 전후. 값은 화면에 보일 글로 바꿔 둔다. */
export type ValueChange = { label: string; before: string; after: string }
export type ApplySummary = { complexes: ComplexSummary[]; splits: SplitSummary[]; targets: TargetChange[]; warnings: string[]; unattached: string[]
  fields: ValueChange[]; schedules: ValueChange[]; housingTypes: ValueChange[] }

function typeLabel(row: CorrectionRow, housing: HousingCandidates) {
  const types = row.housingComplexId === null ? [] : housing[row.housingComplexId] ?? []
  const name = (id: number) => { const type = types.find(item => item.id === id); return type ? `${type.name} · 전용 ${type.exclusiveArea}㎡` : `주택형 #${id}` }
  const housingType = row.housingTypeId !== null ? name(row.housingTypeId) : row.newHousingType ? `새 주택형 ${row.newHousingType.name} · 전용 ${row.newHousingType.exclusiveArea}㎡` : '주택형 미연결'
  const linked = (row.linkedHousingTypes ?? []).map(link => link.housingTypeId != null ? name(link.housingTypeId) : `새 주택형 ${link.newHousingType!.name}`)
  return { housingType, linked }
}
/** 제외하지 않은 행의 모집수 합. 공통 모집 묶음은 한 번만 센다. 모르는 모집수가 있으면 null이다. */
function total(rows: CorrectionRow[]) {
  let sum = 0
  const groups = new Set<number>()
  for (const row of rows.filter(item => !item.excluded)) {
    if (row.groupId !== null) {
      if (groups.has(row.groupId)) continue
      groups.add(row.groupId)
      if (row.sharedHouseholdCount === null) return null
      sum += row.sharedHouseholdCount
    } else if (row.householdCount === null) return null
    else sum += row.householdCount
  }
  return sum
}
const comparable = (row: CorrectionRow) => JSON.stringify([row.typeName, row.householdCount, row.housingTypeId, row.newHousingType, row.linkedHousingTypes ?? null, row.exclusiveArea, row.supplyArea, row.moveInMonth, row.excluded, row.housingComplexId,
  row.targets.map(target => [target.target, target.supplyRank, target.householdCount, target.reserveCount, target.deposit, target.rent, target.convertedDeposit, target.condition, target.excluded])])
const amount = (value: string | number | null | undefined) => value === null || value === undefined || value === '' ? '없음' : `${Number(value).toLocaleString('ko-KR')}원`
const plain = (value: unknown) => value === null || value === undefined || value === '' ? '없음' : String(value)
/** 대상별 임대조건에서 비교하는 항목. 이름과 함께 바뀐 항목만 '이전 → 이후'로 적는다. */
const targetParts: [label: string, read: (target: CorrectionTarget) => string][] = [['공급 대상', target => plain(target.target)], ['순위', target => plain(target.supplyRank)],
  ['대상별 모집수', target => plain(target.householdCount)], ['예비자 수', target => plain(target.reserveCount)], ['보증금', target => amount(target.deposit)],
  ['월 임대료', target => amount(target.rent)], ['전환보증금', target => amount(target.convertedDeposit)], ['신청 조건', target => plain(target.condition)]]
const typeParts: [key: keyof TypeValues, read: (values: TypeValues) => string][] = [['name', values => `이름 ${values.name}`],
  ['supplyArea', values => `공급면적 ${values.supplyArea == null ? '없음' : `${values.supplyArea}㎡`}`], ['householdCount', values => `세대수 ${values.householdCount ?? '없음'}`],
  ['basicDeposit', values => `기본 보증금 ${amount(values.basicDeposit)}`], ['basicMonthlyRent', values => `기본 월 임대료 ${amount(values.basicMonthlyRent)}`]]
const fieldLabels: Record<string, string> = Object.fromEntries([...metadataFields.map(([field, label]) => [field, label]), ...receptionFields.map(([field, label]) => [`receptionPlace.${field}`, label]),
  ['rentalType', '공급 유형'], ['recruitmentType', '모집 유형'], ['name', '공고명']])
function fieldValue(field: string, value: unknown) {
  if (value === null || value === undefined || value === '') return '없음'
  if (field === 'receptionPlace.method') return choices.method.find(([key]) => key === value)?.[1] ?? String(value)
  return typeof value === 'string' && labels[value] ? labels[value] : shownDateTime(value)
}
const scheduleRange = (value: Record<string, unknown>) => `${shownDateTime(value.startAt)}${value.endAt ? ` ~ ${shownDateTime(value.endAt)}` : ''}`
const scheduleType = (value: unknown) => choices.scheduleType.find(([key]) => key === value)?.[1] ?? plain(value)
const scheduleName = (value: Record<string, unknown>) => plain(value.name ?? scheduleType(value.scheduleType))
/** 미리보기의 기본정보·접수처·일정·주택형 갱신을 변경 전후 값으로 바꾼다. 주택형은 바뀐 값만, 새 주택형은 만들 값을 모두 적는다. */
function valueChanges(preview: JsonPreview) {
  const fields = preview.fields.map(change => ({ label: fieldLabels[change.field] ?? change.field, before: fieldValue(change.field, change.before), after: fieldValue(change.field, change.after) }))
  // 일정은 이름·종류가 바뀌었으면 그 값도 전후 문구에 넣어, 날짜가 같아도 무엇이 바뀌는지 보이게 한다.
  const schedules = preview.schedules.map(({ before, after }) => {
    const named = before !== null && scheduleName(before) !== scheduleName(after), typed = before !== null && before.scheduleType !== after.scheduleType
    const text = (value: Record<string, unknown>) => [named && scheduleName(value), typed && scheduleType(value.scheduleType), scheduleRange(value)].filter(Boolean).join(' · ')
    return { label: scheduleName(after), before: before ? text(before) : '새 일정', after: text(after) }
  })
  const housingTypes = preview.housingTypes.map(change => {
    const shown = change.before === null ? typeParts.filter(([key]) => key === 'name' || key === 'supplyArea' || key === 'householdCount' || change.after[key] != null)
      : typeParts.filter(([key]) => (change.before![key] ?? null) !== (change.after[key] ?? null))
    return { label: change.housingTypeId === null ? `새 주택형 「${change.after.name}」` : `#${change.housingTypeId} 「${change.typeName}」`,
      before: change.before === null ? '없음' : shown.map(([, read]) => read(change.before!)).join(' · '), after: shown.map(([, read]) => read(change.after)).join(' · ') }
  })
  return { fields, schedules, housingTypes }
}
const targetLabel = (target: CorrectionTarget) => `${target.target}${target.supplyRank ? ` ${target.supplyRank}` : ''} · 보증금 ${amount(target.deposit)} · 월 ${amount(target.rent)}`

/**
 * 적용 전 요약. 서버 미리보기의 변경 전후 공급행을 단지별로 묶어 모집수 합계와 같은 주택형 중복을 보이고,
 * 통합행 분리 묶음이 통합행을 어떻게 바꾸는지, 추가·변경되는 임대조건과 서버 경고·남는 LH 행을 함께 돌려준다.
 */
export function summarizePreview(preview: JsonPreview, housing: HousingCandidates, lhRows: LhSupplyRow[]): ApplySummary {
  const supply = preview.supply
  const before = supply?.before ?? [], after = supply?.after ?? []
  const complexKey = (row: CorrectionRow) => row.housingComplexId === null ? `name:${row.complexName}` : `id:${row.housingComplexId}`
  const keys = [...new Set([...before, ...after].map(complexKey))]
  const summaryRow = (row: CorrectionRow, change: SummaryRow['change']): SummaryRow => ({ key: row.key, id: row.id, typeName: row.typeName, householdCount: row.groupId !== null ? row.sharedHouseholdCount : row.householdCount, excluded: row.excluded, change, ...typeLabel(row, housing) })
  const complexes = keys.map(key => {
    const old = before.filter(row => complexKey(row) === key), next = after.filter(row => complexKey(row) === key)
    const sample = next[0] ?? old[0]
    const types = new Map<number, string[]>()
    for (const row of next.filter(item => !item.excluded && item.housingTypeId !== null)) types.set(row.housingTypeId!, [...types.get(row.housingTypeId!) ?? [], row.typeName])
    return { name: sample.housingComplexName ?? sample.complexName, beforeTotal: total(old), afterTotal: total(next),
      before: old.map(row => summaryRow(row, 'same')),
      after: next.map(row => { const previous = old.find(item => item.key === row.key)
        return summaryRow(row, row.excluded && !previous?.excluded ? 'excluded' : !previous ? 'new' : comparable(previous) !== comparable(row) ? 'changed' : 'same') }),
      duplicates: [...types].filter(([, names]) => names.length > 1).map(([id, names]) => `${typeLabel({ ...next[0], housingTypeId: id, newHousingType: null, linkedHousingTypes: [] }, housing).housingType}에 공급행 ${names.length}개(${names.join(', ')})가 연결됩니다.`) }
  })
  const splits = (supply?.splits ?? []).map(split => {
    const lh = lhRows.find(row => row.rowIdentifier === split.lhSourceRowIdentifier)
    const merged = before.find(row => row.key === lh?.attachedRowKey)
    const rows = split.rowKeys.map(key => after.find(row => row.key === key)).filter((row): row is CorrectionRow => Boolean(row))
    const head = rows.find(row => row.key === merged?.key)
    return { label: lh ? lhRowLabel(lh) : split.lhSourceRowIdentifier,
      merged: merged ? `통합행 #${String(merged.id)} ${merged.typeName} · 모집 ${merged.householdCount ?? '미확인'}` : '통합행 없음',
      result: [head ? `첫 분리행 ${head.typeName} ${head.householdCount ?? '미확인'}세대(ID 유지)` : '', ...rows.filter(row => row !== head).map(row => `새 분리행 ${row.typeName} ${row.householdCount ?? '미확인'}세대`)].filter(Boolean).join(' · '),
      householdCount: split.householdCount, lhHouseholdCount: split.lhHouseholdCount }
  })
  const targets = after.flatMap(row => {
    const previous = before.find(item => item.key === row.key)
    return row.targets.filter(target => !target.excluded).flatMap((target): TargetChange[] => {
      const old = target.id === null ? undefined : previous?.targets.find(item => item.id === target.id)
      if (!old) return [{ row: row.typeName, kind: 'new', detail: targetLabel(target) }]
      const changed = targetParts.filter(([, read]) => read(old) !== read(target)).map(([label, read]) => `${label} ${read(old)} → ${read(target)}`)
      return changed.length ? [{ row: row.typeName, kind: 'changed', detail: [target.target, ...changed].join(' · ') }] : []
    })
  })
  return { complexes, splits, targets, warnings: [...new Set([...preview.warnings, ...supply?.warnings ?? [], ...preview.housingTypeWarnings])],
    unattached: (supply?.unattachedLhSupplyRows ?? []).map(lhRowLabel), ...valueChanges(preview) }
}

/**
 * 같은 요청을 다시 보내 409(ADMIN_DATA_CONFLICT)를 받았을 때, 보내려던 공급행이 최신 공급행에 이미 들어 있는지.
 * 새 행은 저장 후 키가 바뀔 수 있어 단지·표기·모집수·전용면적·주택형으로 대응한다. 분리(SPLIT)·연결(ATTACH) 행은 값만 같아서는 안 되고,
 * 대응한 행이 최신 lhSupplyRows에서 그 LH 행의 분리행(splitRowKeys)이거나 붙은 행(attachedRowKey)이어야 한다.
 */
export function correctionReflected(intended: CorrectionRow[], latest: Pick<SupplyWorkspace, 'rows' | 'lhSupplyRows'>) {
  const signature = (row: CorrectionRow) => JSON.stringify([row.housingComplexId, row.typeName, row.householdCount, row.exclusiveArea, row.housingTypeId, row.excluded])
  const lh = (identifier: string | undefined) => (latest.lhSupplyRows ?? []).find(row => row.rowIdentifier === identifier)
  const lineage = (row: CorrectionRow, saved: CorrectionRow) => row.saveMode === 'SPLIT' ? Boolean(lh(row.lhSourceRowIdentifier)?.splitRowKeys?.includes(saved.key))
    : row.saveMode === 'ATTACH' ? lh(row.lhSourceRowIdentifier)?.attachedRowKey === saved.key : true
  const pool = [...latest.rows]
  return intended.every(row => {
    const at = pool.findIndex(saved => signature(saved) === signature(row) && lineage(row, saved))
    if (at < 0) return false
    pool.splice(at, 1)
    return true
  })
}

/**
 * 같은 요청을 이미 처리했다는 응답일 때, 이 요청이 고친 기본정보·접수처와 일정이 최신값에 들어 있는지.
 * 공급행만 대조하면 기본정보·일정만 고친 요청은 저장되지 않았어도 반영된 것으로 보이므로 함께 확인한다.
 */
export function documentReflected(input: Pick<JsonUpdateInput, 'document' | 'fields' | 'schedules'>, latest: Pick<JsonWorkspace, 'detail' | 'events'>) {
  const at = (root: unknown, path: string) => path.split('.').reduce<unknown>((node, key) => typeof node === 'object' && node !== null ? (node as Record<string, unknown>)[key] : undefined, root)
  const same = (left: unknown, right: unknown) => JSON.stringify(left ?? null) === JSON.stringify(right ?? null)
  const fields = input.fields.every(field => same(at(latest.detail.data, field), at(input.document, field.startsWith('receptionPlace.') ? field : `announcement.${field}`)))
  const keys = ['scheduleType', 'name', 'startAt', 'endAt']
  const schedules = input.schedules.every(selection => latest.events.some(event => (selection.existingId === null || event.id === selection.existingId)
    && keys.every(key => same(event.data[key], input.document.schedules[selection.inputIndex]?.[key]))))
  return fields && schedules
}
