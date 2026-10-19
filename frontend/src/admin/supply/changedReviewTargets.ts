import type { CorrectionRow } from './supplyCorrectionApi'

/** 적용 전후 작업실을 비교해 검토가 필요한 행·조건 키를 표시 순서대로 돌려준다. 기존 키는 적용 전후에 유지된다. */
export function changedReviewTargets(before: CorrectionRow[], after: CorrectionRow[]): string[] {
  const previousRows = new Map(before.map(row => [row.key, row]))
  return after.flatMap(row => {
    const previous = previousRows.get(row.key)
    if (!previous) return [row.key, ...row.targets.map(target => target.key)]
    const { targets: _targets, ...fields } = row
    const { targets: _previousTargets, ...previousFields } = previous
    const previousTargets = new Map(previous.targets.map(target => [target.key, target]))
    return [...(JSON.stringify(fields) === JSON.stringify(previousFields) ? [] : [row.key]),
      ...row.targets.filter(target => JSON.stringify(target) !== JSON.stringify(previousTargets.get(target.key))).map(target => target.key)]
  })
}
