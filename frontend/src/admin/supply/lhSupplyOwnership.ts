import type { CorrectionRow, LhSupplyRow, SupplyWorkspace } from './supplyCorrectionApi'

/** 같은 공고 단지·전용면적 공급행이 없어 어느 공급행에도 붙지 않은 LH 공급 원본 행. 세대수 검산용 정보이며 저장을 막지 않는다. */
export function unattachedLhRows(workspace: SupplyWorkspace): LhSupplyRow[] {
  return workspace.unattachedLhSupplyRows ?? []
}

export function unattachedLhNotice(workspace: SupplyWorkspace) {
  const rows = unattachedLhRows(workspace)
  if (!rows.length) return ''
  const labels = rows.map(row => [row.complexLabel, row.typeName, row.exclusiveArea && `${row.exclusiveArea}㎡`].filter(Boolean).join(' · ')).join(', ')
  return `LH 공급 원본 ${rows.length}행(${labels})과 전용면적이 같은 공급행이 없습니다. LH 행은 세대수 검산용 참고 정보라 저장을 막지 않습니다. 이 공고가 그 주택형을 모집하면 PDF로 채우기나 직접 추가로 그 전용면적의 공급행을 만드세요.`
}

/**
 * LH 원천에서 온 공급행(지울 수 없는 행)에 LH 줄이 붙은 단지에 관리자가 직접 추가한 행. LH 원천 행과 같은 주택형이면 중복이다.
 * LH 줄은 같은 전용면적 공급행에 면적으로 붙으므로 직접 추가한 행에도 붙을 수 있다. 그 행 자신은 LH 원천 행으로 보지 않는다.
 */
export function possibleLhDuplicates(workspace: SupplyWorkspace): CorrectionRow[] {
  const deletable = new Set(workspace.deletableRowKeys ?? [])
  const lhComplexes = new Set(lhSourceRows(workspace, workspace.rows).map(row => row.housingComplexId).filter(id => id !== null))
  return workspace.rows.filter(row => deletable.has(row.key) && row.housingComplexId !== null && lhComplexes.has(row.housingComplexId))
}

/** 직접 추가 행을 합칠 수 있는 LH 원천 행. 같은 단지에 연결된 행만 고르고 자기 자신과 지울 수 있는 행은 빼며, 편집 중인 값으로 판단한다. */
export function mergeCandidates(workspace: SupplyWorkspace, rows: CorrectionRow[], duplicate: CorrectionRow): CorrectionRow[] {
  return lhSourceRows(workspace, rows).filter(row => row.key !== duplicate.key && !row.excluded && row.housingComplexId === duplicate.housingComplexId)
}

/** LH 줄이 붙은 공급행 중 관리자가 직접 추가하지 않은(지울 수 없는) 행 */
function lhSourceRows(workspace: SupplyWorkspace, rows: CorrectionRow[]) {
  const deletable = new Set(workspace.deletableRowKeys ?? [])
  const lhRowKeys = new Set((workspace.rowSources ?? []).filter(source => source.category === 'LH_ANNOUNCEMENT_SUPPLY').map(source => source.rowKey))
  return rows.filter(row => lhRowKeys.has(row.key) && !deletable.has(row.key))
}

/** 같은 주택형에 연결된 후보가 하나면 그 행을 제안한다. 이름·면적만으로는 제안하지 않는다. */
export function suggestedMergeTarget(candidates: CorrectionRow[], duplicate: CorrectionRow): CorrectionRow | null {
  const sameType = duplicate.housingTypeId === null ? [] : candidates.filter(row => row.housingTypeId === duplicate.housingTypeId)
  return sameType.length === 1 ? sameType[0] : null
}

/** 펼침 행에 제외되지 않은 임대조건이 있으면 어느 조건을 남길지 관리자가 먼저 정리해야 한다. */
export function mergeBlocked(target: CorrectionRow) {
  return target.targets.some(item => !item.excluded)
}

/**
 * 직접 추가 행(관리자가 확인한 값)의 비어 있지 않은 칸과 임대조건을 펼침 행으로 옮긴다.
 * 단지·주택형 연결은 짝을 정한 근거라 그대로 둔다. 임대조건은 새 조건으로 복사해 직접 추가 행을 지워도 남게 한다.
 */
export function mergeInto(target: CorrectionRow, duplicate: CorrectionRow, newKey: () => string = () => crypto.randomUUID()): CorrectionRow {
  const copied = duplicate.targets.filter(item => !item.excluded)
    .map((item, index) => ({ ...item, id: null, key: newKey(), displayOrder: target.targets.length + index }))
  return {
    ...target,
    typeName: duplicate.typeName.trim() ? duplicate.typeName : target.typeName,
    householdCount: duplicate.householdCount ?? target.householdCount,
    moveInMonth: duplicate.moveInMonth ?? target.moveInMonth,
    category: duplicate.category,
    areaOverride: duplicate.areaOverride || target.areaOverride,
    exclusiveArea: duplicate.areaOverride ? duplicate.exclusiveArea : target.exclusiveArea,
    supplyArea: duplicate.areaOverride ? duplicate.supplyArea : target.supplyArea,
    targets: [...target.targets, ...copied],
  }
}
