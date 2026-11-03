import type { JsonPreview } from '../announcement-json/api'
import type { CorrectionRow, CorrectionTarget, LhSupplyRow, SupplyWorkspace } from '../supply/supplyCorrectionApi'
import { lhRowLabel, type HousingCandidates } from './matching'

export type SummaryRow = { key: string; id: number | null; typeName: string; householdCount: number | null; housingType: string; linked: string[]
  change: 'same' | 'changed' | 'new' | 'excluded'; excluded: boolean }
export type ComplexSummary = { name: string; before: SummaryRow[]; after: SummaryRow[]; beforeTotal: number | null; afterTotal: number | null; duplicates: string[] }
export type SplitSummary = { label: string; merged: string; result: string; householdCount: number | null; lhHouseholdCount: number | null }
export type TargetChange = { row: string; kind: 'new' | 'changed'; detail: string }
export type ApplySummary = { complexes: ComplexSummary[]; splits: SplitSummary[]; targets: TargetChange[]; warnings: string[]; unattached: string[]; fieldCount: number; scheduleCount: number }

const won = (value: string | null) => value === null ? '미확인' : `${Number(value).toLocaleString('ko-KR')}원`
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
const targetLabel = (target: CorrectionTarget) => `${target.target}${target.supplyRank ? ` ${target.supplyRank}` : ''} · 보증금 ${won(target.deposit)} · 월 ${won(target.rent)}`

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
      return targetLabel(old) !== targetLabel(target) || old.householdCount !== target.householdCount || old.reserveCount !== target.reserveCount || old.condition !== target.condition
        ? [{ row: row.typeName, kind: 'changed', detail: `${targetLabel(old)} → ${targetLabel(target)}` }] : []
    })
  })
  return { complexes, splits, targets, warnings: [...new Set([...preview.warnings, ...supply?.warnings ?? [], ...preview.housingTypeWarnings])],
    unattached: (supply?.unattachedLhSupplyRows ?? []).map(lhRowLabel), fieldCount: preview.fields.length, scheduleCount: preview.schedules.length }
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
