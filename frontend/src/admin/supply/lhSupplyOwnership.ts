import type { CorrectionRow, LhSupplyRow, SupplyWorkspace } from './supplyCorrectionApi'

/** LH 공급 원본 행이 아직 공급행에 붙지 않았으면 행 분할은 LH 연결 펼침이 맡으므로 새 공급행을 추가하지 않는다. */
export function unattachedLhRows(workspace: SupplyWorkspace): LhSupplyRow[] {
  return workspace.unattachedLhSupplyRows ?? []
}

export function unattachedLhNotice(workspace: SupplyWorkspace) {
  const rows = unattachedLhRows(workspace)
  if (!rows.length) return ''
  const labels = rows.map(row => [row.complexLabel, row.typeName, row.exclusiveArea && `${row.exclusiveArea}㎡`].filter(Boolean).join(' · ')).join(', ')
  return `LH 공급 원본 ${rows.length}행(${labels})이 아직 공급행에 없습니다. 새 공급행을 추가하지 말고 LH 행 연결을 저장한 뒤 공고 정제를 다시 실행하면 LH 주택형별로 펼쳐진 행을 보정할 수 있습니다.`
}

/** LH 원본으로 펼친 행이 있는 단지에 관리자가 직접 추가한 행. LH 행과 같은 주택형이면 중복이다. */
export function possibleLhDuplicates(workspace: SupplyWorkspace): CorrectionRow[] {
  const lhRowKeys = new Set((workspace.rowSources ?? []).filter(source => source.category === 'LH_ANNOUNCEMENT_SUPPLY').map(source => source.rowKey))
  const lhComplexes = new Set(workspace.rows.filter(row => lhRowKeys.has(row.key)).map(row => row.housingComplexId).filter(id => id !== null))
  const deletable = new Set(workspace.deletableRowKeys ?? [])
  return workspace.rows.filter(row => deletable.has(row.key) && row.housingComplexId !== null && lhComplexes.has(row.housingComplexId))
}
