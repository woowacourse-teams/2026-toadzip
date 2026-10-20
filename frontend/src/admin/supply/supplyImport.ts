import { isRecord } from '../management/managementContract'
import { blankRow, blankTarget, parseCorrectionInput, type CorrectionRow } from './supplyCorrectionApi'
export type ImportedSupply = { rows: Record<string, unknown>[] }
const rowFields = ['complexName', 'typeName', 'pnu', 'moveInMonth', 'category', 'householdCount', 'exclusiveArea', 'supplyArea', 'excluded', 'targets']
const targetFields = ['id', 'target', 'supplyRank', 'householdCount', 'reserveCount', 'deposit', 'rent', 'convertedDeposit', 'condition', 'excluded']
export function parseSupplyImport(text: string): ImportedSupply {
  const input: unknown = JSON.parse(text)
  if (!isRecord(input) || input.areaUnit !== 'm2' || input.moneyUnit !== 'KRW' || !Array.isArray(input.rows) || !input.rows.length || input.rows.length > 500) throw new Error('areaUnit은 m2, moneyUnit은 KRW로 명시하고 rows에 1~500개 공급행을 넣으세요.')
  for (const row of input.rows) {
    if (!isRecord(row) || Object.keys(row).some(key => !rowFields.includes(key))) throw new Error('지원하지 않는 공급행 항목이 있습니다. 입력 예시의 항목을 사용하세요.')
    if (row.targets !== undefined && (!Array.isArray(row.targets) || row.targets.length > 200 || row.targets.some(target => !isRecord(target) || Object.keys(target).some(key => !targetFields.includes(key))))) throw new Error('공급대상 형식 또는 항목을 확인하세요.')
  }
  return { rows: input.rows as Record<string, unknown>[] }
}
export function mergeSupplyImport(current: CorrectionRow[], imported: ImportedSupply, destinations: string[]) {
  const selected = destinations.filter(value => value !== '' && value !== 'new')
  if (new Set(selected).size !== selected.length) throw new Error('한 공급행에 여러 입력행을 겹쳐 적용할 수 없습니다. 대응을 다시 선택하세요.')
  let result = [...current]
  imported.rows.forEach((input, index) => {
    const destination = destinations[index]
    if (!destination) return
    const before = destination === 'new' ? blankRow(result.length) : result.find(row => row.key === destination)
    if (!before) throw new Error('선택한 공급행이 사라졌습니다. 대응을 다시 확인하세요.')
    const { targets, ...values } = input
    const next = { ...before, ...values } as CorrectionRow
    if ('exclusiveArea' in values || 'supplyArea' in values) next.areaOverride = true
    next.targets = [...before.targets]
    const targetIds = new Set<number>()
    for (const value of (targets ?? []) as Record<string, unknown>[]) {
      const { id, ...fields } = value
      if (id !== undefined && id !== null && (typeof id !== 'number' || !Number.isSafeInteger(id) || id <= 0 || targetIds.has(id))) throw new Error('공급대상 ID가 잘못되었거나 중복되었습니다.')
      const old = id ? before.targets.find(target => target.id === id) : blankTarget(next.targets.length)
      if (!old) throw new Error('선택한 행에 속하지 않는 공급대상 ID입니다. 다른 행의 조건을 이동하지 않습니다.')
      if (typeof id === 'number') targetIds.add(id)
      const target = { ...old, ...fields }
      if (id) next.targets = next.targets.map(item => item.id === id ? target : item)
      else next.targets.push(target)
    }
    // Reuse the ordinary correction contract; amounts must remain exact won strings.
    const checked = parseCorrectionInput({ operationId: 'draft', snapshotToken: 'draft', reason: '', sourceUrl: '', sourcePage: null, rows: [next] }).rows[0]
    if (destination === 'new') result.push(checked)
    else result = result.map(row => row.key === destination ? checked : row)
  })
  return result
}
