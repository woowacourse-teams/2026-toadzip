import { cellsOf, type SheetHousingType, type SupplySheet } from './supplySheet'
import { cellValues } from './sheetEdits'

// 보호 표시는 적용하면 서버가 바꾸므로 값 변경으로 보지 않는다.
const typeValues = ({ adminModified: _modified, ...type }: SheetHousingType) => JSON.stringify(type)

/**
 * 적용 전후 공급표를 비교해 검토가 필요한 키를 표시 순서대로 돌려준다. 주택형 자체 값은 `row:{id}`, 칸의 값(공급대상, 그 대상만의
 * 모집·예비 수, 임대조건)은 `cell:{id}`다. 기존 키는 적용 전후에 유지된다.
 */
export function changedReviewTargets(before: SupplySheet, after: SupplySheet): string[] {
  const previousTypes = new Map(before.housingTypes.map(type => [type.key, type]))
  const previousCells = new Map(before.cells.map(cell => [cell.key, JSON.stringify(cellValues(before, cell))]))
  return [...after.housingTypes].sort((a, b) => a.displayOrder - b.displayOrder).flatMap(type => {
    const previous = previousTypes.get(type.key)
    return [...(previous && typeValues(previous) === typeValues(type) ? [] : [type.key]),
      ...cellsOf(after, type.key).filter(cell => previousCells.get(cell.key) !== JSON.stringify(cellValues(after, cell))).map(cell => cell.key)]
  })
}
