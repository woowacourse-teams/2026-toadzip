import type { CorrectionRow, LhSupplyRow, SupplyWorkspace } from './supplyCorrectionApi'

/** 아직 어떤 공급행도 가리키지 않는 LH 공급 원본 행. 새 공급행 추가를 막지 않지만, 저장하면 공고문 소유가 되어 정제가 이 행을 공급행으로 만들지 않는다. */
export function unattachedLhRows(workspace: SupplyWorkspace): LhSupplyRow[] {
  return workspace.unattachedLhSupplyRows ?? []
}

export function unattachedLhNotice(workspace: SupplyWorkspace) {
  const rows = unattachedLhRows(workspace)
  if (!rows.length) return ''
  const labels = rows.map(row => [row.complexLabel, row.typeName, row.exclusiveArea && `${row.exclusiveArea}㎡`].filter(Boolean).join(' · ')).join(', ')
  return `LH 공급 원본 ${rows.length}행(${labels})이 아직 공급행이 되지 않았습니다. 저장하면 정제가 이 행을 공급행으로 만들지 않으니, PDF로 채우기에서 'LH 행에 연결'로 붙이거나 직접 추가한 뒤 미리보기 경고를 확인하세요.`
}

/**
 * LH 원본으로 펼친 행이 있는 단지에 관리자가 직접 추가한 행. LH 행과 같은 주택형이면 중복이다.
 * LH 한 행을 주택형별로 나눈 행(LH_SPLIT)은 의도한 분리 형제라 중복으로 보지 않는다.
 */
export function possibleLhDuplicates(workspace: SupplyWorkspace): CorrectionRow[] {
  const sources = workspace.rowSources ?? []
  const lhRowKeys = new Set(sources.filter(source => source.category === 'LH_ANNOUNCEMENT_SUPPLY').map(source => source.rowKey))
  const splitRowKeys = new Set(sources.filter(source => source.category === 'LH_SPLIT').map(source => source.rowKey))
  const lhComplexes = new Set(workspace.rows.filter(row => lhRowKeys.has(row.key)).map(row => row.housingComplexId).filter(id => id !== null))
  const deletable = new Set(workspace.deletableRowKeys ?? [])
  return workspace.rows.filter(row => deletable.has(row.key) && !splitRowKeys.has(row.key) && row.housingComplexId !== null && lhComplexes.has(row.housingComplexId))
}

/** 직접 추가 행을 합칠 수 있는 LH 펼침 행. 같은 단지에 연결된 행만 고르며, 편집 중인 값으로 판단한다. */
export function mergeCandidates(workspace: SupplyWorkspace, rows: CorrectionRow[], duplicate: CorrectionRow): CorrectionRow[] {
  const lhRowKeys = new Set((workspace.rowSources ?? []).filter(source => source.category === 'LH_ANNOUNCEMENT_SUPPLY').map(source => source.rowKey))
  return rows.filter(row => lhRowKeys.has(row.key) && !row.excluded && row.housingComplexId === duplicate.housingComplexId)
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
