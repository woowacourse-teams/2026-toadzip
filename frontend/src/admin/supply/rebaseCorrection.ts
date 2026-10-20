import type { CorrectionRow, CorrectionTarget } from './supplyCorrectionApi'

export type CorrectionConflict = { rowKey: string; targetKey?: string; field: string; mine: unknown; current: unknown }
const rowFields = ['housingComplexId', 'housingTypeId', 'newHousingType', 'excluded', 'displayOrder', 'complexName', 'typeName', 'pnu', 'moveInMonth', 'category', 'householdCount', 'areaOverride', 'exclusiveArea', 'supplyArea', 'targetCountsComparable'] as const
const targetFields = ['excluded', 'displayOrder', 'target', 'supplyRank', 'householdCount', 'reserveCount', 'deposit', 'rent', 'convertedDeposit', 'condition'] as const
const equal = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)
export function rebaseCorrection(base: CorrectionRow[], draft: CorrectionRow[], latest: CorrectionRow[]) {
  const conflicts: CorrectionConflict[] = []
  function merge<T extends CorrectionRow | CorrectionTarget>(before: T, mine: T, current: T, fields: readonly (keyof T)[], rowKey: string, targetKey?: string): T {
    const result = { ...current }
    for (const field of fields) {
      if (equal(before[field], mine[field])) continue
      if (!equal(before[field], current[field]) && !equal(mine[field], current[field])) {
        conflicts.push({ rowKey, targetKey, field: String(field), mine: mine[field], current: current[field] })
      } else result[field] = mine[field]
    }
    return result
  }
  const rows = latest.map(current => {
    const before = base.find(item => item.id === current.id)
    const mine = draft.find(item => item.id === current.id)
    if (!before || !mine) return current
    const result = merge(before, mine, current, rowFields, current.key)
    result.targets = current.targets.map(target => {
      const old = before.targets.find(item => item.id === target.id)
      const edited = mine.targets.find(item => item.id === target.id)
      return old && edited ? merge(old, edited, target, targetFields, current.key, target.key) : target
    })
    for (const edited of mine.targets.filter(item => item.id !== null)) {
      const old = before.targets.find(item => item.id === edited.id)
      if (old && !current.targets.some(item => item.id === edited.id) && !equal(old, edited)) {
        result.targets.push(edited)
        conflicts.push({ rowKey: current.key, targetKey: edited.key, field: '__missing', mine: edited, current: null })
      }
    }
    result.targets.push(...mine.targets.filter(item => item.id === null))
    return result
  })
  for (const edited of draft.filter(item => item.id !== null)) {
    const old = base.find(item => item.id === edited.id)
    if (old && !latest.some(item => item.id === edited.id) && !equal(old, edited)) {
      rows.push(edited)
      conflicts.push({ rowKey: edited.key, field: '__missing', mine: edited, current: null })
    }
  }
  rows.push(...draft.filter(item => item.id === null))
  return { rows, conflicts }
}
export function resolveConflict(rows: CorrectionRow[], conflict: CorrectionConflict, keepMine: boolean) {
  if (conflict.field === '__missing') {
    const newTarget = (target: CorrectionTarget) => ({ ...target, id: null, key: crypto.randomUUID(), protectedValues: false })
    if (conflict.targetKey) return rows.map(row => row.key !== conflict.rowKey ? row : { ...row,
      targets: row.targets.flatMap(target => target.key !== conflict.targetKey ? [target] : keepMine ? [newTarget(target)] : []) })
    return rows.flatMap(row => row.key !== conflict.rowKey ? [row] : keepMine
      ? [{ ...row, id: null, key: crypto.randomUUID(), protectedValues: false, groupId: null,
          sharedHouseholdCount: null, targets: row.targets.map(newTarget) }] : [])
  }
  const value = keepMine ? conflict.mine : conflict.current
  return rows.map(row => row.key !== conflict.rowKey ? row : conflict.targetKey
    ? { ...row, targets: row.targets.map(target => target.key === conflict.targetKey ? { ...target, [conflict.field]: value } : target) }
    : { ...row, [conflict.field]: value }) as CorrectionRow[]
}
